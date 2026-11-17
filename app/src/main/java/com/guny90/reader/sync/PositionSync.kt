package com.guny90.reader.sync

import android.content.SharedPreferences
import java.util.UUID

/**
 * Position sync settings and per-book bookkeeping. Network calls are blocking:
 * run [push] / [pullNewer] on a background thread.
 *
 * Avoiding clock problems: devices' clocks may differ, so "newer" is decided only with the
 * server's own timestamps: a remote position is offered once per server timestamp.
 */
class PositionSync(private val prefs: SharedPreferences, private val deviceName: String) {

    /** A position as stored on the server: "reader:1:<chapter>:<char offset>". */
    data class Position(val chapter: Int, val offset: Int) {
        fun encode() = "$PREFIX$chapter:$offset"

        companion object {
            private const val PREFIX = "reader:1:"

            fun decode(text: String): Position? {
                if (!text.startsWith(PREFIX)) return null
                val parts = text.removePrefix(PREFIX).split(':')
                val chapter = parts.getOrNull(0)?.toIntOrNull() ?: return null
                val offset = parts.getOrNull(1)?.toIntOrNull() ?: return null
                return if (chapter >= 0 && offset >= 0) Position(chapter, offset) else null
            }
        }
    }

    class Remote(val position: Position, val percentage: Double, val device: String, val timestamp: Long)

    val deviceId: String = prefs.getString("sync_device_id", null)
        ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString("sync_device_id", it).apply()
        }

    val server: String get() = prefs.getString("sync_server", null) ?: KoSync.DEFAULT_SERVER
    val username: String? get() = prefs.getString("sync_user", null)
    val isEnabled: Boolean get() = username != null && prefs.getString("sync_key", null) != null

    private fun client(): KoSync? {
        val user = username ?: return null
        val key = prefs.getString("sync_key", null) ?: return null
        return KoSync(server, user, key)
    }

    /** Checks (and optionally creates) the account, then saves it. Throws [KoSync.SyncException]. */
    fun signIn(server: String, username: String, password: String, register: Boolean) {
        val key = KoSync.md5Hex(password)
        val client = KoSync(server, username, key)
        if (register) client.register()
        client.authorize()
        prefs.edit()
            .putString("sync_server", server.trim().trimEnd('/'))
            .putString("sync_user", username)
            .putString("sync_key", key)
            .apply()
    }

    fun signOut() {
        prefs.edit().remove("sync_user").remove("sync_key").apply()
    }

    /** Uploads [position] unless already uploaded (or always, with [force]). True if something was sent. */
    fun push(document: String, position: Position, percentage: Double, force: Boolean = false): Boolean {
        val client = client() ?: return false
        val encoded = position.encode()
        if (!force && prefs.getString("sync_pushed_$document", null) == encoded) return false
        val timestamp = client.push(document, encoded, percentage, deviceName, deviceId)
        prefs.edit()
            .putString("sync_pushed_$document", encoded)
            .putLong("sync_seen_$document", timestamp)
            .apply()
        return true
    }

    /**
     * The server's position if another device uploaded it after anything this device has
     * already uploaded or been offered, and it differs from [current]. Otherwise null.
     */
    fun pullNewer(document: String, current: Position): Remote? {
        val client = client() ?: return null
        val remote = client.pull(document) ?: return null
        if (remote.deviceId == deviceId) return null
        if (remote.timestamp <= prefs.getLong("sync_seen_$document", 0)) return null
        val position = Position.decode(remote.progress)
        if (position == null || position == current) {
            markSeen(document, remote.timestamp)
            return null
        }
        return Remote(position, remote.percentage, remote.device, remote.timestamp)
    }

    /** Don't offer this remote position again (accepted or declined). */
    fun markSeen(document: String, timestamp: Long) {
        prefs.edit().putLong("sync_seen_$document", timestamp).apply()
    }

    /** "Stay here": this device's position should win, so upload it next time even if unchanged. */
    fun declined(document: String, timestamp: Long) {
        prefs.edit().putLong("sync_seen_$document", timestamp).remove("sync_pushed_$document").apply()
    }
}
