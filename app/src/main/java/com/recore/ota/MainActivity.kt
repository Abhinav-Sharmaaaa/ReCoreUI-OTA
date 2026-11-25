package com.recore.ota

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val channels = listOf("test", "beta", "release")
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var changelog: TextView
    private lateinit var progress: ProgressBar
    private lateinit var checkBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var spinner: Spinner

    private var feed: Feed? = null
    private var queue: List<Update> = emptyList()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad * 3, pad * 2, pad)
        }

        info = TextView(this).apply { textSize = 16f }
        root.addView(info)

        spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, channels)
        spinner.setSelection(channels.indexOf(Ota.channel(this)).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val c = channels[pos]
                if (c != Ota.channel(this@MainActivity)) {
                    Ota.setChannel(this@MainActivity, c)
                    feed = null
                    queue = emptyList()
                    updateBtn.visibility = View.GONE
                    changelog.text = ""
                    status.text = "Channel changed to $c. Tap Check for updates."
                    refreshInfo()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        root.addView(spinner)

        checkBtn = Button(this).apply { text = "Check for updates" }
        checkBtn.setOnClickListener { check() }
        root.addView(checkBtn)

        status = TextView(this).apply { textSize = 16f; setPadding(0, pad, 0, pad / 2) }
        root.addView(status)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(progress)

        changelog = TextView(this).apply { textSize = 14f; setPadding(0, pad, 0, pad) }
        root.addView(changelog)

        updateBtn = Button(this).apply { text = "Update now"; visibility = View.GONE }
        updateBtn.setOnClickListener { update() }
        root.addView(updateBtn)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshInfo()

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        UpdateJob.schedule(this)
        check()
    }

    private fun refreshInfo() {
        info.text = "Device: ${Ota.device()}\nInstalled: build ${Ota.currentBuild()} (${Ota.romChannel()})\nChannel to check:"
    }

    private fun setStatus(t: String) {
        ui.post { status.text = t }
    }

    private fun check() {
        if (busy) return
        busy = true
        setStatus("Checking...")
        io.execute {
            try {
                Ota.cleanup(this)
                val f = Ota.fetchFeed(Ota.channel(this))
                ui.post {
                    feed = f
                    queue = emptyList()
                    if (f == null) {
                        status.text = "No update feed found for this channel."
                        updateBtn.visibility = View.GONE
                    } else if (!Ota.behind(f)) {
                        status.text = "You are up to date."
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
                            updateBtn.text = "Install update 1 of ${q.size} (build ${q.first().build})"
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
