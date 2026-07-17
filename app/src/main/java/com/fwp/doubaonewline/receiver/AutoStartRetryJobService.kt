package com.fwp.doubaonewline.receiver

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.fwp.doubaonewline.bridge.AutoStartMonitorService
import com.fwp.doubaonewline.bridge.BridgeContract

class AutoStartRetryJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = getSharedPreferences(BridgeContract.PREFS, MODE_PRIVATE)
        val enabled = prefs.getBoolean(BridgeContract.PREF_ENABLED, false)
        Log.i(TAG, "Retry job started enabled=$enabled")
        if (enabled) {
            runCatching { AutoStartMonitorService.start(this, trigger = true) }
                .onFailure { Log.w(TAG, "Unable to start monitor from retry job", it) }
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val TAG = "AutoStartRetryJob"
        private const val JOB_ID = 4005

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, AutoStartRetryJobService::class.java)
            )
                .setMinimumLatency(5_000L)
                .build()
            runCatching { scheduler.schedule(info) }
                .onFailure { Log.w(TAG, "Unable to schedule retry job", it) }
        }
    }
}
