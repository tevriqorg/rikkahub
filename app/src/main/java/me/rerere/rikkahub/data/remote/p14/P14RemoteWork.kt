package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import androidx.work.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

internal object P14RemoteWork {
    fun enqueue(context:Context) {
        val req=OneTimeWorkRequestBuilder<P14RemoteSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("p14-remote-sync",ExistingWorkPolicy.KEEP,req)
    }
}
class P14RemoteSyncWorker(ctx:Context,params:WorkerParameters):CoroutineWorker(ctx,params) {
    override suspend fun doWork():Result = runCatching {
        val koin=GlobalContext.get()
        val repo=P14RemoteRepository(applicationContext,koin.get<SettingsStore>(),koin.get<OkHttpClient>())
        if(!repo.processPendingActive()) repo.refreshActive()
        Result.success()
    }.getOrElse { Result.retry() }
}
