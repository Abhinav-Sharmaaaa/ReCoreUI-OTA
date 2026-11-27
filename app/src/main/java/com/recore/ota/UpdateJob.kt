package com.recore.ota

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

class UpdateJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                if (Ota.gateEnabled()) {
                    try {
                        val before = Ota.approvedBranches(this)
                        val acc = Ota.fetchAccess(this)
                        val added = acc.approved - before
                        if (added.isNotEmpty()) {
                            Ota.notifyInfo(this, "Access approved", "You can now get ${added.joinToString(", ")} updates. Open ReCore Updater to switch.")
                        }
                    } catch (e: Exception) {
                        // keep the last known access
                    }
                }
                val feed = Ota.fetchFeed(Ota.channel(this))
                if (feed != null && Ota.behind(feed)) {
                    val q = Ota.queue(feed)
                    if (q.isNotEmpty()) Ota.notifyAvailable(this, q.size, feed.channel)
                }
            } catch (e: Exception) {
                // try again at the next period
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(1001) != null) return
            val info = JobInfo.Builder(1001, ComponentName(ctx, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(12L * 60 * 60 * 1000)
                .setPersisted(true)
                .build()
            js.schedule(info)
        }
    }
}
