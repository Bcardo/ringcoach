package com.bcardo.ringcoach

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Ordinary persisted Android work: no foreground service or notification. */
class ProgressJob : JobService() {
    private val scopes = mutableMapOf<Int, CoroutineScope>()
    override fun onStartJob(params: JobParameters): Boolean {
        val database = params.extras.getString("database") ?: "ringcoach.db"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes[params.jobId] = scope
        scope.launch {
            var retry = false
            var cancelled = false
            try {
                ProgressRuntime.mutex.withLock {
                    ProgressRuntime.started(database)
                    try {
                        do { retry = ProgressProcessor(applicationContext, database).run() }
                        while (!retry && ProgressRuntime.consumeRequested(database))
                    }
                    catch (e: CancellationException) { cancelled = true; throw e }
                    finally { ProgressRuntime.finished(database) { requested ->
                        if (!cancelled) jobFinished(params, retry || requested)
                    } }
                }
            } catch (e: CancellationException) { cancelled = true; throw e }
            catch (_: Exception) { /* Unrecoverable storage/setup errors wait for a new open or manual retry. */ }
            finally {
                scope.cancel()
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        scopes.remove(params.jobId)?.cancel()
        return true
    }
    override fun onDestroy() { scopes.values.forEach { it.cancel() }; scopes.clear(); super.onDestroy() }

    companion object {
        const val JOB_ID = 9272
        fun schedule(context: Context, database: String = "ringcoach.db", replaceExisting: Boolean = false): Int {
            if (ProgressRuntime.requestIfRunning(database)) return JobScheduler.RESULT_SUCCESS
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val id = if (database == "ringcoach.db") JOB_ID else 9273
            if (!replaceExisting && scheduler.getPendingJob(id) != null) return JobScheduler.RESULT_SUCCESS
            val extras = PersistableBundle().apply { putString("database", database) }
            return scheduler.schedule(JobInfo.Builder(id, ComponentName(context, ProgressJob::class.java))
                .setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true).build())
        }
    }
}

class ProgressBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ProgressJob.schedule(context)
    }
}
