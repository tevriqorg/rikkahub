package me.rerere.rikkahub.data.remote.p14

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class P14UnifiedPushService:PushService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onMessage(message:PushMessage,instance:String) { if(instance==P14_PUSH_INSTANCE && message.decrypted) P14Push.wake(applicationContext) }
    override fun onNewEndpoint(endpoint:PushEndpoint,instance:String) {
        if(instance!=P14_PUSH_INSTANCE) return
        val keys=endpoint.pubKeySet ?: return
        scope.launch { runCatching { P14Push.registerEndpoint(applicationContext,endpoint.url,keys.pubKey,keys.auth) }; P14Push.wake(applicationContext) }
    }
    override fun onRegistrationFailed(reason:FailedReason,instance:String) = Unit
    override fun onUnregistered(instance:String) = Unit
}
