package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.uuid.Uuid

internal class P14RemoteRepository(
    private val context:Context,
    private val settingsStore:SettingsStore,
    private val http:OkHttpClient,
) {
    private val db=P14RemoteDatabase.get(context); private val dao=db.dao()
    private val json=Json { ignoreUnknownKeys=true; explicitNulls=false }
    private val access=P14RuntimeAccess(context,settingsStore,http)
    val notifications:Flow<List<P14NotificationEntity>> = dao.observeNotifications()
    val unreadCount:Flow<Int> = dao.observeUnreadCount()

    suspend fun runtimeProvider(provider:ProviderSetting.OpenAI)=access.prepare(provider)
    suspend fun refresh(provider:ProviderSetting.OpenAI) = refresh(access.connection(provider))
    suspend fun refreshActive():Boolean {
        val c=P14RuntimeCredentialStore(context).activeConnection() ?: return false
        refresh(c); return true
    }
    private suspend fun refresh(c:P14RuntimeConnection)=withContext(Dispatchers.IO) {
        val url=c.baseUrl.toHttpUrl().newBuilder().addPathSegment("notifications")
            .addQueryParameter("include_resolved","true").addQueryParameter("limit","500").build()
        val req=Request.Builder().url(url).header("Authorization","Bearer ${c.bearerToken}").get().build()
        val body=execute(req)
        val items=json.decodeFromString(ListSerializer(P14NotificationDto.serializer()),body)
        dao.upsertNotifications(items.map { it.entity() })
        items.filter { it.interactionId!=null && it.state!="RESOLVED" }.forEach { n ->
            runCatching { fetchInteraction(c,n.notificationId) }
        }
        P14SystemNotifications.project(context, dao, items)
    }

    suspend fun manifest(provider:ProviderSetting.OpenAI):P14EffectiveManifest {
        val c=access.connection(provider)
        return runCatching {
            val url=c.baseUrl.toHttpUrl().newBuilder().addPathSegment("remote-manifest")
                .addQueryParameter("client_protocol",P14RikkaCapabilities.PROTOCOL.toString()).build()
            val dto:P14ManifestDto=json.decodeFromString(execute(Request.Builder().url(url).header("Authorization","Bearer ${c.bearerToken}").get().build()))
            val effective=dto.effective()
            context.getSharedPreferences("p14-manifest",Context.MODE_PRIVATE).edit().putString(c.baseUrl,json.encodeToString(dto)).apply()
            effective
        }.getOrElse {
            val raw=context.getSharedPreferences("p14-manifest",Context.MODE_PRIVATE).getString(c.baseUrl,null)
            if(raw!=null) runCatching { json.decodeFromString<P14ManifestDto>(raw).effective("LAST_KNOWN_GOOD") }.getOrElse{fallbackP14Manifest()}
            else fallbackP14Manifest()
        }
    }

    suspend fun queueState(notificationId:String,state:String) {
        dao.enqueueOperation(P14OperationEntity(Uuid.random().toString(),notificationId,"STATE",state))
        P14RemoteWork.enqueue(context)
    }
    suspend fun queueInteraction(notificationId:String,response:String) {
        dao.enqueueOperation(P14OperationEntity(Uuid.random().toString(),notificationId,"INTERACTION",response))
        P14RemoteWork.enqueue(context)
    }
    suspend fun processPendingActive():Boolean {
        val c=P14RuntimeCredentialStore(context).activeConnection() ?: return false
        processPending(c); refresh(c); return true
    }
    private suspend fun processPending(c:P14RuntimeConnection)=withContext(Dispatchers.IO) {
        for(op in dao.pendingOperations()) {
            dao.setOperationState(op.operationId,"IN_FLIGHT")
            try {
                when(op.type) {
                    "STATE" -> {
                        val current=dao.notification(op.notificationId) ?: run { refresh(c); dao.notification(op.notificationId) }
                            ?: error("Notification disappeared")
                        val cmd=StateCommand(op.operationId,current.revision,op.payload)
                        val body=post(c,"notifications/${op.notificationId}/state",json.encodeToString(cmd))
                        val dto:P14NotificationDto=json.decodeFromString(body)
                        dao.upsertNotifications(listOf(dto.entity()))
                    }
                    "INTERACTION" -> {
                        val cmd=InteractionCommand(op.operationId,op.payload)
                        post(c,"notifications/${op.notificationId}/interaction-response",json.encodeToString(cmd))
                        fetchInteraction(c,op.notificationId)
                    }
                    else -> error("Unknown P14 operation ${op.type}")
                }
                dao.setOperationState(op.operationId,"ACKED")
            } catch(e:P14RemoteHttpException) {
                if(e.status==409) { runCatching{refresh(c)}; dao.setOperationState(op.operationId,"ACKED") }
                else dao.setOperationState(op.operationId,"RETRY")
            } catch(_:Throwable) { dao.setOperationState(op.operationId,"RETRY") }
        }
    }

    suspend fun interaction(notificationId:String):P14InteractionEntity?=withContext(Dispatchers.IO){
        val n=dao.notification(notificationId) ?: return@withContext null
        n.interactionId?.let { dao.interaction(it) }
    }
    suspend fun refreshInteraction(provider:ProviderSetting.OpenAI,notificationId:String)=fetchInteraction(access.connection(provider),notificationId)
    private suspend fun fetchInteraction(c:P14RuntimeConnection,notificationId:String):P14InteractionEntity {
        val text=get(c,"notifications/$notificationId/interaction-context")
        val ctx=json.decodeFromString<P14InteractionContext>(text)
        val i=ctx.interaction
        val entity=P14InteractionEntity(i.interactionId,i.conversationId,notificationId,i.promptText,json.encodeToString(i.options),i.status,i.response,ctx.actionable)
        dao.upsertInteraction(entity); return entity
    }
    suspend fun revokeDevice(provider:ProviderSetting.OpenAI)=access.revoke(provider)

    private fun P14NotificationDto.entity()=P14NotificationEntity(notificationId,conversationId,title,body,severity,interactionId,state,revision,expiresAt,createdAt,updatedAt)
    private fun get(c:P14RuntimeConnection,path:String)=execute(Request.Builder().url("${c.baseUrl}/${path.trimStart('/')}").header("Authorization","Bearer ${c.bearerToken}").get().build())
    private fun post(c:P14RuntimeConnection,path:String,payload:String)=execute(Request.Builder().url("${c.baseUrl}/${path.trimStart('/')}").header("Authorization","Bearer ${c.bearerToken}").post(payload.toRequestBody(JSON)).build())
    private fun execute(req:Request)=http.newCall(req).execute().use { r -> val t=r.body?.string().orEmpty(); if(!r.isSuccessful) throw P14RemoteHttpException(r.code,t.take(800)); t }
    @Serializable private data class StateCommand(@SerialName("client_operation_id") val id:String,@SerialName("base_revision") val revision:Int,val state:String)
    @Serializable private data class InteractionCommand(@SerialName("client_operation_id") val id:String,val response:String)
    companion object { private val JSON="application/json; charset=utf-8".toMediaType() }
}
