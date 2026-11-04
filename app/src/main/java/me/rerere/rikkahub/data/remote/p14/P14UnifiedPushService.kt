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
        val keys=endpoint.pubKeySet
        if(keys==null) {
            P14Push.recordRegistrationStatus(applicationContext,
                "分发器未提供 Web Push 加密密钥，请使用支持 Web Push 的 UnifiedPush 分发器")
            return
        }
        P14Push.recordRegistrationStatus(applicationContext,"已收到分发器端点，正在登记到 P14 服务器…")
        scope.launch {
            runCatching {
                P14Push.registerEndpoint(applicationContext,endpoint.url,keys.pubKey,keys.auth)
            }.onFailure {
                P14Push.recordRegistrationStatus(applicationContext,
                    "端点上传失败："+(it.message ?: "网络或权限错误").take(180))
            }
            P14Push.wake(applicationContext)
        }
    }
    override fun onRegistrationFailed(reason:FailedReason,instance:String) {
        if(instance==P14_PUSH_INSTANCE)
            P14Push.recordRegistrationStatus(applicationContext,"分发器注册失败："+reason.toString())
    }
    override fun onUnregistered(instance:String) {
        if(instance==P14_PUSH_INSTANCE)
            P14Push.recordRegistrationStatus(applicationContext,"推送分发器已取消本设备注册")
    }
}
