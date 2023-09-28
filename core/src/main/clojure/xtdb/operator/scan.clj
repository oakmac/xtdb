(ns xtdb.operator.scan
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.bloom :as bloom]
            [xtdb.buffer-pool :as bp]
            [xtdb.expression :as expr]
            [xtdb.expression.metadata :as expr.meta]
            xtdb.indexer.live-index
            [xtdb.logical-plan :as lp]
            [xtdb.metadata :as meta]
            xtdb.object-store
            [xtdb.trie :as trie]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.reader :as vr]
            [xtdb.vector.writer :as vw]
            xtdb.watermark)
  (:import (clojure.lang IPersistentMap MapEntry)
           (java.io Closeable)
           java.nio.ByteBuffer
           (java.util HashMap Iterator LinkedList List Map)
           (java.util.function IntPredicate)
           (java.util.stream IntStream)
           (org.apache.arrow.memory ArrowBuf BufferAllocator)
           [org.apache.arrow.memory.util ArrowBufPointer]
           (org.apache.arrow.vector VectorLoader VectorSchemaRoot)
           [org.roaringbitmap.buffer MutableRoaringBitmap]
           xtdb.api.protocols.TransactionInstant
           xtdb.buffer_pool.IBufferPool
           xtdb.ICursor
           (xtdb.metadata IMetadataManager IMetadataPredicate ITableMetadata)
           xtdb.object_store.ObjectStore
           xtdb.operator.IRelationSelector
           (xtdb.trie ArrowHashTrie$Leaf HashTrie LiveHashTrie$Leaf ScanLeafPointer)
           (xtdb.util TemporalBounds TemporalBounds$TemporalColumn)
           (xtdb.vector IRelationWriter IRowCopier IVectorReader IVectorWriter RelationReader)
           (xtdb.watermark ILiveTableWatermark IWatermark IWatermarkSource Watermark)))

(s/def ::table symbol?)

;; TODO be good to just specify a single expression here and have the interpreter split it
;; into metadata + col-preds - the former can accept more than just `(and ~@col-preds)
(defmethod lp/ra-expr :scan [_]
  (s/cat :op #{:scan}
         :scan-opts (s/keys :req-un [::table]
                            :opt-un [::lp/for-valid-time ::lp/for-system-time ::lp/default-all-valid-time?])
         :columns (s/coll-of (s/or :column ::lp/column
                                   :select ::lp/column-expression))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface IScanEmitter
  (tableColNames [^xtdb.watermark.IWatermark wm, ^String table-name])
  (allTableColNames [^xtdb.watermark.IWatermark wm])
  (scanColTypes [^xtdb.watermark.IWatermark wm, scan-cols])
  (emitScan [scan-expr scan-col-types param-types]))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn ->scan-cols [{:keys [columns], {:keys [table]} :scan-opts}]
  (for [[col-tag col-arg] columns]
    [table (case col-tag
             :column col-arg
             :select (key (first col-arg)))]))

(def ^:dynamic *column->pushdown-bloom* {})

(defn- ->temporal-bounds [^RelationReader params, {^TransactionInstant basis-tx :tx}, {:keys [for-valid-time for-system-time]}]
  (let [bounds (TemporalBounds.)]
    (letfn [(->time-μs [[tag arg]]
              (case tag
                :literal (-> arg
                             (util/sql-temporal->micros (.getZone expr/*clock*)))
                :param (-> (-> (.readerForName params (name arg))
                               (.getObject 0))
                           (util/sql-temporal->micros (.getZone expr/*clock*)))
                :now (-> (.instant expr/*clock*)
                         (util/instant->micros))))]

      (when-let [system-time (some-> basis-tx (.system-time) util/instant->micros)]
        (.lte (.systemFrom bounds) system-time)

        (when-not for-system-time
          (.gt (.systemTo bounds) system-time)))

      (letfn [(apply-constraint [constraint ^TemporalBounds$TemporalColumn start-col, ^TemporalBounds$TemporalColumn end-col]
                (when-let [[tag & args] constraint]
                  (case tag
                    :at (let [[at] args
                              at-μs (->time-μs at)]
                          (.lte start-col at-μs)
                          (.gt end-col at-μs))

                    ;; overlaps [time-from time-to]
                    :in (let [[from to] args]
                          (.gt end-col (->time-μs (or from [:now])))
                          (when to
                            (.lt start-col (->time-μs to))))

                    :between (let [[from to] args]
                               (.gt end-col (->time-μs (or from [:now])))
                               (when to
                                 (.lte start-col (->time-μs to))))

                    :all-time nil)))]

        (apply-constraint for-valid-time (.validFrom bounds) (.validTo bounds))
        (apply-constraint for-system-time (.systemFrom bounds) (.systemTo bounds))))
    bounds))

(defn tables-with-cols [basis ^IWatermarkSource wm-src ^IScanEmitter scan-emitter]
  (let [{:keys [tx, after-tx]} basis
        wm-tx (or tx after-tx)]
    (with-open [^Watermark wm (.openWatermark wm-src wm-tx)]
      (.allTableColNames scan-emitter wm))))

;; As the algorithm processes events in reverse system time order, one can
;; immediately write out the system-to times when having finished an event.
;; The system-to times are not relevant for processing earlier events.
(defrecord Rectangle [^long valid-from, ^long valid-to, ^long sys-from])

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface RowConsumer
  (^void accept [^int idx, ^long validFrom, ^long validTo, ^long systemFrom, ^long systemTo]))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn- copy-row-consumer [^IRelationWriter out-rel, ^RelationReader leaf-rel, col-names]
  (letfn [(writer-for [normalised-col-name]
            (let [wtrs (->> col-names
                            (into [] (keep (fn [col-name]
                                             (when (= normalised-col-name (util/str->normal-form-str col-name))
                                               (.writerForName out-rel col-name types/temporal-col-type))))))]
              (reify IVectorWriter
                (writeLong [_ l]
                  (doseq [^IVectorWriter wtr wtrs]
                    (.writeLong wtr l))))))]
    (let [op-rdr (.readerForName leaf-rel "op")
          put-rdr (.legReader op-rdr :put)
          doc-rdr (.structKeyReader put-rdr "xt$doc")

          row-copiers (object-array
                       (for [col-name col-names
                             :let [normalized-name (util/str->normal-form-str col-name)
                                   ^IVectorReader rdr (case normalized-name
                                                        "xt$iid" (.readerForName leaf-rel "xt$iid")
                                                        ("xt$system_from" "xt$system_to" "xt$valid_from" "xt$valid_to") nil
                                                        (.structKeyReader doc-rdr normalized-name))]
                             :when rdr]
                         (.rowCopier rdr
                                     (case normalized-name
                                       "xt$iid" (.writerForName out-rel col-name [:fixed-size-binary 16])
                                       (.writerForName out-rel col-name)))))

          ^IVectorWriter valid-from-wtr (writer-for "xt$valid_from")
          ^IVectorWriter valid-to-wtr (writer-for "xt$valid_to")
          ^IVectorWriter sys-from-wtr (writer-for "xt$system_from")
          ^IVectorWriter sys-to-wtr (writer-for "xt$system_to")]

      (reify RowConsumer
        (accept [_ idx valid-from valid-to sys-from sys-to]
          (.startRow out-rel)

          (dotimes [i (alength row-copiers)]
            (let [^IRowCopier copier (aget row-copiers i)]
              (.copyRow copier idx)))

          (.writeLong valid-from-wtr valid-from)
          (.writeLong valid-to-wtr valid-to)
          (.writeLong sys-from-wtr sys-from)
          (.writeLong sys-to-wtr sys-to)

          (.endRow out-rel))))))

(defn- wrap-temporal-bounds ^xtdb.operator.scan.RowConsumer [^RowConsumer rc, ^TemporalBounds temporal-bounds]
  (reify RowConsumer
    (accept [_ idx valid-from valid-to sys-from sys-to]
      (when (and (.inRange temporal-bounds valid-from valid-to sys-from sys-to)
                 (not= valid-from valid-to)
                 (not= sys-from sys-to))
        (.accept rc idx valid-from valid-to sys-from sys-to)))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface EventResolver
  (^void resolveEvent [^int idx, ^long validFrom, ^long validTo, ^long systemFrom
                       ^xtdb.operator.scan.RowConsumer rowConsumer])
  (^void nextIid []))

(defn event-resolver
  (^xtdb.operator.scan.EventResolver [] (event-resolver (LinkedList.)))

  (^xtdb.operator.scan.EventResolver [^List !ranges]
   (reify EventResolver
     (nextIid [_]
       (.clear !ranges))

     ;; https://en.wikipedia.org/wiki/Allen%27s_interval_algebra
     (resolveEvent [_ idx valid-from valid-to system-from rc]
       (when rc
         (let [itr (.iterator !ranges)]
           (loop [valid-from valid-from]
             (when (< valid-from valid-to)
               (if-not (.hasNext itr)
                 (.accept rc idx valid-from valid-to system-from util/end-of-time-μs)

                 (let [^Rectangle r (.next itr)]
                   (if (<= (.valid-to r) valid-from)
                     ;; state #{< m} event
                     (recur valid-from)

                     ;; state #{> mi o oi s si d di f fi =} event
                     (do
                       (when (< valid-from (.valid-from r))
                         ;; state #{> mi oi d f} event
                         (let [valid-to (min valid-to (.valid-from r))]
                           (when (< valid-from valid-to)
                             (.accept rc idx valid-from valid-to
                                      system-from util/end-of-time-μs))))

                       (let [valid-from (max valid-from (.valid-from r))
                             valid-to (min valid-to (.valid-to r))]
                         (when (< valid-from valid-to)
                           (.accept rc idx valid-from valid-to system-from (.sys-from r))))

                       (when (< (.valid-to r) valid-to)
                         ;; state #{o s d} event
                         (recur (.valid-to r)))))))))))

       (let [itr (.listIterator !ranges)]
         (loop [ev-added? false]
           (let [^Rectangle r (when (.hasNext itr)
                                (.next itr))]
             (cond
               (nil? r) (when-not ev-added?
                          (.add itr (Rectangle. valid-from valid-to system-from)))

               ;; state #{< m} event
               (<= (.valid-to r) valid-from) (recur ev-added?)

               ;; state #{> mi o oi s si d di f fi =} event
               :else (do
                       (if (< (.valid-from r) valid-from)
                         ;; state #{o di fi} event
                         (.set itr (Rectangle. (.valid-from r) valid-from (.sys-from r)))

                         ;; state #{> mi oi s si d f =} event
                         (.remove itr))

                       (when-not ev-added?
                         (.add itr (Rectangle. valid-from valid-to system-from)))

                       (when (< valid-to (.valid-to r))
                         ;; state #{> mi oi si di}
                         (let [valid-from (max valid-to (.valid-from r))]
                           (when (< valid-from (.valid-to r))
                             (.add itr (Rectangle. valid-from (.valid-to r) (.sys-from r))))))

                       (recur true)))))

         #_ ; asserting the ranges invariant isn't ideal on the fast path
         (assert (->> (partition 2 1 !ranges)
                      (every? (fn [[^Rectangle r1 ^Rectangle r2]]
                                (<= (.valid-to r1) (.valid-from r2)))))
                 {:ranges (mapv (juxt (comp util/micros->instant :valid-from)
                                      (comp util/micros->instant :valid-to)
                                      (comp util/micros->instant :sys-from))
                                !ranges)
                  :ev [(util/micros->instant valid-from)
                       (util/micros->instant valid-to)
                       (util/micros->instant system-from)]}))))))

(defn- duplicate-ptr [^ArrowBufPointer dst, ^ArrowBufPointer src]
  (.set dst (.getBuf src) (.getOffset src) (.getLength src)))

(defn pick-leaf-row! [^ScanLeafPointer leaf-ptr
                      ^TemporalBounds temporal-bounds,
                      {:keys [^EventResolver ev-resolver
                              skip-iid-ptr prev-iid-ptr current-iid-ptr]}]
  (when-not (= skip-iid-ptr (.getIidPointer leaf-ptr current-iid-ptr))
    (when-not (= prev-iid-ptr current-iid-ptr)
      (.nextIid ev-resolver)
      (duplicate-ptr prev-iid-ptr current-iid-ptr))

    (let [idx (.getIndex leaf-ptr)
          leg-name (.getName (.getLeg (.opReader leaf-ptr) idx))]
      (if (= "evict" leg-name)
        (do
          (.nextIid ev-resolver)
          (duplicate-ptr skip-iid-ptr current-iid-ptr))

        (let [system-from (.getSystemTime leaf-ptr)]
          (when (and (<= (.lower (.systemFrom temporal-bounds)) system-from)
                     (<= system-from (.upper (.systemFrom temporal-bounds))))
            (case leg-name
              "put"
              (.resolveEvent ev-resolver idx
                             (.getLong (.putValidFromReader leaf-ptr) idx)
                             (.getLong (.putValidToReader leaf-ptr) idx)
                             system-from
                             (.rowConsumer leaf-ptr))

              "delete"
              (.resolveEvent ev-resolver idx
                             (.getLong (.deleteValidFromReader leaf-ptr) idx)
                             (.getLong (.deleteValidToReader leaf-ptr) idx)
                             system-from
                             nil))))))))

(defn iid-selector [^ByteBuffer iid-bb]
  (reify IRelationSelector
    (select [_ allocator rel-rdr _params]
      (with-open [arrow-buf (util/->arrow-buf-view allocator iid-bb)]
        (let [iid-ptr (ArrowBufPointer. arrow-buf 0 (.capacity iid-bb))
              ptr (ArrowBufPointer.)
              iid-rdr (.readerForName rel-rdr "xt$iid")
              value-count (.valueCount iid-rdr)]
          (if (pos-int? value-count)
            ;; lower-bound
            (loop [left 0 right (dec value-count)]
              (if (= left right)
                (if (= iid-ptr (.getPointer iid-rdr left ptr))
                  ;; upper bound
                  (loop [right left]
                    (if (or (>= right value-count) (not= iid-ptr (.getPointer iid-rdr right ptr)))
                      (.toArray (IntStream/range left right))
                      (recur (inc right))))
                  (int-array 0))
                (let [mid (quot (+ left right) 2)]
                  (if (<= (.compareTo iid-ptr (.getPointer iid-rdr mid ptr)) 0)
                    (recur left mid)
                    (recur (inc mid) right)))))
            (int-array 0)))))))

(defrecord VSRCache [^IBufferPool buffer-pool, ^BufferAllocator allocator, ^Map cache]
  Closeable
  (close [_] (util/close cache)))

(defn ->vsr-cache [buffer-pool allocator]
  (->VSRCache buffer-pool allocator (HashMap.)))

(defn cache-vsr [{:keys [^Map cache, buffer-pool, allocator]} trie-leaf-file]
  (.computeIfAbsent cache trie-leaf-file
                    (util/->jfn
                      (fn [trie-leaf-file]
                        (bp/open-vsr buffer-pool trie-leaf-file allocator)))))

(defn merge-task-leaf-reader ^IVectorReader [buffer-pool vsr-cache [leaf-tag leaf-arg]]
  (case leaf-tag
    :arrow
    (let [{:keys [page-idx trie-leaf-file]} leaf-arg]
      (util/with-open [rb (bp/open-record-batch buffer-pool trie-leaf-file page-idx)]
        (let [vsr (cache-vsr vsr-cache trie-leaf-file)
              loader (VectorLoader. vsr)]
          (.load loader rb)
          (vr/<-root vsr))))

    :live (let [{:keys [^RelationReader rel-rdr, ^LiveHashTrie$Leaf node]} leaf-arg]
            (.select ^RelationReader rel-rdr (.data ^LiveHashTrie$Leaf node)))))

(deftype TrieCursor [^BufferAllocator allocator, ^Iterator merge-tasks
                     col-names, ^Map col-preds, temporal-bounds
                     params, ^IPersistentMap picker-state
                     vsr-cache, buffer-pool]
  ICursor
  (tryAdvance [_ c]
    (if (.hasNext merge-tasks)
      (let [{:keys [leaves path]} (.next merge-tasks)
            is-valid-ptr (ArrowBufPointer.)]
        (with-open [out-rel (vw/->rel-writer allocator)]
          (let [^IRelationSelector iid-pred (get col-preds "xt$iid")
                merge-q (trie/->merge-queue)]

            (doseq [leaf leaves
                    :when leaf
                    :let [^RelationReader leaf-rdr (merge-task-leaf-reader buffer-pool vsr-cache leaf)
                          ^RelationReader leaf-rdr (cond-> leaf-rdr
                                                     iid-pred (.select (.select iid-pred allocator leaf-rdr params)))
                          leaf-ptr (ScanLeafPointer. leaf-rdr
                                                     (-> (copy-row-consumer out-rel leaf-rdr col-names)
                                                         (wrap-temporal-bounds temporal-bounds)))]]
              (when (.isValid leaf-ptr is-valid-ptr path)
                (.add merge-q leaf-ptr)))

            (loop []
              (when-let [^ScanLeafPointer leaf-ptr (.poll merge-q)]
                (pick-leaf-row! leaf-ptr temporal-bounds picker-state)
                (.nextIndex leaf-ptr)
                (when (.isValid leaf-ptr is-valid-ptr path)
                  (.add merge-q leaf-ptr))
                (recur)))

            (.accept c (-> (vw/rel-wtr->rdr out-rel)
                           (vr/with-absent-cols allocator col-names)

                           (as-> rel (reduce (fn [^RelationReader rel, ^IRelationSelector col-pred]
                                               (.select rel (.select col-pred allocator rel params)))
                                             rel
                                             (vals (dissoc col-preds "xt$iid"))))))))
        true)

      false))

  (close [_]
    (util/close vsr-cache)))

(defn- eid-select->eid [eid-select]
  (if (= 'xt/id (second eid-select))
    (nth eid-select 2)
    (second eid-select)))

(defn selects->iid-byte-buffer ^ByteBuffer [selects ^RelationReader params-rel]
  (when-let [eid-select (or (get selects "xt/id") (get selects "xt$id"))]
    (when (= '= (first eid-select))
      (let [eid (eid-select->eid eid-select)]
        (cond
          (s/valid? ::lp/value eid)
          (trie/->iid eid)

          (s/valid? ::lp/param eid)
          (let [eid-rdr (.readerForName params-rel (name eid))]
            (when (= 1 (.valueCount eid-rdr))
              (trie/->iid (.getObject eid-rdr 0)))))))))

(defn filter-pushdown-bloom-page-idx-pred ^IntPredicate [^IMetadataManager metadata-manager ^String col-name
                                                         table-trie ^RelationReader trie-rdr]
  (when-let [^MutableRoaringBitmap pushdown-bloom (get *column->pushdown-bloom* (symbol col-name))]
    (let [^ITableMetadata table-metadata (.tableMetadata metadata-manager trie-rdr (:trie-file table-trie))
          metadata-rdr (.metadataReader table-metadata)
          bloom-rdr (-> (.structKeyReader metadata-rdr "columns")
                        (.listElementReader)
                        (.structKeyReader "bloom"))]
      (reify IntPredicate
        (test [_ page-idx]
          (boolean
           (when-let [bloom-vec-idx (.rowIndex table-metadata col-name page-idx)]
             (and (not (nil? (.getObject bloom-rdr bloom-vec-idx)))
                  (MutableRoaringBitmap/intersects pushdown-bloom
                                                   (bloom/bloom->bitmap bloom-rdr bloom-vec-idx))))))))))

(defn- ->scan-trie [^IMetadataManager metadata-mgr, ^IMetadataPredicate metadata-pred col-names
                    {:keys [trie-file] :as table-trie}
                    {:keys [trie-rdr] :as arrow-trie}]
  (let [^ITableMetadata table-metadata (.tableMetadata metadata-mgr trie-rdr trie-file)]
    {:table-trie table-trie
     :arrow-trie arrow-trie
     :table-metadata table-metadata
     :page-idx-pred (reduce (fn [^IntPredicate page-idx-pred col-name]
                              (if-let [bloom-page-idx-pred (filter-pushdown-bloom-page-idx-pred metadata-mgr col-name table-trie trie-rdr)]
                                (.and page-idx-pred bloom-page-idx-pred)
                                page-idx-pred))
                            (.build metadata-pred table-metadata)
                            col-names)}))

(defn- ->path-pred [^ArrowBuf iid-arrow-buf]
  (if iid-arrow-buf
    (let [iid-ptr (ArrowBufPointer. iid-arrow-buf 0 (.capacity iid-arrow-buf))]
      #(zero? (HashTrie/compareToPath iid-ptr %)))
    (constantly true)))

(defn- ->merge-tasks
  "scan-tries :: [{arrow-trie table-trie table-metadata page-idx-pred}]"
  [scan-tries, ^ILiveTableWatermark live-table-wm, path-pred]

  (letfn [(merge-tasks* [path [mn-tag mn-arg]]
            (when (path-pred path)
              (case mn-tag
                :branch (into [] cat mn-arg)
                :leaf (let [trie-nodes mn-arg
                            ^MutableRoaringBitmap cumulative-iid-bitmap (MutableRoaringBitmap.)
                            trie-nodes-it (.iterator ^Iterable trie-nodes)
                            scan-tries-it (.iterator ^Iterable scan-tries)]
                        (loop [node-taken? false, leaves []]
                          (if (.hasNext trie-nodes-it)
                            (let [{:keys [^IntPredicate page-idx-pred ^ITableMetadata table-metadata table-trie]}
                                  (when (.hasNext scan-tries-it)
                                    (.next scan-tries-it))]

                              (if-let [trie-node (.next trie-nodes-it)]
                                (condp = (class trie-node)
                                  ArrowHashTrie$Leaf
                                  (let [page-idx (.getPageIndex ^ArrowHashTrie$Leaf trie-node)
                                        take-node? (.test page-idx-pred page-idx)
                                        {:keys [table-name, trie-key]} table-trie]
                                    (when take-node?
                                      (.or cumulative-iid-bitmap (.iidBloomBitmap table-metadata page-idx)))

                                    (recur (or node-taken? take-node?)
                                           (conj leaves (when (or take-node?
                                                                  (when node-taken?
                                                                    (when-let [iid-bitmap (.iidBloomBitmap table-metadata page-idx)]
                                                                      (MutableRoaringBitmap/intersects cumulative-iid-bitmap iid-bitmap))))
                                                          [:arrow {:page-idx page-idx
                                                                   :trie-leaf-file (trie/->table-leaf-obj-key table-name trie-key)}]))))

                                  LiveHashTrie$Leaf
                                  (recur true (conj leaves [:live {:node trie-node, :rel-rdr (.liveRelation live-table-wm)}])))

                                (recur node-taken? (conj leaves nil))))

                            (when node-taken?
                              [{:path path
                                :leaves leaves}])))))))]

    (trie/postwalk-merge-tries (cond-> (mapv (comp :trie :arrow-trie) scan-tries)
                                 live-table-wm (conj (.compactLogs (.liveTrie live-table-wm))))
                               merge-tasks*)))

(defmethod ig/prep-key ::scan-emitter [_ opts]
  (merge opts
         {:metadata-mgr (ig/ref ::meta/metadata-manager)
          :buffer-pool (ig/ref ::bp/buffer-pool)
          :object-store (ig/ref :xtdb/object-store)}))

(defmethod ig/init-key ::scan-emitter [_ {:keys [^ObjectStore object-store ^IMetadataManager metadata-mgr, ^IBufferPool buffer-pool]}]
  (reify IScanEmitter
    (tableColNames [_ wm table-name]
      (let [normalized-table (util/str->normal-form-str table-name)]
        (into #{} cat [(keys (.columnTypes metadata-mgr normalized-table))
                       (some-> (.liveIndex wm)
                               (.liveTable normalized-table)
                               (.columnTypes)
                               keys)])))

    (allTableColNames [_ wm]
      (merge-with set/union
                  (update-vals (.allColumnTypes metadata-mgr)
                               (comp set keys))
                  (update-vals (some-> (.liveIndex wm)
                                       (.allColumnTypes))
                               (comp set keys))))

    (scanColTypes [_ wm scan-cols]
      (letfn [(->col-type [[table col-name]]
                (let [normalized-table (util/str->normal-form-str (str table))
                      normalized-col-name (util/str->normal-form-str (str col-name))]
                  (if (types/temporal-column? (util/str->normal-form-str (str col-name)))
                    [:timestamp-tz :micro "UTC"]
                    (types/merge-col-types (.columnType metadata-mgr normalized-table normalized-col-name)
                                           (some-> (.liveIndex wm)
                                                   (.liveTable normalized-table)
                                                   (.columnTypes)
                                                   (get normalized-col-name))))))]
        (->> scan-cols
             (into {} (map (juxt identity ->col-type))))))

    (emitScan [_ {:keys [columns], {:keys [table] :as scan-opts} :scan-opts} scan-col-types param-types]
      (let [col-names (->> columns
                           (into #{} (map (fn [[col-type arg]]
                                            (case col-type
                                              :column arg
                                              :select (key (first arg)))))))

            col-types (->> col-names
                           (into {} (map (juxt identity
                                               (fn [col-name]
                                                 (get scan-col-types [table col-name]))))))

            col-names (into #{} (map str) col-names)

            normalized-table-name (util/str->normal-form-str (str table))

            selects (->> (for [[tag arg] columns
                               :when (= tag :select)
                               :let [[col-name pred] (first arg)]]
                           (MapEntry/create (str col-name) pred))
                         (into {}))

            col-preds (->> (for [[col-name select-form] selects]
                             ;; for temporal preds, we may not need to re-apply these if they can be represented as a temporal range.
                             (MapEntry/create col-name
                                              (expr/->expression-relation-selector select-form {:col-types col-types, :param-types param-types})))
                           (into {}))

            metadata-args (vec (for [[col-name select] selects
                                     :when (not (types/temporal-column? (util/str->normal-form-str col-name)))]
                                 select))

            row-count (->> (for [{:keys [tables]} (vals (.chunksMetadata metadata-mgr))
                                 :let [{:keys [row-count]} (get tables normalized-table-name)]
                                 :when row-count]
                             row-count)
                           (reduce +))]

        {:col-types col-types
         :stats {:row-count row-count}
         :->cursor (fn [{:keys [allocator, ^IWatermark watermark, basis, params default-all-valid-time?]}]
                     (let [iid-bb (selects->iid-byte-buffer selects params)
                           col-preds (cond-> col-preds
                                       iid-bb (assoc "xt$iid" (iid-selector iid-bb)))
                           metadata-pred (expr.meta/->metadata-selector (cons 'and metadata-args) col-types params)
                           scan-opts (-> scan-opts
                                         (update :for-valid-time
                                                 (fn [fvt]
                                                   (or fvt (if default-all-valid-time? [:all-time] [:at [:now :now]])))))
                           ^ILiveTableWatermark live-table-wm (some-> (.liveIndex watermark) (.liveTable normalized-table-name))
                           table-tries (->> (trie/list-table-trie-files object-store normalized-table-name)
                                            (trie/current-table-tries))]

                       (util/with-open [iid-arrow-buf (when iid-bb (util/->arrow-buf-view allocator iid-bb))
                                        arrow-tries (LinkedList.)]
                         (let [merge-tasks (or (->merge-tasks (map (fn [table-trie]
                                                                     (let [arrow-trie (trie/open-arrow-trie-file buffer-pool table-trie)]
                                                                       (.add arrow-tries arrow-trie)
                                                                       (->scan-trie metadata-mgr metadata-pred col-names table-trie arrow-trie)))
                                                                   table-tries)
                                                              live-table-wm
                                                              (->path-pred iid-arrow-buf))
                                               [])]

                           ;; The consumers for different leafs need to share some state so the logic of how to advance
                           ;; is correct. For example if the `skip-iid-ptr` gets set in one leaf consumer it should also affect
                           ;; the skipping in another leaf consumer.

                           (->TrieCursor allocator (.iterator ^Iterable merge-tasks)
                                         col-names col-preds
                                         (->temporal-bounds params basis scan-opts)
                                         params
                                         {:ev-resolver (event-resolver)
                                          :skip-iid-ptr (ArrowBufPointer.)
                                          :prev-iid-ptr (ArrowBufPointer.)
                                          :current-iid-ptr (ArrowBufPointer.)}
                                         (->vsr-cache buffer-pool allocator)
                                         buffer-pool)))))}))))

(defmethod lp/emit-expr :scan [scan-expr {:keys [^IScanEmitter scan-emitter scan-col-types, param-types]}]
  (.emitScan scan-emitter scan-expr scan-col-types param-types))
