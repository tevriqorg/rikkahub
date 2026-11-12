package me.rerere.rikkahub.data.remote.p14

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
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
    private const val STATUS_KEY = "registration_status"

    fun registrationStatus(context:Context):String? =
        context.getSharedPreferences("p14-push",Context.MODE_PRIVATE).getString(STATUS_KEY,null)

    fun recordRegistrationStatus(context:Context,message:String) {
        context.getSharedPreferences("p14-push",Context.MODE_PRIVATE)
            .edit().putString(STATUS_KEY,message).apply()
    }

    fun enable(activity:Activity,onResult:(Result<Unit>)->Unit) {
        val store=P14RuntimeCredentialStore(activity)
        val connection=store.activeConnection()
        if(connection==null) {
            val failure=IllegalStateException("请先在 P14 Remote 配置连接并打开一次远程对话")
            recordRegistrationStatus(activity,failure.message.orEmpty())
            activity.runOnUiThread { onResult(Result.failure(failure)) }
            return
        }
        recordRegistrationStatus(activity,"正在检查推送服务器与分发器…")
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            val setup=runCatching {
                val http=GlobalContext.get().get<OkHttpClient>()
                val cfg:PushConfig=json.decodeFromString(
                    execute(http,Request.Builder().url(connection.baseUrl+"/push/config")
                        .header("Authorization","Bearer "+connection.bearerToken).get().build()))
                val vapid=cfg.vapid
                check(cfg.enabled && cfg.transport=="unifiedpush" && !vapid.isNullOrBlank()) {
                    "P14 服务端未启用 UnifiedPush/Web Push"
                }
                requireNotNull(vapid)
            }
            if(setup.isFailure) {
                val failure=setup.exceptionOrNull() ?: IllegalStateException("无法查询推送服务")
                recordRegistrationStatus(activity,"注册失败："+(failure.message ?: "服务器连接失败").take(180))
                withContext(Dispatchers.Main) { onResult(Result.failure(failure)) }
                return@launch
            }
            val vapid=setup.getOrThrow()
            withContext(Dispatchers.Main) {
                runCatching {
                    if(UnifiedPush.getDistributors(activity).isEmpty()) {
                        val failure=IllegalStateException(
                            "当前手机未安装 UnifiedPush 分发器。请安装 ntfy 等兼容应用，启用其 UnifiedPush 功能后重试"
                        )
                        recordRegistrationStatus(activity,"推送注册失败："+failure.message)
                        onResult(Result.failure(failure))
                    } else UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
                        activity.runOnUiThread {
                            val outcome=if(!ok) Result.failure(IllegalStateException(
                                "没有可用的 UnifiedPush 分发器。请安装并启用 ntfy 等支持 UnifiedPush 的 App"
                            )) else runCatching {
                                activity.getSharedPreferences("p14-push",Context.MODE_PRIVATE)
                                    .edit().putBoolean("enabled",true).putString("vapid",vapid).apply()
                                recordRegistrationStatus(activity,"已向分发器发送注册请求，等待设备端点与服务端确认…")
                                UnifiedPush.register(
                                    context=activity.applicationContext,
                                    instance=P14_PUSH_INSTANCE,
                                    messageForDistributor="RikkaHub P14 wake-up",
                                    vapid=vapid,
                                )
                            }
                            if(outcome.isFailure) {
                                recordRegistrationStatus(activity,
                                    "推送注册失败："+(outcome.exceptionOrNull()?.message ?: "未知错误").take(180))
                            }
                            onResult(outcome)
                        }
                    }
                }.onFailure { error ->
                    recordRegistrationStatus(activity,
                        "无法打开推送分发器："+(error.message ?: "未知错误").take(180))
                    onResult(Result.failure(error))
                }
            }
        }
    }
    suspend fun registerEndpoint(context:Context,endpoint:String,p256dh:String,auth:String) {
        val store=P14RuntimeCredentialStore(context); val c=store.activeConnection()?:return
        val http=GlobalContext.get().get<OkHttpClient>(); val device=store.deviceId()
        val existing=runCatching { json.decodeFromString<PushSubscription>(execute(http,Request.Builder().url("${c.baseUrl}/push/devices/$device").header("Authorization","Bearer ${c.bearerToken}").get().build())) }.getOrNull()
        val cmd=PushCommand(endpoint,p256dh,auth,existing?.revision)
        val req=Request.Builder().url("${c.baseUrl}/push/devices/$device").header("Authorization","Bearer ${c.bearerToken}").put(json.encodeToString(cmd).toRequestBody(media)).build()
        execute(http,req)
        recordRegistrationStatus(context,"推送注册成功：P14 服务端已保存此设备端点")
    }
    fun wake(context:Context)=P14RemoteWork.enqueue(context)
    private fun execute(http:OkHttpClient,req:Request)=http.newCall(req).execute().use { r -> val t=r.body?.string().orEmpty(); if(!r.isSuccessful) throw P14RemoteHttpException(r.code,t); t }
}
