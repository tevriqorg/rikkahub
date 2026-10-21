package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.uuid.Uuid

@Serializable internal data class P14WorkItem(
    val source:String,
    @SerialName("source_id") val sourceId:String?=null,
    @SerialName("entry_id") val entryId:String,
    @SerialName("conversation_id") val conversationId:String?=null,
    val kind:String,
    val status:String,
    val phase:String?=null,
    @SerialName("progress_percent") val progressPercent:Int?=null,
    @SerialName("interaction_id") val interactionId:String?=null,
    @SerialName("result_text") val resultText:String?=null,
    @SerialName("destination_ref") val destinationRef:String?=null,
    @SerialName("body_text") val bodyText:String?=null,
)

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

    suspend fun workItems(view:String,offset:Int=0,limit:Int=20):List<P14WorkItem> = withContext(Dispatchers.IO) {
        require(view in setOf("todo","idea","research","execute"))
        val connection=P14RuntimeCredentialStore(context).activeConnection() ?: return@withContext emptyList()
        val url=connection.baseUrl.toHttpUrl().newBuilder().addPathSegments("views/$view/work")
            .addQueryParameter("offset",offset.toString()).addQueryParameter("limit",limit.toString()).build()
        val raw=execute(Request.Builder().url(url).header("Authorization","Bearer ${connection.bearerToken}").get().build())
        json.decodeFromString<List<P14WorkItem>>(raw)
    }

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

    suspend fun manifest(provider:ProviderSetting.OpenAI):P14EffectiveManifest = withContext(Dispatchers.IO) { manifest(access.connection(provider)) }
    suspend fun manifestActive():P14EffectiveManifest = withContext(Dispatchers.IO) {
        val c=P14RuntimeCredentialStore(context).activeConnection() ?: return@withContext fallbackP14Manifest()
        manifest(c)
    }
    private fun manifest(c:P14RuntimeConnection):P14EffectiveManifest = P14ManifestPolicy.load(context,c,http)

    suspend fun notification(notificationId:String):P14NotificationEntity?=withContext(Dispatchers.IO){ dao.notification(notificationId) }
    suspend fun setConversationStateActive(conversationId:String,baseRevision:Int,pinned:Boolean?=null,archived:Boolean?=null)=withContext(Dispatchers.IO) {
        val c=P14RuntimeCredentialStore(context).activeConnection() ?: error("P14 device is not connected")
        val payload=buildJsonObject {
            put("client_operation_id",Uuid.random().toString()); put("base_revision",baseRevision)
            pinned?.let { put("pinned",it) }; archived?.let { put("archived",it) }
        }.toString()
        post(c,"conversations/$conversationId/state",payload)
    }

    /** P14 Quick Action is queued with its stable operation/action identity before any HTTP. */
    suspend fun quickActionActive(conversationId:String,actionType:String):String=withContext(Dispatchers.IO) {
        val c=P14RuntimeCredentialStore(context).activeConnection() ?: error("P14 device is not connected")
        val manifest=manifest(c)
        require(actionType in manifest.quickActionTypes) { "Quick Action is not enabled by the current P14 Manifest" }
        val operationId=Uuid.random().toString()
        val actionId=Uuid.random().toString()
        val payload=buildJsonObject {
            put("client_operation_id",operationId); put("action_id",actionId); put("action_type",actionType)
            put("payload",buildJsonObject {})
        }.toString()
        dao.enqueueOperation(P14OperationEntity(operationId,"", "QUICK_ACTION",payload,
            conversationId=conversationId,actionId=actionId))
        P14RemoteWork.enqueue(context)
        operationId
    }

    /** Offline-first Action Card decision; user approval does not itself execute tools. */
    suspend fun queueActionDecision(actionId:String,decision:String,supplementText:String?=null):String {
        require(decision in setOf("confirm","supplement","skip","cancel"))
        require((decision=="supplement") == !supplementText.isNullOrBlank())
        val operationId=Uuid.random().toString()
        val payload=buildJsonObject {
            put("client_operation_id",operationId)
            put("decision",decision)
            if(decision=="supplement") put("supplement_text",supplementText!!.trim())
        }.toString()
        dao.enqueueOperation(P14OperationEntity(operationId,"","ACTION_DECISION",payload,actionId=actionId))
        P14RemoteWork.enqueue(context)
        return operationId
    }

    suspend fun queueObservationContextLink(entryId:String,observationId:String):String {
        require(entryId.isNotBlank() && observationId.isNotBlank())
        val operationId=Uuid.random().toString()
        val payload=buildJsonObject { put("client_operation_id",operationId) }.toString()
        dao.enqueueOperation(P14OperationEntity(operationId,"","LINK_OBSERVATION",payload,
            conversationId=entryId,actionId=observationId))
        P14RemoteWork.enqueue(context)
        return operationId
    }

    suspend fun queueState(notificationId:String,state:String) {
        dao.enqueueOperation(P14OperationEntity(Uuid.random().toString(),notificationId,"STATE",state))
        P14RemoteWork.enqueue(context)
    }
    suspend fun queueInteraction(notificationId:String,response:String) {
        val normalized=response.trim(); require(normalized.isNotEmpty())
        val notification=requireNotNull(dao.notification(notificationId)) { "Unknown P14 notification" }
        val interactionId=requireNotNull(notification.interactionId) { "Notification is not actionable" }
        val interaction=requireNotNull(dao.interaction(interactionId)) { "Interaction context is not available" }
        require(interaction.actionable && interaction.status=="PENDING") { "Interaction is no longer actionable" }
        db.withTransaction {
            check(dao.markInteractionResponded(interactionId,normalized)==1) { "Interaction was already answered" }
            dao.enqueueOperation(P14OperationEntity(Uuid.random().toString(),notificationId,"INTERACTION",normalized))
        }
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
                    "LINK_OBSERVATION" -> {
                        val entryId=requireNotNull(op.conversationId)
                        val observationId=requireNotNull(op.actionId)
                        post(c,"entries/$entryId/context/observations/$observationId",op.payload)
                    }
                    "ACTION_DECISION" -> {
                        val actionId=requireNotNull(op.actionId) { "Decision missing Action ID" }
                        post(c,"actions/$actionId/decision",op.payload)
                    }
                    "QUICK_ACTION" -> {
                        val conversationId=requireNotNull(op.conversationId) { "Quick Action missing durable conversation" }
                        // The original serialized payload preserves action_id/client_operation_id on retry.
                        post(c,"conversations/$conversationId/quick-actions",op.payload)
                    }
                    else -> error("Unknown P14 operation ${op.type}")
                }
                dao.setOperationState(op.operationId,"ACKED")
            } catch(e:P14RemoteHttpException) {
                if(e.status==409 && op.type in setOf("ACTION_DECISION","LINK_OBSERVATION","INTERACTION")) {
                    // A competing device or an expired Interaction can reject this operation.
                    // Never misreport that conflicting decision as ACKED.
                    dao.setOperationState(op.operationId,"CONFLICT")
                    if(op.type=="INTERACTION") runCatching { refresh(c) }
                } else if(e.status==409 && op.type != "QUICK_ACTION") {
                    runCatching{refresh(c)}; dao.setOperationState(op.operationId,"ACKED")
                } else dao.setOperationState(op.operationId,"RETRY")
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
