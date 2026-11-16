package com.guny90.reader.dict

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * Random access into a dictzip (.dict.dz) file: a gzip file whose "RA" header field lists
 * independently compressed chunks. Only the chunks covering a definition are inflated,
 * so lookups stay fast and the file never has to be unpacked to disk.
 */
class DictZip private constructor(
    private val raf: RandomAccessFile,
    private val chunkLength: Int,
    private val chunkStarts: LongArray, // file offset of each chunk, plus one past the last
) : DictData {

    private var cachedIndex = -1
    private var cachedChunk = ByteArray(0)

    override fun read(offset: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            val position = offset + done
            val chunk = chunk((position / chunkLength).toInt())
            val inChunk = (position % chunkLength).toInt()
            val n = minOf(size - done, chunk.size - inChunk)
            if (n <= 0) throw IllegalStateException("Definition outside of the .dict.dz data")
            System.arraycopy(chunk, inChunk, out, done, n)
            done += n
        }
        return out
    }

    private fun chunk(index: Int): ByteArray {
        if (index == cachedIndex) return cachedChunk
        if (index < 0 || index >= chunkStarts.size - 1) throw IllegalStateException("Bad chunk $index")
        val compressed = ByteArray((chunkStarts[index + 1] - chunkStarts[index]).toInt() + 1) // +1 dummy byte for nowrap
        raf.seek(chunkStarts[index])
        raf.readFully(compressed, 0, compressed.size - 1)

        val inflater = Inflater(true)
        val out = ByteArray(chunkLength)
        var total = 0
        try {
            inflater.setInput(compressed)
            while (total < chunkLength) {
                val n = inflater.inflate(out, total, chunkLength - total)
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                total += n
            }
        } finally {
            inflater.end()
        }
        cachedIndex = index
        cachedChunk = if (total == chunkLength) out else out.copyOf(total)
        return cachedChunk
    }

    override fun close() = raf.close()

    companion object {
        private const val FHCRC = 2
        private const val FEXTRA = 4
        private const val FNAME = 8
        private const val FCOMMENT = 16

        fun isDictZip(file: File): Boolean = RandomAccessFile(file, "r").use { readHeader(it) != null }

        fun open(file: File): DictZip {
            val raf = RandomAccessFile(file, "r")
            val header = readHeader(raf)
            if (header == null) {
                raf.close()
                throw IllegalArgumentException("${file.name} is a plain gzip file, not dictzip")
            }
            return DictZip(raf, header.first, header.second)
        }

        /** Returns (chunk length, chunk start offsets) or null if this isn't a dictzip file. */
        private fun readHeader(raf: RandomAccessFile): Pair<Int, LongArray>? {
            if (raf.length() < 12) return null
            raf.seek(0)
            val head = ByteArray(10)
            raf.readFully(head)
            if (head[0] != 0x1f.toByte() || head[1] != 0x8b.toByte() || head[2] != 8.toByte()) return null
            val flags = head[3].toInt()
            if (flags and FEXTRA == 0) return null

            val xlen = le16(raf)
            val extra = ByteArray(xlen)
            raf.readFully(extra)
            var chunkLength = 0
            var sizes: IntArray? = null
            var i = 0
            while (i + 4 <= xlen) {
                val len = le16(extra, i + 2)
                if (extra[i] == 'R'.code.toByte() && extra[i + 1] == 'A'.code.toByte() && i + 10 <= xlen) {
                    chunkLength = le16(extra, i + 6)
                    val count = le16(extra, i + 8)
                    sizes = IntArray(count) { le16(extra, i + 10 + 2 * it) }
                }
                i += 4 + len
            }
            if (sizes == null || chunkLength == 0) return null

            if (flags and FNAME != 0) skipZeroTerminated(raf)
            if (flags and FCOMMENT != 0) skipZeroTerminated(raf)
            if (flags and FHCRC != 0) raf.skipBytes(2)

            val starts = LongArray(sizes.size + 1)
            starts[0] = raf.filePointer
            for (k in sizes.indices) starts[k + 1] = starts[k] + sizes[k]
            return chunkLength to starts
        }

        private fun le16(raf: RandomAccessFile): Int {
            val a = raf.read()
            val b = raf.read()
            return a or (b shl 8)
        }

        private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

        private fun skipZeroTerminated(raf: RandomAccessFile) {
            while (true) {
                val c = raf.read()
                if (c <= 0) return
            }
        }
    }
}
