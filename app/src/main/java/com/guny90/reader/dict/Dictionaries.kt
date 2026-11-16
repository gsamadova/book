package com.guny90.reader.dict

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Installed dictionaries, each in its own folder under [root].
 * [list] is cheap (reads small .ifo files); everything else should run on one background thread.
 */
class Dictionaries(private val root: File) {

    class Info(val id: String, val name: String, val words: Int)

    class Lookup(
        val dictName: String,
        val word: String,
        val definitions: List<StarDict.Definition>,
        val suggestions: List<String>,
    )

    /** A problem the user can fix; the message is shown as is. */
    class UserError(message: String) : Exception(message)

    private val opened = HashMap<String, StarDict>()

    fun list(): List<Info> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir ->
                val ifo = dir.listFiles()?.firstOrNull { it.name.endsWith(".ifo") } ?: return@mapNotNull null
                val info = try { StarDict.readIfo(ifo) } catch (e: Exception) { return@mapNotNull null }
                Info(
                    id = dir.name,
                    name = info["bookname"]?.trim().orEmpty().ifEmpty { ifo.name.removeSuffix(".ifo") },
                    words = info["wordcount"]?.trim()?.toIntOrNull() ?: 0,
                )
            }
            .sortedBy { it.name.lowercase() }

    fun lookup(id: String, word: String): Lookup {
        val dict = get(id)
        val hits = dict.find(word)
        return if (hits.isNotEmpty()) {
            Lookup(dict.name, word, hits.take(MAX_DEFINITIONS).map { dict.definition(it) }, emptyList())
        } else {
            Lookup(dict.name, word, emptyList(), dict.nearest(word))
        }
    }

    fun remove(id: String) {
        opened.remove(id)?.close()
        File(root, id).deleteRecursively()
    }

    fun closeAll() {
        opened.values.forEach { it.close() }
        opened.clear()
    }

    private fun get(id: String): StarDict =
        opened.getOrPut(id) { StarDict.open(File(root, id)) }

    /**
     * Installs dictionaries from loose StarDict files and/or archives (.zip, .tar.gz, .tgz, .tar).
     * [files] pairs the original file name with a local copy. Returns the names of added dictionaries.
     */
    fun install(files: List<Pair<String, File>>): List<String> {
        root.mkdirs()
        val staging = File(root, ".staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            for ((name, file) in files) {
                val lower = name.lowercase()
                when {
                    UNSUPPORTED_ARCHIVES.any { lower.endsWith(it) } -> throw UserError(
                        "Please unpack \"$name\" first, then add the files inside it " +
                            "(.ifo, .idx, .dict or .dict.dz)."
                    )
                    lower.endsWith(".zip") -> unzip(file, staging)
                    lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ->
                        GZIPInputStream(FileInputStream(file).buffered()).use { untar(it, staging) }
                    lower.endsWith(".tar") -> FileInputStream(file).buffered().use { untar(it, staging) }
                    else -> file.copyTo(File(staging, baseName(name)), overwrite = true)
                }
            }

            val ifos = staging.listFiles()?.filter { it.name.endsWith(".ifo") }.orEmpty()
            if (ifos.isEmpty()) throw UserError(
                "No .ifo file found.\n\nA StarDict dictionary is a set of files: .ifo, .idx and .dict " +
                    "(or .dict.dz). Select them together, or pick one .zip / .tar.gz archive."
            )
            return ifos.map { installOne(it, staging) }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun installOne(ifo: File, staging: File): String {
        val base = ifo.name.removeSuffix(".ifo")
        fun staged(suffix: String) = File(staging, base + suffix).takeIf { it.exists() }

        val idx = staged(".idx") ?: staged(".idx.gz")
            ?: throw UserError("\"$base\" is missing its .idx file. Add it together with the .ifo file.")
        val dict = staged(".dict.dz") ?: staged(".dict")
            ?: throw UserError("\"$base\" is missing its .dict or .dict.dz file. Add it together with the .ifo file.")

        val id = base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40) + "-" + System.currentTimeMillis()
        val dest = File(root, id)
        dest.mkdirs()
        try {
            move(ifo, File(dest, "$base.ifo"))
            if (idx.name.endsWith(".gz")) gunzip(idx, File(dest, "$base.idx")) else move(idx, File(dest, "$base.idx"))
            when {
                dict.name.endsWith(".dz") && DictZip.isDictZip(dict) -> move(dict, File(dest, "$base.dict.dz"))
                dict.name.endsWith(".dz") -> gunzip(dict, File(dest, "$base.dict")) // plain gzip, no random access
                else -> move(dict, File(dest, "$base.dict"))
            }
            staged(".syn")?.let { move(it, File(dest, "$base.syn")) }
            staged(".syn.gz")?.let { gunzip(it, File(dest, "$base.syn")) }

            // Opening validates the files and builds the lookup helper files once.
            return StarDict.open(dest).use { d ->
                if (d.wordCount > 0) d.definition(0) // reads one entry to check .dict works
                d.name
            }
        } catch (e: Exception) {
            dest.deleteRecursively()
            if (e is UserError) throw e
            throw UserError("\"$base\" is not a valid StarDict dictionary.\n${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val MAX_DEFINITIONS = 10
        private val UNSUPPORTED_ARCHIVES = listOf(".tar.bz2", ".tbz2", ".tbz", ".bz2", ".7z", ".rar", ".tar.xz", ".xz")

        /** Archive paths are flattened to the file name; this also prevents writing outside [dest]. */
        private fun baseName(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

        /** Staging and destination are in the same app folder, so a rename avoids copying big files. */
        private fun move(src: File, dest: File) {
            if (!src.renameTo(dest)) src.copyTo(dest, overwrite = true)
        }

        private fun gunzip(src: File, dest: File) {
            GZIPInputStream(FileInputStream(src).buffered()).use { input ->
                dest.outputStream().use { input.copyTo(it, 64 * 1024) }
            }
        }

        private fun unzip(zip: File, dest: File) {
            ZipInputStream(FileInputStream(zip).buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = baseName(entry.name)
                    if (entry.isDirectory || name.isEmpty() || name.startsWith(".")) continue
                    File(dest, name).outputStream().use { input.copyTo(it, 64 * 1024) }
                }
            }
        }

        /** Minimal tar reader: regular files and GNU long names are enough for dictionaries. */
        internal fun untar(input: InputStream, dest: File) {
            val header = ByteArray(512)
            var longName: String? = null
            while (readBlock(input, header)) {
                if (header.all { it == 0.toByte() }) break
                val size = parseOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val name = longName ?: String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000')
                longName = null
                val padding = (512 - size % 512) % 512
                when (type) {
                    'L' -> {
                        val bytes = ByteArray(size.toInt())
                        readFully(input, bytes)
                        longName = String(bytes, Charsets.UTF_8).substringBefore('\u0000')
                    }
                    '0', '\u0000' -> {
                        val file = baseName(name)
                        if (file.isEmpty() || file.startsWith(".")) skip(input, size)
                        else File(dest, file).outputStream().use { copy(input, it, size) }
                    }
                    else -> skip(input, size)
                }
                skip(input, padding)
            }
        }

        /** Tar sizes are octal ASCII, padded with spaces or NULs. */
        private fun parseOctal(b: ByteArray, at: Int, len: Int): Long {
            var result = 0L
            for (i in at until at + len) {
                val c = b[i].toInt().toChar()
                if (c in '0'..'7') result = result * 8 + (c - '0')
                else if (c == '\u0000' || (c == ' ' && result > 0)) break
            }
            return result
        }

        private fun readBlock(input: InputStream, block: ByteArray): Boolean {
            var read = 0
            while (read < block.size) {
                val n = input.read(block, read, block.size - read)
                if (n < 0) return false
                read += n
            }
            return true
        }

        private fun readFully(input: InputStream, bytes: ByteArray) {
            if (!readBlock(input, bytes)) throw java.io.EOFException("Truncated tar archive")
        }

        private fun copy(input: InputStream, out: java.io.OutputStream?, size: Long) {
            val buffer = ByteArray(64 * 1024)
            var left = size
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw java.io.EOFException("Truncated tar archive")
                out?.write(buffer, 0, n)
                left -= n
            }
        }

        private fun skip(input: InputStream, size: Long) = copy(input, null, size)
    }
}
