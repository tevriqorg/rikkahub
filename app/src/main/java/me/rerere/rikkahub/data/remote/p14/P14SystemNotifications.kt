package me.rerere.rikkahub.data.remote.p14

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.activity.P14InboxActivity

internal object P14SystemNotifications {
    private const val CHANNEL="p14_remote_notifications"
    fun ensureChannel(context:Context) {
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL,"P14 Remote",NotificationManager.IMPORTANCE_DEFAULT).apply {
            description="Durable P14 remote notifications"
            lockscreenVisibility=android.app.Notification.VISIBILITY_PRIVATE
        })
    }
    suspend fun project(context:Context,dao:P14RemoteDao,items:List<P14NotificationDto>) {
        ensureChannel(context)
        val nm=NotificationManagerCompat.from(context)
        items.forEach { n ->
            val id=n.notificationId.hashCode()
            if(n.state=="RESOLVED") { nm.cancel(id); return@forEach }
            val intent=Intent(context,P14InboxActivity::class.java).putExtra("notification_id",n.notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending=PendingIntent.getActivity(context,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b=NotificationCompat.Builder(context,CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle(n.title).setContentText(n.body).setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
                .setContentIntent(pending).setAutoCancel(false).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.mipmap.ic_launcher_foreground).setContentTitle("P14 Remote").setContentText("Open RikkaHub to view").build())
            runCatching { nm.notify(id,b.build()) }
        }
    }
}
