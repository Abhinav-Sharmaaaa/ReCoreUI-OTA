package com.recore.ota

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var versionText: TextView
    private lateinit var status: TextView
    private lateinit var changelog: TextView
    private lateinit var progress: ProgressBar
    private lateinit var checkBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var branchBox: LinearLayout
    private lateinit var branchHint: TextView

    private var feed: Feed? = null
    private var queue: List<Update> = emptyList()
    private var access = Access(emptySet(), emptySet(), emptySet())
    private var busy = false
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad * 3, pad * 2, pad)
        }

        root.addView(TextView(this).apply {
            text = "Software update"
            textSize = 28f
            setPadding(0, 0, 0, pad)
        })

        versionText = TextView(this).apply { textSize = 15f }
        root.addView(versionText)

        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, pad * 2, 0, pad / 2)
        }
        root.addView(status)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(progress)

        changelog = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, pad)
        }
        root.addView(changelog)

        updateBtn = Button(this).apply {
            text = "Download and install"
            visibility = View.GONE
        }
        updateBtn.setOnClickListener { update() }
        root.addView(updateBtn)

        checkBtn = Button(this).apply { text = "Check for updates" }
        checkBtn.setOnClickListener { check() }
        root.addView(checkBtn)

        root.addView(TextView(this).apply {
            text = "Update channel"
            textSize = 20f
            setPadding(0, pad * 2, 0, pad / 2)
        })
        branchBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(branchBox)
        branchHint = TextView(this).apply {
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(branchHint)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshInfo()
        renderBranches()

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        UpdateJob.schedule(this)
        check()
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
    }

    private fun refreshInfo() {
        versionText.text = "ReCore build ${Ota.currentBuild()} (${Ota.romChannel()})\n" +
            "Device: ${Ota.device()}\nUpdate channel: ${Ota.channel(this)}"
    }

    private fun setStatus(t: String) {
        ui.post { status.text = t }
    }

    private fun renderBranches() {
        branchBox.removeAllViews()
        val current = Ota.channel(this)
        val approved = Ota.approvedBranches(this)
        val gate = Ota.gateEnabled()
        val rows = if (gate) listOf("release", "beta", "test") else listOf("release")
        for (b in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, pad / 2, 0, pad / 2)
            }
            val label = TextView(this).apply {
                text = b.replaceFirstChar { it.uppercase() }
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btn = Button(this)
            when {
                b == current -> {
                    btn.text = "Current"
                    btn.isEnabled = false
                }
                b == "release" || !gate || approved.contains(b) -> {
                    btn.text = "Switch"
                    btn.setOnClickListener { switchTo(b) }
                }
                access.pending.contains(b) -> {
                    btn.text = "Waiting for approval"
                    btn.isEnabled = false
                }
                access.denied.contains(b) -> {
                    btn.text = "Declined"
                    btn.isEnabled = false
                }
                else -> {
                    btn.text = "Request access"
                    btn.setOnClickListener { requestAccess(b) }
                }
            }
            row.addView(label)
            row.addView(btn)
            branchBox.addView(row)
        }
        branchHint.text = if (gate) {
            "Beta and test builds are for testers. Access has to be approved by the developer; you are told when it is. " +
                "Release builds can be older than beta ones, so going back may need the full ROM zip."
        } else {
            "Beta and test access is not set up in this build."
        }
    }

    private fun switchTo(branch: String) {
        Ota.setChannel(this, branch)
        feed = null
        queue = emptyList()
        updateBtn.visibility = View.GONE
        changelog.text = ""
        status.text = "Channel set to $branch."
        refreshInfo()
        renderBranches()
        check()
    }

    private fun requestAccess(branch: String) {
        if (busy) return
        busy = true
        setStatus("Sending your request...")
        io.execute {
            try {
                val state = Ota.requestAccess(this, branch)
                val a = Ota.fetchAccess(this)
                ui.post {
                    access = a
                    status.text = when (state) {
                        "approved" -> "Access to $branch is already approved."
                        "denied" -> "Your request for $branch was declined."
                        else -> "Request sent. You will get a notification when it is approved."
                    }
                    refreshInfo()
                    renderBranches()
                }
            } catch (e: Exception) {
                setStatus("Could not send the request: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun refreshAccess() {
        if (!Ota.gateEnabled()) return
        io.execute {
            try {
                val a = Ota.fetchAccess(this)
                ui.post {
                    access = a
                    refreshInfo()
                    renderBranches()
                }
            } catch (e: Exception) {
                // offline: keep what is known
            }
        }
    }

    private fun check() {
        if (busy) return
        busy = true
        setStatus("Checking...")
        io.execute {
            try {
                Ota.cleanup(this)
                if (Ota.gateEnabled()) {
                    try {
                        val a = Ota.fetchAccess(this)
                        ui.post {
                            access = a
                            refreshInfo()
                            renderBranches()
                        }
                    } catch (e: Exception) {
                        // offline: keep what is known
                    }
                }
                val f = Ota.fetchFeed(Ota.channel(this))
                ui.post {
                    feed = f
                    queue = emptyList()
                    if (f == null) {
                        status.text = "No update feed found for this channel."
                        updateBtn.visibility = View.GONE
                    } else if (!Ota.behind(f)) {
                        status.text = "Your software is up to date."
                        changelog.text = ""
                        updateBtn.visibility = View.GONE
                    } else {
                        val q = Ota.queue(f)
                        queue = q
                        if (q.isEmpty()) {
                            status.text = "Build ${f.build} is out, but this phone (build ${Ota.currentBuild()}) can only get it with the full ROM zip."
                            changelog.text = ""
                            updateBtn.visibility = View.GONE
                        } else {
                            status.text = if (q.size == 1) "1 update available" else "${q.size} updates queued, installed one by one"
                            val lines = q.mapIndexed { i, u ->
                                "${i + 1}. Build ${u.build} (${u.size / 1048576} MB)\n${u.changelog}"
                            }.joinToString("\n\n")
                            val rest = if (q.last().build != f.build) "\n\nBuild ${f.build} needs the full ROM zip." else ""
                            changelog.text = lines + rest
                            updateBtn.text = if (q.size == 1) "Download and install" else "Download and install (1 of ${q.size})"
                            updateBtn.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                setStatus("Check failed: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun update() {
        val f = queue.firstOrNull() ?: return
        if (busy) return
        busy = true
        updateBtn.isEnabled = false
        checkBtn.isEnabled = false
        io.execute {
            try {
                val free = Ota.freeBytes()
                if (free < f.requiresFree) {
                    setStatus("Not enough free storage. Free up about ${f.requiresFree / 1073741824 + 1} GB (you have ${free / 1073741824} GB) and try again.")
                } else {
                    setStatus("Downloading build ${f.build}...")
                    var last = 0L
                    val zip = Ota.download(this, f) { done, total ->
                        val now = System.currentTimeMillis()
                        if (now - last > 500 || done == total) {
                            last = now
                            ui.post {
                                progress.progress = (done * 1000 / total).toInt()
                                status.text = "Downloading build ${f.build}: ${done / 1048576} of ${total / 1048576} MB"
                            }
                        }
                    }
                    setStatus("Verified. Restarting into recovery to install build ${f.build}...")
                    val err = Ota.install(zip)
                    if (err != null) setStatus(err)
                }
            } catch (e: Exception) {
                setStatus("Update failed: ${e.message}")
            } finally {
                busy = false
                ui.post {
                    updateBtn.isEnabled = true
                    checkBtn.isEnabled = true
                }
            }
        }
    }
}
