package com.guny90.reader.dict

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.channels.FileChannel

/**
 * Read-only StarDict dictionary (.ifo + .idx + .dict/.dict.dz, optional .syn).
 *
 * Lookups are a binary search over the memory-mapped .idx file. To make that possible
 * without scanning the whole index, a small "<base>.idx.pos" file with the byte position
 * of every entry is built once (on install) and memory-mapped as well.
 *
 * Pure JVM (no Android APIs) so it can be unit-tested. Not thread-safe: use from one thread.
 */
class StarDict private constructor(
    val info: Map<String, String>,
    private val idx: ByteBuffer,
    private val idxPos: IntBuffer,
    private val syn: ByteBuffer?,
    private val synPos: IntBuffer?,
    private val data: DictData,
    fallbackName: String,
) : Closeable {

    /** One definition: its headword and its text parts as (StarDict type char, text). */
    class Definition(val word: String, val parts: List<Pair<Char, String>>)

    val name: String = info["bookname"]?.trim().orEmpty().ifEmpty { fallbackName }
    val wordCount: Int get() = idxPos.limit()

    private val offset64 = info["idxoffsetbits"]?.trim() == "64"
    private val types = info["sametypesequence"]?.trim().orEmpty()

    fun headword(index: Int): String = readWord(idx, idxPos.get(index))

    /**
     * Entry indexes for [word]: case-insensitive matches of the word as written, in lower case
     * and capitalized, plus entries reached through the .syn file. Exact-case matches come first.
     */
    fun find(word: String): List<Int> {
        val clean = word.trim()
        if (clean.isEmpty()) return emptyList()
        val candidates = linkedSetOf(
            clean,
            clean.lowercase(),
            clean.lowercase().replaceFirstChar { it.titlecase() },
        )
        val found = LinkedHashSet<Int>()
        for (candidate in candidates) {
            val key = candidate.toByteArray(Charsets.UTF_8)
            var i = lowerBound(idx, idxPos, key)
            while (i < idxPos.limit() && compareFold(idx, idxPos.get(i), key) == 0) found.add(i++)
            if (syn != null && synPos != null) {
                var j = lowerBound(syn, synPos, key)
                while (j < synPos.limit() && compareFold(syn, synPos.get(j), key) == 0) {
                    val p = synPos.get(j++)
                    val target = syn.getInt(wordEnd(syn, p) + 1)
                    if (target in 0 until idxPos.limit()) found.add(target)
                }
            }
        }
        // Exact-case match first (e.g. "Polish" before "polish" when the reader selected "Polish").
        return found.sortedBy { if (headword(it) == clean) 0 else 1 }
    }

    /**
     * Suggestions when [find] has no result. First, shorter forms of the word that exist as
     * headwords (e.g. "running" -> "run", "kotami" -> "kot"), then the entries that follow the
     * word alphabetically. Works for any language without grammar rules.
     */
    fun nearest(word: String, limit: Int = 12): List<String> {
        val clean = word.trim().lowercase()
        val out = LinkedHashSet<String>()
        if (clean.isEmpty() || wordCount == 0) return emptyList()
        // Alphabetically close inflected forms usually belong to the same word:
        // "poszły" is missing, but "poszła" and "poszli" next to it both point to "pójść".
        // The ones sharing the longest beginning with the selected word come first.
        if (syn != null && synPos != null) {
            val j = lowerBound(syn, synPos, clean.toByteArray(Charsets.UTF_8))
            val minShared = maxOf(3, clean.length / 2)
            (maxOf(0, j - 4) until minOf(synPos.limit(), j + 4))
                .map { k ->
                    val p = synPos.get(k)
                    val end = wordEnd(syn, p)
                    val form = String(bytesAt(syn, p, end - p), Charsets.UTF_8).lowercase()
                    form.commonPrefixWith(clean).length to syn.getInt(end + 1)
                }
                .filter { (shared, target) -> shared >= minShared && target in 0 until wordCount }
                .sortedByDescending { it.first }
                .forEach { (_, target) -> if (out.size < 3) out.add(headword(target)) }
        }
        val minLength = maxOf(3, clean.length / 3) // "running" -> "run", but no 1-2 letter noise
        for (length in clean.length - 1 downTo minLength) {
            val prefix = clean.substring(0, length)
            find(prefix).firstOrNull()?.let { out.add(headword(it)) }
            if (out.size >= 4) break
        }
        var i = lowerBound(idx, idxPos, clean.toByteArray(Charsets.UTF_8))
        if (i > 0) i-- // the entry just before is often the base form
        while (i < wordCount && out.size < limit) out.add(headword(i++))
        return out.toList()
    }

    fun definition(index: Int): Definition {
        val p = idxPos.get(index)
        val end = wordEnd(idx, p)
        val word = String(bytesAt(idx, p, end - p), Charsets.UTF_8)
        val offset: Long
        val size: Int
        if (offset64) {
            offset = idx.getLong(end + 1)
            size = idx.getInt(end + 9)
        } else {
            offset = idx.getInt(end + 1).toLong() and 0xffffffffL
            size = idx.getInt(end + 5)
        }
        return Definition(word, parseParts(data.read(offset, size)))
    }

    /** Splits raw entry data into typed parts (see the StarDict format doc, "sametypesequence"). */
    private fun parseParts(b: ByteArray): List<Pair<Char, String>> {
        val out = ArrayList<Pair<Char, String>>()
        var p = 0
        fun text(type: Char, last: Boolean) {
            val end = if (last) b.size else indexOfZero(b, p)
            if (type.isLowerCase()) out.add(type to String(b, p, end - p, Charsets.UTF_8))
            p = end + 1
        }
        fun binary(last: Boolean) {
            if (last) { p = b.size; return }
            if (p + 4 > b.size) { p = b.size; return }
            val len = ByteBuffer.wrap(b, p, 4).int
            p += 4 + len
        }
        if (types.isNotEmpty()) {
            for ((k, type) in types.withIndex()) {
                if (p >= b.size) break
                val last = k == types.lastIndex
                if (type.isUpperCase()) binary(last) else text(type, last)
            }
        } else {
            while (p < b.size) {
                val type = b[p++].toInt().toChar()
                if (type.isUpperCase()) binary(false) else text(type, false)
            }
        }
        return out
    }

    override fun close() = data.close()

    companion object {
        const val IFO_MAGIC = "StarDict's dict ifo file"

        fun readIfo(file: File): Map<String, String> {
            val lines = file.readLines(Charsets.UTF_8)
            if (lines.isEmpty() || !lines[0].trim().trimStart('﻿').startsWith(IFO_MAGIC)) {
                throw IllegalArgumentException("${file.name} is not a StarDict .ifo file")
            }
            return lines.drop(1).mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq > 0) line.substring(0, eq).trim() to line.substring(eq + 1).trim() else null
            }.toMap()
        }

        /** Opens the dictionary in [dir] (one .ifo with matching .idx and .dict/.dict.dz). */
        fun open(dir: File): StarDict {
            val ifo = dir.listFiles()?.firstOrNull { it.name.endsWith(".ifo") }
                ?: throw IllegalArgumentException("No .ifo file")
            val base = ifo.name.removeSuffix(".ifo")
            val info = readIfo(ifo)
            val idxFile = File(dir, "$base.idx")
            if (!idxFile.exists()) throw IllegalArgumentException("Missing $base.idx")
            val tail = if (info["idxoffsetbits"]?.trim() == "64") 12 else 8

            val idx = map(idxFile)
            val idxPos = positions(idx, tail, File(dir, "$base.idx.pos"))

            val synFile = File(dir, "$base.syn")
            val syn = if (synFile.exists()) map(synFile) else null
            val synPos = syn?.let { positions(it, 4, File(dir, "$base.syn.pos")) }

            val dz = File(dir, "$base.dict.dz")
            val plain = File(dir, "$base.dict")
            val data = when {
                dz.exists() -> DictZip.open(dz)
                plain.exists() -> PlainDict(plain)
                else -> throw IllegalArgumentException("Missing $base.dict or $base.dict.dz")
            }
            return StarDict(info, idx, idxPos, syn, synPos, data, base)
        }

        private fun map(file: File): ByteBuffer =
            RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

        /** Loads (building on first use) the entry start positions of an .idx/.syn file. */
        private fun positions(buf: ByteBuffer, tail: Int, posFile: File): IntBuffer {
            if (!posFile.exists()) {
                val tmp = File(posFile.path + ".tmp")
                DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use { out ->
                    val n = buf.limit()
                    var p = 0
                    while (p < n) {
                        out.writeInt(p)
                        while (p < n && buf.get(p) != 0.toByte()) p++
                        p += 1 + tail
                    }
                }
                if (!tmp.renameTo(posFile)) throw IllegalStateException("Cannot write ${posFile.name}")
            }
            return map(posFile).asIntBuffer()
        }

        // StarDict sorts with g_ascii_strcasecmp, then strcmp to break ties.
        private fun lowerBound(buf: ByteBuffer, pos: IntBuffer, key: ByteArray): Int {
            var lo = 0
            var hi = pos.limit()
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (compareFold(buf, pos.get(mid), key) < 0) lo = mid + 1 else hi = mid
            }
            return lo
        }

        internal fun compareFold(buf: ByteBuffer, start: Int, key: ByteArray): Int {
            var i = 0
            while (true) {
                val a = if (start + i < buf.limit()) buf.get(start + i).toInt() and 0xff else 0
                val b = if (i < key.size) key[i].toInt() and 0xff else 0
                val d = asciiLower(a) - asciiLower(b)
                if (d != 0 || a == 0) return d
                i++
            }
        }

        private fun asciiLower(c: Int) = if (c in 'A'.code..'Z'.code) c + 32 else c

        private fun wordEnd(buf: ByteBuffer, start: Int): Int {
            var p = start
            while (p < buf.limit() && buf.get(p) != 0.toByte()) p++
            return p
        }

        private fun readWord(buf: ByteBuffer, start: Int): String {
            val end = wordEnd(buf, start)
            return String(bytesAt(buf, start, end - start), Charsets.UTF_8)
        }

        private fun bytesAt(buf: ByteBuffer, start: Int, length: Int): ByteArray {
            val out = ByteArray(length)
            for (i in 0 until length) out[i] = buf.get(start + i)
            return out
        }

        private fun indexOfZero(b: ByteArray, from: Int): Int {
            var i = from
            while (i < b.size && b[i] != 0.toByte()) i++
            return i
        }
    }
}

/** Random access to the definitions file. */
interface DictData : Closeable {
    fun read(offset: Long, size: Int): ByteArray
}

class PlainDict(file: File) : DictData {
    private val raf = RandomAccessFile(file, "r")

    override fun read(offset: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    override fun close() = raf.close()
}
