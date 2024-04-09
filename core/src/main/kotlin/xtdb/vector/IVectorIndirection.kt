package xtdb.vector

interface IVectorIndirection {
    fun valueCount(): Int

    fun getIndex(idx: Int): Int

    operator fun get(idx: Int): Int = getIndex(idx)

    data class Selection(val idxs: IntArray) : IVectorIndirection {
        override fun valueCount(): Int {
            return idxs.size
        }

        override fun getIndex(idx: Int): Int {
            return idxs[idx]
        }

        override fun toString(): String {
            @Suppress("DEPRECATION")
            return "(Selection {idxs=%s})".formatted(idxs.contentToString())
        }
    }

    data class Slice(val startIdx: Int, val len: Int) : IVectorIndirection {
        override fun valueCount(): Int {
            return len
        }

        override fun getIndex(idx: Int): Int {
            return startIdx + idx
        }
    }
}
