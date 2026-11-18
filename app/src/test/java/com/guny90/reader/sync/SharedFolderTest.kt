package com.guny90.reader.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class SharedFolderTest {

    @Test fun fileNameDropsCharactersFileSystemsReject() {
        assertEquals("War and Peace.epub", SharedFolder.fileName("War and Peace", isPdf = false))
        assertEquals("AC DC Live.pdf", SharedFolder.fileName("AC/DC: Live?", isPdf = true))
        assertEquals("Женщина.epub", SharedFolder.fileName("Женщина", isPdf = false))
        assertEquals("Book.epub", SharedFolder.fileName(" ?? ", isPdf = false))
        assertEquals("Wait for it.epub", SharedFolder.fileName("Wait for it...", isPdf = false))
        assertEquals(125, SharedFolder.fileName("x".repeat(500), isPdf = false).length)
    }

    @Test fun uniqueNameNumbersClashes() {
        assertEquals("A.epub", SharedFolder.uniqueName("A.epub", listOf("B.epub")))
        assertEquals("A (2).epub", SharedFolder.uniqueName("A.epub", listOf("a.EPUB")))
        assertEquals("A (3).epub", SharedFolder.uniqueName("A.epub", listOf("A.epub", "A (2).epub")))
    }

    @Test fun onlyBooksAreListed() {
        assertTrue(SharedFolder.isBookName("Book.epub"))
        assertTrue(SharedFolder.isBookName("Scan.PDF"))
        assertFalse(SharedFolder.isBookName("Book.epub.part"))
        assertFalse(SharedFolder.isBookName(".hidden.epub"))
        assertFalse(SharedFolder.isBookName("notes.txt"))
    }

    @Test fun partialMd5OfAStreamMatchesTheFileVersion() {
        val file = File(Files.createTempDirectory("md5").toFile(), "book.epub")
        file.writeBytes(ByteArray(300_000) { ((it * 7 + 3) % 251).toByte() })
        val viaReader = RandomAccessFile(file, "r").use { raf ->
            KoSync.partialMd5(raf.length()) { offset, buffer -> raf.seek(offset); raf.read(buffer) }
        }
        assertEquals("3e53b613def422b326c240820288f5ef", viaReader)
        file.parentFile!!.deleteRecursively()
    }
}
