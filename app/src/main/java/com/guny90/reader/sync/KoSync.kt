package com.guny90.reader.sync

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Client for the KOReader sync server protocol ("kosync"), e.g. https://kosync.eu or https://sync.koreader.rocks.
 * Stores only a small record per book: an opaque position string, a percentage and the device.
 *
 * [key] is the MD5 of the password: the server never sees the password itself.
 * Blocking calls: use from a background thread.
 */
class KoSync(
    serverUrl: String,
    private val username: String,
    private val key: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) {

    class Progress(
        val progress: String,
        val percentage: Double,
        val device: String,
        val deviceId: String,
        /** Server time (Unix seconds) of the last upload. */
        val timestamp: Long,
    )

    /** Failure with a message that can be shown to the user as is. */
    class SyncException(message: String, val status: Int = 0) : IOException(message)

    private val base = serverUrl.trim().trimEnd('/')

    /** Checks the username and password. */
    fun authorize() {
        request("GET", "/users/auth", null)
    }

    /** Creates the account (the server stores [key], not the password). */
    fun register() {
        request("POST", "/users/create", JSONObject().put("username", username).put("password", key), auth = false)
    }

    /** Uploads the position of [document]. Returns the server timestamp. */
    fun push(document: String, progress: String, percentage: Double, device: String, deviceId: String): Long {
        val body = JSONObject()
            .put("document", document)
            .put("progress", progress)
            .put("percentage", percentage)
            .put("device", device)
            .put("device_id", deviceId)
        return request("PUT", "/syncs/progress", body).optLong("timestamp", 0)
    }

    /** The stored position of [document], or null if no device has uploaded one yet. */
    fun pull(document: String): Progress? {
        val json = request("GET", "/syncs/progress/" + URLEncoder.encode(document, "UTF-8"), null)
        if (!json.has("progress")) return null
        return Progress(
            progress = json.optString("progress"),
            percentage = json.optDouble("percentage", 0.0),
            device = json.optString("device"),
            deviceId = json.optString("device_id"),
            timestamp = json.optLong("timestamp", 0),
        )
    }

    private fun request(method: String, path: String, body: JSONObject?, auth: Boolean = true): JSONObject {
        val connection = try {
            URL(base + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw SyncException("Invalid server address: $base")
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/vnd.koreader.v1+json")
            if (auth) {
                connection.setRequestProperty("x-auth-user", username)
                connection.setRequestProperty("x-auth-key", key)
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val json = try { JSONObject(text.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
            if (status !in 200..299) throw SyncException(errorMessage(status, json), status)
            return json
        } catch (e: SyncException) {
            throw e
        } catch (e: SocketTimeoutException) {
            // Typical when the server is down behind a proxy: the connection opens but nothing answers.
            throw SyncException(SERVER_DOWN)
        } catch (e: IOException) {
            throw SyncException("Can't connect to the sync server. Check the internet connection.\n(${e.message})")
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(status: Int, json: JSONObject): String = when (json.optInt("code")) {
        2001 -> "Wrong username or password."
        2002 -> "This username is already taken."
        2003 -> "The server rejected the request (invalid username or password)."
        2005 -> "This server doesn't allow new accounts."
        else -> if (status >= 500) "$SERVER_DOWN (Error $status.)" // e.g. Cloudflare 522: server offline
        else "Sync server error $status. ${json.optString("message")}".trim()
    }

    companion object {
        const val DEFAULT_SERVER = "https://kosync.eu"
        const val SERVER_DOWN = "The sync server isn't responding. It may be temporarily down; please try again later."

        fun md5Hex(text: String): String = hex(MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)))

        /**
         * Book fingerprint, same as KOReader's "partial MD5": MD5 of 1 KB samples at offsets
         * 0, 1K, 4K, 16K, … 1G. Identical files give identical ids on every device, whatever
         * their name, and only ~12 KB are read.
         */
        fun partialMd5(file: File): String {
            val md5 = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(1024)
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                for (i in -1..10) {
                    val offset = if (i < 0) 0L else 1024L shl (2 * i)
                    if (offset >= length) break
                    raf.seek(offset)
                    val n = raf.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    md5.update(buffer, 0, n)
                }
            }
            return hex(md5.digest())
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
