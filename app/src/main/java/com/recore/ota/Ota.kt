package com.recore.ota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Feed(
    val device: String,
    val channel: String,
    val build: Int,
    val filename: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val changelog: String
)

object Ota {
    private const val PREFS = "ota"
    private const val NOTIFY_CHANNEL = "ota"

    fun prop(key: String): String {
        return try {
            val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out
        } catch (e: Exception) {
            ""
        }
    }

    fun currentBuild(): Int = prop("ro.recore.ota.build").toIntOrNull() ?: 0
    fun romChannel(): String = prop("ro.recore.ota.channel").ifEmpty { "release" }
    fun device(): String = prop("ro.product.device")

    fun channel(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("channel", null) ?: romChannel()

    fun setChannel(ctx: Context, channel: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("channel", channel).apply()
    }

    private fun feedUrl(channel: String): String =
        "https://github.com/${BuildConfig.OTA_REPO}/releases/download/feed/${device()}-$channel.json"

    fun fetchFeed(channel: String): Feed? {
        val conn = URL(feedUrl(channel)).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            val code = conn.responseCode
            if (code == 404) return null
            if (code != 200) throw IOException("HTTP $code")
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            val url = j.getString("url")
            if (!url.startsWith("https://github.com/${BuildConfig.OTA_REPO}/releases/download/")) {
                throw IOException("Feed points outside the OTA repo")
            }
            return Feed(
                device = j.getString("device"),
                channel = j.getString("channel"),
                build = j.getInt("build"),
                filename = j.getString("filename"),
                url = url,
                size = j.getLong("size"),
                sha256 = j.getString("sha256").lowercase(),
                changelog = j.optString("changelog", "")
            )
        } finally {
            conn.disconnect()
        }
    }

    // Newer build, or a different channel chosen on purpose (allows going to a lower build).
    fun isNewer(feed: Feed, ctx: Context): Boolean {
        val cur = currentBuild()
        return feed.build > cur || (channel(ctx) != romChannel() && feed.build != cur)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun download(ctx: Context, f: Feed, onProgress: (Long, Long) -> Unit): File {
        val dir = ctx.getExternalFilesDir(null) ?: throw IOException("No storage available")
        val out = File(dir, "update.zip")
        val part = File(dir, "update.zip.part")
        if (out.exists() && out.length() == f.size && sha256(out) == f.sha256) return out
        out.delete()

        var have = if (part.exists()) part.length() else 0L
        if (have > f.size) {
            part.delete()
            have = 0L
        }
        if (have < f.size) {
            val conn = URL(f.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            try {
                conn.connect()
                val code = conn.responseCode
                if (code == 200 && have > 0) {
                    part.delete()
                    have = 0L
                } else if (code != 200 && code != 206) {
                    throw IOException("HTTP $code")
                }
                FileOutputStream(part, have > 0).use { os ->
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(65536)
                        var total = have
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            total += n
                            onProgress(total, f.size)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        if (part.length() != f.size) throw IOException("Download incomplete, tap Update to resume")
        if (sha256(part) != f.sha256) {
            part.delete()
            throw IOException("Checksum mismatch, download deleted")
        }
        if (!part.renameTo(out)) throw IOException("Could not finalize the download")
        return out
    }

    private fun su(script: String): Pair<Int, String> {
        val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        val finished = p.waitFor(15, TimeUnit.SECONDS)
        val text = try {
            if (finished) p.inputStream.bufferedReader().readText().trim() else ""
        } catch (e: Exception) {
            ""
        }
        return Pair(if (finished) p.exitValue() else 0, text)
    }

    // Returns an error message, or null when the device is rebooting into TWRP.
    fun install(zip: File): String? {
        if (!zip.exists()) return "Update file is missing"
        val check = try {
            su("id")
        } catch (e: Exception) {
            return "Root is not available. Install manually: reboot to TWRP and flash ${zip.absolutePath}"
        }
        if (!check.second.contains("uid=0")) {
            return "Root was not granted. Allow root for ReCore Updater in KernelSU, then tap Update again."
        }
        val path = zip.absolutePath.replaceFirst("/storage/emulated/0", "/sdcard")
        val script = "mkdir -p /cache/recovery && " +
            "printf 'install %s\\nreboot\\n' '$path' > /cache/recovery/openrecoveryscript && " +
            "sync && reboot recovery"
        return try {
            val r = su(script)
            if (r.first != 0) "Could not start the install: ${r.second}" else null
        } catch (e: Exception) {
            "Could not start the install: ${e.message}"
        }
    }

    fun notifyAvailable(ctx: Context, f: Feed) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(NOTIFY_CHANNEL, "System updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, NOTIFY_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ReCore update available")
            .setContentText("Build ${f.build} (${f.channel})")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
    }
}
