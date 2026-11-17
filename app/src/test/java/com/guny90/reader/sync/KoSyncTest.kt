package com.guny90.reader.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * Runs the client against a small in-process server that follows the rules of
 * koreader-sync-server (app/controllers/1/syncs_controller.lua, config/errors.lua).
 */
class KoSyncTest {
    private lateinit var server: HttpServer
    private lateinit var url: String
    private val users = HashMap<String, String>()
    private val docs = HashMap<String, JSONObject>()
    private var clock = 1_700_000_000L

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { handle(it) }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        fun reply(status: Int, body: JSONObject) {
            val bytes = body.toString().toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        fun error(code: Int, status: Int) = reply(status, JSONObject().put("code", code).put("message", "error $code"))
        // gin routes by API version in the Accept header
        if (ex.requestHeaders.getFirst("Accept") != "application/vnd.koreader.v1+json") return error(0, 404)
        val user = ex.requestHeaders.getFirst("x-auth-user")
        val key = ex.requestHeaders.getFirst("x-auth-key")
        val authorized = user != null && key != null && users[user] == key
        val path = ex.requestURI.path
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8).let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        when {
            ex.requestMethod == "POST" && path == "/users/create" -> {
                val name = body.optString("username")
                if (name.isEmpty() || name.contains(':') || body.optString("password").isEmpty()) return error(2003, 403)
                if (users.containsKey(name)) return error(2002, 402)
                users[name] = body.getString("password")
                reply(201, JSONObject().put("username", name))
            }
            ex.requestMethod == "GET" && path == "/users/auth" ->
                if (authorized) reply(200, JSONObject().put("authorized", "OK")) else error(2001, 401)
            ex.requestMethod == "PUT" && path == "/syncs/progress" -> {
                if (!authorized) return error(2001, 401)
                val doc = body.optString("document")
                if (!doc.matches(Regex("^[A-Za-z0-9_]+$"))) return error(2007, 403)
                if (!body.has("progress") || !body.has("percentage") || !body.has("device")) return error(2003, 403)
                val stamp = ++clock
                docs["$user:$doc"] = JSONObject(body.toString()).put("timestamp", stamp).apply { remove("document") }
                reply(200, JSONObject().put("document", doc).put("timestamp", stamp))
            }
            ex.requestMethod == "GET" && path.startsWith("/syncs/progress/") -> {
                if (!authorized) return error(2001, 401)
                val doc = path.removePrefix("/syncs/progress/")
                val stored = docs["$user:$doc"] ?: return reply(200, JSONObject())
                reply(200, JSONObject(stored.toString()).put("document", doc))
            }
            else -> error(0, 404)
        }
    }

    @Test fun registersAuthorizesAndRejectsWrongPassword() {
        val key = KoSync.md5Hex("secret")
        KoSync(url, "ala", key).register()
        assertEquals(KoSync.md5Hex("secret"), users["ala"]) // the server stores the hash, not the password
        KoSync(url, "ala", key).authorize()
        try {
            KoSync(url, "ala", KoSync.md5Hex("wrong")).authorize(); fail()
        } catch (e: KoSync.SyncException) {
            assertEquals(401, e.status)
            assertEquals("Wrong username or password.", e.message)
        }
        try {
            KoSync(url, "ala", key).register(); fail()
        } catch (e: KoSync.SyncException) {
            assertEquals("This username is already taken.", e.message)
        }
    }

    @Test fun pushesAndPullsProgress() {
        val client = KoSync("$url/", "ala", KoSync.md5Hex("pw")) // trailing slash is fine
        client.register()
        assertNull(client.pull("abc123"))
        val stamp = client.push("abc123", "reader:1:4:1200", 0.42, "Phone", "dev1")
        val p = client.pull("abc123")!!
        assertEquals("reader:1:4:1200", p.progress)
        assertEquals(0.42, p.percentage, 1e-9)
        assertEquals("Phone", p.device)
        assertEquals("dev1", p.deviceId)
        assertEquals(stamp, p.timestamp)
    }

    @Test fun reportsUnreachableServer() {
        try {
            KoSync("http://127.0.0.1:1", "a", "b").authorize(); fail()
        } catch (e: KoSync.SyncException) {
            assertTrue(e.message!!.startsWith("Can't connect to the sync server"))
        }
    }

    @Test fun reportsServerDownForProxyErrorsAndTimeouts() {
        // What sync.koreader.rocks returned during its outage: Cloudflare 522 with an HTML body.
        val down = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        down.createContext("/slow") { Thread.sleep(1500); it.sendResponseHeaders(200, -1); it.close() }
        down.createContext("/") { ex ->
            val body = "error code: 522".toByteArray()
            ex.sendResponseHeaders(522, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        down.start()
        val base = "http://127.0.0.1:${down.address.port}"
        try {
            try {
                KoSync(base, "a", "b").register(); fail()
            } catch (e: KoSync.SyncException) {
                assertEquals("${KoSync.SERVER_DOWN} (Error 522.)", e.message)
            }
            try {
                KoSync("$base/slow", "a", "b", readTimeoutMs = 300).authorize(); fail()
            } catch (e: KoSync.SyncException) {
                assertEquals(KoSync.SERVER_DOWN, e.message)
            }
        } finally {
            down.stop(0)
        }
    }

    @Test fun positionEncoding() {
        val p = PositionSync.Position(3, 12345)
        assertEquals("reader:1:3:12345", p.encode())
        assertEquals(p, PositionSync.Position.decode(p.encode()))
        assertNull(PositionSync.Position.decode("/body/DocFragment[3]/body/p[2]")) // a KOReader xpointer
        assertNull(PositionSync.Position.decode("reader:1:x:1"))
    }

    @Test fun partialMd5MatchesKoreaderAlgorithm() {
        val file = File(Files.createTempDirectory("md5").toFile(), "book.epub")
        file.writeBytes(ByteArray(300_000) { ((it * 7 + 3) % 251).toByte() })
        // Reference computed independently in Python with KOReader's offsets (0, 1K, 4K, 16K, …).
        assertEquals("3e53b613def422b326c240820288f5ef", KoSync.partialMd5(file))
        file.parentFile!!.deleteRecursively()
    }
}
