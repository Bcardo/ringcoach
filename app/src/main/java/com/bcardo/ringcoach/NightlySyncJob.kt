package com.bcardo.ringcoach

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Polls after Samsung sync; Android chooses execution time. Progress submissions also refresh. */
class NightlySyncJob : JobService() {
    private var scope: CoroutineScope? = null
    override fun onStartJob(params: JobParameters): Boolean {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { jobScope ->
            jobScope.launch {
                try { SamsungNightlyReader.refresh(applicationContext) }
                finally { jobFinished(params, false); jobScope.cancel() }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { scope?.cancel(); return true }
    companion object {
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(9271) == null) scheduler.schedule(JobInfo.Builder(9271,
                ComponentName(context, NightlySyncJob::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(6), TimeUnit.HOURS.toMillis(1))
                .setPersisted(true).build())
        }
    }
}
