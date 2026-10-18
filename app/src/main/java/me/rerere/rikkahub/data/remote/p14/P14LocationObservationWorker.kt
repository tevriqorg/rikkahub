package me.rerere.rikkahub.data.remote.p14

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/** Opt-in, bounded observation: never active GPS polling, never a Chat/Entry/Inbox object. */
internal object P14LocationObservationScheduler {
    private const val WORK_NAME="p14-location-observation"
    fun configure(context:Context) {
        val manager=WorkManager.getInstance(context.applicationContext)
        if (!P14ProfileStore(context).locationEnabled()) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val work=PeriodicWorkRequestBuilder<P14LocationObservationWorker>(30,TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(WORK_NAME,ExistingPeriodicWorkPolicy.KEEP,work)
    }
}

internal class P14LocationObservationWorker(context:Context, params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val ctx=applicationContext
        val policy=P14ProfileStore(ctx)
        if(!policy.locationEnabled()) return Result.success()
        if(ContextCompat.checkSelfPermission(ctx,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return Result.success()
        // Do not collect while background unless user independently grants Android background location.
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(ctx,Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED) return Result.success()
        return runCatching {
            val manager=ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            @Suppress("MissingPermission")
            val fix=manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: return Result.success()
            val age=System.currentTimeMillis()-fix.time
            if(age<0 || age>30*60_000L || fix.accuracy>1000f) return Result.success()
            val repo=P14ProductRepository(ctx,GlobalContext.get().get<OkHttpClient>())
            val previous=repo.recentObservations().firstOrNull()
            if(previous!=null) {
                val distance=FloatArray(1)
                Location.distanceBetween(previous.latitude,previous.longitude,fix.latitude,fix.longitude,distance)
                if(distance[0] < policy.locationMinDistanceMeters()) return Result.success()
            }
            repo.acceptLocationObservation(fix.latitude,fix.longitude,fix.accuracy.toDouble())
            P14RemoteWork.enqueue(ctx)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
