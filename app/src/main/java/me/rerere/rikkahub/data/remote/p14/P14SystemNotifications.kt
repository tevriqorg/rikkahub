package me.rerere.rikkahub.data.remote.p14

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.activity.P14EntryActivity
import me.rerere.rikkahub.ui.activity.P14InboxActivity

internal object P14SystemNotifications {
    private const val CHANNEL="p14_remote_notifications"
    private val json=Json { ignoreUnknownKeys=true }

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
            // Every OS notification opens the durable Notification detail first.
            // A linked Entry/conversation is not a replacement for the notification:
            // the user may discuss the notice without granting an Action.
            val contentIntent=Intent(context,P14InboxActivity::class.java)
                .putExtra("notification_id",n.notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending=PendingIntent.getActivity(context,id,contentIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder=NotificationCompat.Builder(context,CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle(n.title).setContentText(n.body).setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
                .setContentIntent(pending).setAutoCancel(false).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.mipmap.ic_launcher_foreground).setContentTitle("P14 Remote").setContentText("Open RikkaHub to view").build())

            n.interactionId?.let { interactionId ->
                val interaction=dao.interaction(interactionId)
                if(interaction?.actionable==true && interaction.status=="PENDING") {
                    val options=runCatching { json.decodeFromString<List<P14InteractionOption>>(interaction.optionsJson) }.getOrDefault(emptyList())
                    options.take(2).forEachIndexed { index,option ->
                        val actionIntent=Intent(context,P14EntryActivity::class.java)
                            .putExtra(P14EntryActivity.EXTRA_CONVERSATION_ID,interaction.conversationId)
                            .putExtra(P14EntryActivity.EXTRA_NOTIFICATION_ID,n.notificationId)
                            .putExtra(P14EntryActivity.EXTRA_INTERACTION_RESPONSE,option.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        val actionPending=PendingIntent.getActivity(
                            context,id*31+index+1,actionIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        builder.addAction(0,option.label,actionPending)
                    }
                }
            }
            runCatching { nm.notify(id,builder.build()) }
        }
    }
}
