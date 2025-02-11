(ns xtdb.aws.s3
  (:require [xtdb.buffer-pool :as bp]
            [xtdb.object-store :as os]
            [xtdb.util :as util])
  (:import [java.io Closeable]
           [java.nio ByteBuffer]
           [java.nio.file Path]
           [java.util ArrayList List]
           [java.util.concurrent CompletableFuture]
           [java.util.function Function]
           [software.amazon.awssdk.core ResponseBytes]
           [software.amazon.awssdk.core.async AsyncRequestBody AsyncResponseTransformer]
           [software.amazon.awssdk.services.s3 S3AsyncClient]
           [software.amazon.awssdk.services.s3.model AbortMultipartUploadRequest CompleteMultipartUploadRequest CompletedMultipartUpload CompletedPart CreateMultipartUploadRequest CreateMultipartUploadResponse DeleteObjectRequest GetObjectRequest HeadObjectRequest ListObjectsV2Request ListObjectsV2Response NoSuchKeyException PutObjectRequest S3Object UploadPartRequest UploadPartResponse]
           xtdb.api.storage.ObjectStore
           [xtdb.aws S3 S3$Factory]
           [xtdb.aws.s3 S3Configurator]
           [xtdb.multipart IMultipartUpload SupportsMultipart]))

(defn- get-obj-req
  ^GetObjectRequest [{:keys [^S3Configurator configurator bucket ^Path prefix]} ^Path k]
  (let [prefixed-key (util/prefix-key prefix k)]
    (-> (GetObjectRequest/builder)
        (.bucket bucket)
        (.key (str prefixed-key))
        (->> (.configureGet configurator))
        ^GetObjectRequest (.build))))

(defn- get-obj-range-req
  ^GetObjectRequest [{:keys [^S3Configurator configurator bucket ^Path prefix]} ^Path k ^Long start ^long len]
  (let [prefixed-key (util/prefix-key prefix k)
        end-byte (+ start (dec len))]
    (-> (GetObjectRequest/builder)
        (.bucket bucket)
        (.key (str prefixed-key))
        (.range (format "bytes=%d-%d" start end-byte))
        (->> (.configureGet configurator))
        ^GetObjectRequest (.build))))

(defn list-objects [{:keys [^S3AsyncClient client bucket ^Path prefix] :as s3-opts} continuation-token]
  (vec
   (let [^ListObjectsV2Request
         req (-> (ListObjectsV2Request/builder)
                 (.bucket bucket)
                 (.prefix (some-> prefix str))
                 (cond-> continuation-token (.continuationToken continuation-token))
                 (.build))

         ^ListObjectsV2Response
         resp (.get (.listObjectsV2 ^S3AsyncClient client req))]

     (concat (for [^S3Object object (.contents resp)]
               (cond->> (util/->path (.key object))
                 prefix (.relativize prefix)))
             (when (.isTruncated resp)
               (list-objects s3-opts (.nextContinuationToken resp)))))))

(defn- with-exception-handler [^CompletableFuture fut ^Path k]
  (.exceptionally fut (reify Function
                        (apply [_ e]
                          (try
                            (throw (.getCause ^Exception e))
                            (catch NoSuchKeyException _
                              (throw (os/obj-missing-exception k))))))))

(defn single-object-upload
  [{:keys [^S3AsyncClient client ^S3Configurator configurator bucket ^Path prefix]} ^Path k ^ByteBuffer buf]
  (let [prefixed-key (util/prefix-key prefix k)]
    (.putObject client
                (-> (PutObjectRequest/builder)
                    (.bucket bucket)
                    (.key (str prefixed-key))
                    (->> (.configurePut configurator))
                    ^PutObjectRequest (.build))
                (AsyncRequestBody/fromByteBuffer buf))))

(defrecord MultipartUpload [^S3AsyncClient client bucket ^Path prefix ^Path k upload-id !part-number ^List !completed-parts]
  IMultipartUpload 
  (uploadPart [_  buf]
    (let [prefixed-key (util/prefix-key prefix k)
          content-length (long (.limit buf))
          part-number (int (swap! !part-number inc))]
      (-> (.uploadPart client
                       (-> (UploadPartRequest/builder)
                           (.bucket bucket)
                           (.key (str prefixed-key))
                           (.uploadId upload-id)
                           (.partNumber part-number)
                           (.contentLength content-length)
                           ^UploadPartRequest (.build))
                       (AsyncRequestBody/fromByteBuffer buf))
          (.thenApply (fn [^UploadPartResponse upload-part-response]
                        (.add !completed-parts (-> (CompletedPart/builder)
                                                   (.partNumber part-number)
                                                   (.eTag (.eTag upload-part-response))
                                                   ^CompletedPart (.build))))))))
  
  (complete [_]
    (let [prefixed-key (util/prefix-key prefix k)
          ^List !sorted-parts (sort-by (fn [^CompletedPart part] (.partNumber part)) !completed-parts)]
      (.completeMultipartUpload client
                                (-> (CompleteMultipartUploadRequest/builder)
                                    (.bucket bucket)
                                    (.key (str prefixed-key))
                                    (.uploadId upload-id)
                                    (.multipartUpload (-> (CompletedMultipartUpload/builder)
                                                          (.parts !sorted-parts)
                                                          ^CompletedMultipartUpload (.build)))
                                    ^CompleteMultipartUploadRequest (.build)))))
  
  (abort [_]
    (let [prefixed-key (util/prefix-key prefix k)]
      (.abortMultipartUpload client
                             (-> (AbortMultipartUploadRequest/builder)
                                 (.bucket bucket)
                                 (.key (str prefixed-key))
                                 (.uploadId upload-id)
                                 ^AbortMultipartUploadRequest (.build))))))

(defrecord S3ObjectStore [^S3Configurator configurator ^S3AsyncClient client bucket ^Path prefix multipart-minimum-part-size]
  ObjectStore
  (getObject [this k]
    (-> (.getObject client (get-obj-req this k) (AsyncResponseTransformer/toBytes))
        (.thenApply (reify Function
                      (apply [_ bs]
                        (.asByteBuffer ^ResponseBytes bs))))
        (with-exception-handler k)))

  (getObject [this k out-path]
    (-> (.getObject client (get-obj-req this k) out-path)
        (.thenApply (reify Function
                      (apply [_ _]
                        out-path)))
        (with-exception-handler k)))

  (getObjectRange [this k start len]
    (os/ensure-shared-range-oob-behaviour start len)
    (try
      (-> (.getObject client ^GetObjectRequest (get-obj-range-req this k start len) (AsyncResponseTransformer/toBytes))
          (.thenApply (reify Function
                        (apply [_ bs]
                          (.asByteBuffer ^ResponseBytes bs))))
          (with-exception-handler k))
      (catch IndexOutOfBoundsException e
        (CompletableFuture/failedFuture e))))

  (putObject [this k buf]
    (let [prefixed-key (util/prefix-key prefix k)]
      (-> (.headObject client
                       (-> (HeadObjectRequest/builder)
                           (.bucket bucket)
                           (.key (str prefixed-key))
                           (->> (.configureHead configurator))
                           ^HeadObjectRequest (.build)))
          (.thenApply (fn [_resp] true))
          (.exceptionally (fn [^Exception e]
                            (let [e (.getCause e)]
                              (if (instance? NoSuchKeyException e)
                                false
                                (throw e)))))
          (.thenCompose (fn [exists?]
                          (if exists?
                            (CompletableFuture/completedFuture nil)
                            (single-object-upload this k buf)))))))

  (listAllObjects [this]
    (list-objects this nil))

  (deleteObject [_ k]
    (let [prefixed-key (util/prefix-key prefix k)]
      (.deleteObject client
                     (-> (DeleteObjectRequest/builder)
                         (.bucket bucket)
                         (.key (str prefixed-key))
                         ^DeleteObjectRequest (.build)))))

  SupportsMultipart
  (startMultipart [_ k]
    (let [prefixed-key (util/prefix-key prefix k)
          initiate-request (-> (CreateMultipartUploadRequest/builder)
                               (.bucket bucket)
                               (.key (str prefixed-key))
                               ^CreateMultipartUploadRequest (.build))]
      (-> (.createMultipartUpload client initiate-request)
          (.thenApply (fn [^CreateMultipartUploadResponse initiate-response]
                        (->MultipartUpload client
                                           bucket
                                           prefix
                                           k
                                           (.uploadId initiate-response)
                                           (atom 0)
                                           (ArrayList.)))))))

  Closeable
  (close [_]
    (.close client)))

(defmethod bp/->object-store-factory ::object-store [_ {:keys [bucket ^S3Configurator configurator prefix]}]
  (cond-> (S3/s3 bucket)
    configurator (.s3Configurator configurator)
    prefix (.prefix (util/->path prefix))))

(def minimum-part-size (* 5 1024 1024))

(defn open-object-store ^ObjectStore [^S3$Factory factory]
  (let [bucket (.getBucket factory)
        configurator (.getS3Configurator factory)
        s3-client (.makeClient configurator)
        prefix (.getPrefix factory)
        prefix-with-version (if prefix (.resolve prefix bp/storage-root) bp/storage-root)]
  
    (->S3ObjectStore configurator
                     s3-client
                     bucket
                     prefix-with-version
                     minimum-part-size)))
