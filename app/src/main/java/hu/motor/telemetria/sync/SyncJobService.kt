package hu.motor.telemetria.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Rendszerszintű háttérszinkron: akkor is lefut, ha az app épp nincs elindítva.
 * Így ha a túra alatt végig nem volt net, az adat magától felmegy, amint van –
 * nem kell megnyitni az alkalmazást.
 */
class SyncJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        work = scope.launch {
            val complete = SyncManager.syncNow()
            // Ha maradt feltöltendő (megszakadt a net), a rendszer később újrafuttat.
            jobFinished(params, !complete)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 4711

        /** Negyedóránként, hálózat esetén. Újraindítás után is megmarad. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(job) }
        }
    }
}
