package com.guny90.reader.dict

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StarDictTest {
    private lateinit var tmp: File

    @Before fun setUp() { tmp = Files.createTempDirectory("stardict").toFile() }
    @After fun tearDown() { tmp.deleteRecursively() }

    private val words = mapOf(
        "apple" to "a round fruit",
        "Apple" to "a computer company",
        "run" to "to move fast",
        "runner" to "one who runs",
        "żółw" to "turtle",
        "kot" to "cat",
        "zebra" to "striped animal",
    )

    // ------------------------------------------------------------ building test dictionaries

    /** StarDict order: ASCII case-insensitive, then byte order. */
    private val stardictOrder = Comparator<String> { a, b ->
        val x = a.toByteArray(); val y = b.toByteArray()
        fun lower(c: Int) = if (c in 65..90) c + 32 else c
        for (i in 0 until minOf(x.size, y.size)) {
            val d = lower(x[i].toInt() and 0xff) - lower(y[i].toInt() and 0xff)
            if (d != 0) return@Comparator d
        }
        val d = x.size - y.size
        if (d != 0) d else {
            for (i in x.indices) { val e = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff); if (e != 0) return@Comparator e }
            0
        }
    }

    private fun build(
        dir: File,
        base: String = "test",
        offset64: Boolean = false,
        dictZip: Boolean = false,
        synonyms: Map<String, String> = emptyMap(),
        sameTypes: String? = "m",
    ): File {
        dir.mkdirs()
        val sorted = words.keys.sortedWith(stardictOrder)
        val dict = ByteArrayOutputStream()
        val idx = DataOutputStream(File(dir, "$base.idx").outputStream())
        for (w in sorted) {
            val body = if (sameTypes != null) words[w]!!.toByteArray()
            else byteArrayOf('m'.code.toByte()) + words[w]!!.toByteArray() + 0
            idx.write(w.toByteArray()); idx.write(0)
            if (offset64) idx.writeLong(dict.size().toLong()) else idx.writeInt(dict.size())
            idx.writeInt(body.size)
            dict.write(body)
        }
        idx.close()
        val ifo = buildString {
            append("StarDict's dict ifo file\nversion=3.0.0\nbookname=Test Dict\n")
            append("wordcount=${sorted.size}\nidxfilesize=${File(dir, "$base.idx").length()}\n")
            if (offset64) append("idxoffsetbits=64\n")
            if (sameTypes != null) append("sametypesequence=$sameTypes\n")
        }
        File(dir, "$base.ifo").writeText(ifo)
        if (dictZip) writeDictZip(dict.toByteArray(), File(dir, "$base.dict.dz"), chunk = 16)
        else File(dir, "$base.dict").writeBytes(dict.toByteArray())
        if (synonyms.isNotEmpty()) {
            val syn = DataOutputStream(File(dir, "$base.syn").outputStream())
            for (s in synonyms.keys.sortedWith(stardictOrder)) {
                syn.write(s.toByteArray()); syn.write(0); syn.writeInt(sorted.indexOf(synonyms[s]))
            }
            syn.close()
        }
        return dir
    }

    /** Writes data the way dictzip does: gzip with an RA field and full-flushed chunks. */
    private fun writeDictZip(data: ByteArray, file: File, chunk: Int) {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val body = ByteArrayOutputStream()
        val sizes = ArrayList<Int>()
        val buf = ByteArray(1024)
        var pos = 0
        while (pos < data.size) {
            val n = minOf(chunk, data.size - pos)
            deflater.setInput(data, pos, n)
            pos += n
            val last = pos >= data.size
            if (last) deflater.finish()
            val before = body.size()
            while (true) {
                val m = deflater.deflate(buf, 0, buf.size, if (last) Deflater.NO_FLUSH else Deflater.FULL_FLUSH)
                body.write(buf, 0, m)
                if (last) { if (deflater.finished()) break } else if (m < buf.size) break
            }
            sizes.add(body.size() - before)
        }
        val out = DataOutputStream(file.outputStream())
        fun le16(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        fun le32(v: Long) { le16((v and 0xffff).toInt()); le16((v shr 16 and 0xffff).toInt()) }
        out.write(byteArrayOf(0x1f, 0x8b.toByte(), 8, 4, 0, 0, 0, 0, 0, 3))
        val raLen = 6 + 2 * sizes.size
        le16(4 + raLen)
        out.write('R'.code); out.write('A'.code); le16(raLen)
        le16(1); le16(chunk); le16(sizes.size); sizes.forEach { le16(it) }
        out.write(body.toByteArray())
        val crc = CRC32().apply { update(data) }
        le32(crc.value); le32(data.size.toLong())
        out.close()
    }

    private fun text(d: StarDict, word: String) =
        d.find(word).map { d.definition(it) }.map { it.word + ": " + it.parts.joinToString { p -> p.second } }

    // ------------------------------------------------------------ tests

    @Test fun findsWordsCaseInsensitivelyWithExactCaseFirst() {
        StarDict.open(build(File(tmp, "d"))).use { d ->
            assertEquals(7, d.wordCount)
            assertEquals(listOf("apple: a round fruit", "Apple: a computer company"), text(d, "apple"))
            assertEquals("Apple: a computer company", text(d, "Apple").first())
            assertEquals(listOf("kot: cat"), text(d, "Kot"))
            assertEquals(listOf("żółw: turtle"), text(d, "Żółw")) // non-ASCII capital
            assertTrue(d.find("missing").isEmpty())
        }
    }

    @Test fun supports64BitOffsetsAndDictZip() {
        StarDict.open(build(File(tmp, "d"), offset64 = true, dictZip = true)).use { d ->
            for ((w, def) in words) {
                val hit = d.find(w).map { d.definition(it) }.first { it.word == w }
                assertEquals(def, hit.parts.single().second)
            }
        }
    }

    @Test fun parsesTypedEntriesWithoutSameTypeSequence() {
        StarDict.open(build(File(tmp, "d"), sameTypes = null)).use { d ->
            val def = d.definition(d.find("zebra").single())
            assertEquals(listOf('m' to "striped animal"), def.parts)
        }
    }

    @Test fun followsSynonyms() {
        StarDict.open(build(File(tmp, "d"), synonyms = mapOf("ran" to "run", "koty" to "kot"))).use { d ->
            assertEquals(listOf("run: to move fast"), text(d, "ran"))
            assertEquals(listOf("kot: cat"), text(d, "koty"))
        }
    }

    @Test fun suggestsShorterFormsAndNeighbours() {
        StarDict.open(build(File(tmp, "d"))).use { d ->
            val s = d.nearest("running")
            assertEquals("run", s.first())
            assertTrue(s.contains("runner"))
            assertEquals("kot", d.nearest("kotami").first())
        }
    }

    @Test fun suggestsBaseFormsOfNearbyInflectedForms() {
        val syn = mapOf("poszła" to "run", "poszli" to "run", "koty" to "kot")
        StarDict.open(build(File(tmp, "d"), synonyms = syn)).use { d ->
            assertTrue(d.find("poszły").isEmpty())
            assertEquals("run", d.nearest("poszły").first())
        }
    }

    @Test fun installsLooseFilesZipAndTarGz() {
        val src = build(File(tmp, "src"), base = "my dict", dictZip = true)
        val dicts = Dictionaries(File(tmp, "installed"))

        // loose files
        val loose = src.listFiles()!!.map { it.name to it }
        assertEquals(listOf("Test Dict"), dicts.install(loose))

        // zip with a folder inside
        val zip = File(tmp, "d.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (f in src.listFiles()!!) { z.putNextEntry(ZipEntry("folder/${f.name}")); z.write(f.readBytes()) }
        }
        assertEquals(listOf("Test Dict"), dicts.install(listOf("d.zip" to zip)))

        // tar.gz
        val tgz = File(tmp, "d.tar.gz")
        GZIPOutputStream(tgz.outputStream()).use { out ->
            for (f in src.listFiles()!!) {
                val header = ByteArray(512)
                "dir/${f.name}".toByteArray().copyInto(header)
                String.format("%011o\u0000", f.length()).toByteArray().copyInto(header, 124)
                header[156] = '0'.code.toByte()
                out.write(header); out.write(f.readBytes())
                out.write(ByteArray(((512 - f.length() % 512) % 512).toInt()))
            }
            out.write(ByteArray(1024))
        }
        assertEquals(listOf("Test Dict"), dicts.install(listOf("d.tar.gz" to tgz)))

        val installed = dicts.list()
        assertEquals(3, installed.size)
        val lookup = dicts.lookup(installed[0].id, "zebra")
        assertEquals("striped animal", lookup.definitions.single().parts.single().second)
        val miss = dicts.lookup(installed[0].id, "runs")
        assertTrue(miss.definitions.isEmpty())
        assertEquals("run", miss.suggestions.first())

        dicts.remove(installed[0].id)
        assertEquals(2, dicts.list().size)
        dicts.closeAll()
    }

    @Test fun rejectsBz2AndIncompleteSets() {
        val dicts = Dictionaries(File(tmp, "installed"))
        val src = build(File(tmp, "src"))
        try {
            dicts.install(listOf("x.tar.bz2" to File(src, "test.ifo"))); fail()
        } catch (e: Dictionaries.UserError) { assertTrue(e.message!!.contains("unpack")) }
        try {
            dicts.install(listOf("test.ifo" to File(src, "test.ifo"))); fail()
        } catch (e: Dictionaries.UserError) { assertTrue(e.message!!.contains(".idx")) }
        assertTrue(dicts.list().isEmpty())
    }
}
