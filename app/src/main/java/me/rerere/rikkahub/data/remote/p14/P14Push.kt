package me.rerere.rikkahub.data.remote.p14

import android.app.Activity
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koin.core.context.GlobalContext
import org.unifiedpush.android.connector.UnifiedPush

internal const val P14_PUSH_INSTANCE="rikkahub-p14-remote-v1"
@Serializable private data class PushConfig(val enabled:Boolean,val transport:String,@SerialName("vapid_public_key") val vapid:String?=null)
@Serializable private data class PushSubscription(@SerialName("device_id") val deviceId:String,val active:Boolean,val revision:Int)
@Serializable private data class PushCommand(val endpoint:String,val p256dh:String,val auth:String,@SerialName("expected_revision") val revision:Int?=null)

internal object P14Push {
    private val json=Json{ignoreUnknownKeys=true;explicitNulls=false}; private val media="application/json; charset=utf-8".toMediaType()
    fun enable(activity:Activity,onResult:(Result<Unit>)->Unit) {
        val koin=GlobalContext.get(); val store=P14RuntimeCredentialStore(activity)
        val c=store.activeConnection() ?: return onResult(Result.failure(IllegalStateException("Open a P14 Remote chat first")))
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launchSafely {
            val http=koin.get<OkHttpClient>()
            val cfg:PushConfig=json.decodeFromString(execute(http,Request.Builder().url("${c.baseUrl}/push/config").header("Authorization","Bearer ${c.bearerToken}").get().build()))
            val vapid=cfg.vapid
            if(!cfg.enabled || cfg.transport!="unifiedpush" || vapid.isNullOrBlank()) error("P14 Server has no UnifiedPush/Web Push")
            activity.getSharedPreferences("p14-push",Context.MODE_PRIVATE).edit().putBoolean("enabled",true).putString("vapid",vapid).apply()
            withContext(Dispatchers.Main) {
                UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
                    if(!ok) onResult(Result.failure(IllegalStateException("No UnifiedPush distributor")))
                    else runCatching { UnifiedPush.register(context=activity.applicationContext, instance=P14_PUSH_INSTANCE, messageForDistributor="RikkaHub P14 wake-up", vapid=vapid) }.fold({onResult(Result.success(Unit))},{onResult(Result.failure(it))})
                }
            }
        }
    }
    suspend fun registerEndpoint(context:Context,endpoint:String,p256dh:ByteArray,auth:ByteArray) {
        val store=P14RuntimeCredentialStore(context); val c=store.activeConnection()?:return
        val http=GlobalContext.get().get<OkHttpClient>(); val device=store.deviceId()
        val existing=runCatching { json.decodeFromString<PushSubscription>(execute(http,Request.Builder().url("${c.baseUrl}/push/devices/$device").header("Authorization","Bearer ${c.bearerToken}").get().build())) }.getOrNull()
        val cmd=PushCommand(endpoint,Base64.encodeToString(p256dh,Base64.NO_WRAP),Base64.encodeToString(auth,Base64.NO_WRAP),existing?.revision)
        val req=Request.Builder().url("${c.baseUrl}/push/devices/$device").header("Authorization","Bearer ${c.bearerToken}").put(json.encodeToString(cmd).toRequestBody(media)).build()
        execute(http,req)
    }
    fun wake(context:Context)=P14RemoteWork.enqueue(context)
    private fun execute(http:OkHttpClient,req:Request)=http.newCall(req).execute().use { r -> val t=r.body?.string().orEmpty(); if(!r.isSuccessful) throw P14RemoteHttpException(r.code,t); t }
}
private fun kotlinx.coroutines.CoroutineScope.launchSafely(block:suspend kotlinx.coroutines.CoroutineScope.()->Unit)=launch { runCatching { block() } }
