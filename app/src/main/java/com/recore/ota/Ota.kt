package com.recore.ota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// One published update step. A delta only applies to the exact build in fromBuild.
data class Update(
    val build: Int,
    val fromBuild: Int,
    val type: String,
    val filename: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val requiresFree: Long,
    val changelog: String
)

// Which branches this phone may use. release never needs approval.
data class Access(val approved: Set<String>, val pending: Set<String>, val denied: Set<String>)

data class Feed(
    val device: String,
    val channel: String,
    val build: Int,
    val updates: List<Update>
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
    fun device(): String = prop("ro.recore.ota.device").ifEmpty { prop("ro.product.device") }

    fun channel(ctx: Context): String {
        val want = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("channel", null) ?: romChannel()
        if (want == "release" || !gateEnabled()) return want
        return if (approvedBranches(ctx).contains(want)) want else "release"
    }

    fun setChannel(ctx: Context, channel: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("channel", channel).apply()
    }

    private fun feedUrl(channel: String): String =
        "https://github.com/${BuildConfig.OTA_REPO}/releases/download/feed/${device()}-$channel.json"

    private fun parseUpdate(j: JSONObject): Update {
        val url = j.optString("url", "")
        val type = j.optString("type", "none")
        if (type == "delta" && !url.startsWith("https://github.com/${BuildConfig.OTA_REPO}/releases/download/")) {
            throw IOException("Feed points outside the OTA repo")
        }
        return Update(
            build = j.getInt("build"),
            fromBuild = if (j.isNull("from_build")) -1 else j.optInt("from_build", -1),
            type = type,
            filename = j.optString("filename", ""),
            url = url,
            size = j.optLong("size", 0L),
            sha256 = j.optString("sha256", "").lowercase(),
            requiresFree = j.optLong("requires_free", 0L),
            changelog = j.optString("changelog", "")
        )
    }

    fun fetchFeed(channel: String): Feed? {
        val conn = URL(feedUrl(channel)).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            val code = conn.responseCode
            if (code == 404) return null
            if (code != 200) throw IOException("HTTP $code")
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            val list = ArrayList<Update>()
            val arr = j.optJSONArray("updates")
            if (arr != null) {
                for (i in 0 until arr.length()) list.add(parseUpdate(arr.getJSONObject(i)))
            } else {
                list.add(parseUpdate(j))
            }
            return Feed(j.getString("device"), j.getString("channel"), j.getInt("build"), list)
        } finally {
            conn.disconnect()
        }
    }

    fun behind(feed: Feed): Boolean = feed.build > currentBuild()

    // The updates this phone can install one after another, starting from the build it is on.
    fun queue(feed: Feed): List<Update> {
        val out = ArrayList<Update>()
        var cur = currentBuild()
        while (true) {
            val step = feed.updates.firstOrNull { it.type == "delta" && it.fromBuild == cur && it.build > cur } ?: break
            out.add(step)
            cur = step.build
        }
        return out
    }

    // ---- branch access: test and beta need the developer's approval ----

    fun gateEnabled(): Boolean = BuildConfig.GATE_URL.isNotEmpty()

    fun deviceId(ctx: Context): String {
        val aid = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val md = MessageDigest.getInstance("SHA-256").digest("recore:$aid".toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun gateCall(method: String, path: String, body: JSONObject?): JSONObject {
        val conn = URL(BuildConfig.GATE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText() ?: "{}"
            val j = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            if (code !in 200..299) throw IOException(j.optString("error", "HTTP $code"))
            return j
        } finally {
            conn.disconnect()
        }
    }

    fun approvedBranches(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("approved", emptySet()) ?: emptySet()

    fun fetchAccess(ctx: Context): Access {
        val j = gateCall("GET", "/status?id=" + deviceId(ctx), null)
        fun names(key: String): Set<String> {
            val a = j.optJSONArray(key) ?: return emptySet()
            return (0 until a.length()).map { a.getString(it) }.toSet()
        }
        val acc = Access(names("approved"), names("pending"), names("denied"))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("approved", acc.approved).apply()
        return acc
    }

    // Returns "pending", "approved" or "denied".
    fun requestAccess(ctx: Context, branch: String): String {
        val body = JSONObject()
            .put("id", deviceId(ctx))
            .put("device", device())
            .put("model", prop("ro.product.model"))
            .put("branch", branch)
            .put("build", currentBuild())
        return gateCall("POST", "/request", body).optString("state", "pending")
    }

    fun freeBytes(): Long = StatFs(Environment.getDataDirectory().path).availableBytes

    // Remove downloads of updates that are already installed.
    fun cleanup(ctx: Context) {
        val dir = ctx.getExternalFilesDir(null) ?: return
        val cur = currentBuild()
        dir.listFiles()?.forEach { f ->
            val m = Regex("update-(\\d+)\\.zip(\\.part)?").matchEntire(f.name)
            if (m != null && m.groupValues[1].toInt() <= cur) f.delete()
        }
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

    fun download(ctx: Context, f: Update, onProgress: (Long, Long) -> Unit): File {
        val dir = ctx.getExternalFilesDir(null) ?: throw IOException("No storage available")
        val out = File(dir, "update-${f.build}.zip")
        val part = File(dir, "update-${f.build}.zip.part")
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
        if (part.length() != f.size) throw IOException("Download incomplete, tap Install to resume")
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

    fun notifyAvailable(ctx: Context, count: Int, channel: String) {
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
            .setContentText(if (count == 1) "1 update ($channel)" else "$count updates ($channel)")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
    }

    fun notifyInfo(ctx: Context, title: String, text: String) {
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
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(2, n)
    }
}
