package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.OffsetDateTime
import java.time.Instant
import java.net.URLConnection
import java.util.UUID
import kotlin.uuid.Uuid

internal fun durableP14AudioKind(part:UIMessagePart.Audio):String =
    if (part.metadata?.get("p14_media_role")?.jsonPrimitive?.contentOrNull=="voice_message") "VOICE" else "AUDIO"

internal data class P14EntryDirectoryItem(val entryId:String, val conversationId:String, val bodyText:String, val pinned:Boolean, val archived:Boolean)

internal data class P14AcceptedMutation(
    val entryId: String,
    val conversationId: String,
    val messageId: String,
    val clientOperationId: String,
)

@Serializable
private data class P14SyncEventDto(
    val event_id: String,
    val sync_seq: Long,
    val event_type: String,
    val entity_type: String,
    val entity_id: String,
    val caused_by_operation_id: String? = null,
    val occurred_at: String,
    val payload: JsonObject,
)

@Serializable
private data class P14SyncReplayDto(
    val events: List<P14SyncEventDto>,
    val next_after: Long,
    val watermark: Long,
    val has_more: Boolean,
)

/**
 * Backward-compatible read projection for drafts already materialized by earlier APKs.
 * New authoritative finalization deletes the exact draft inside the sync transaction.
 * This only hides identical superseded legacy STREAMING text; it never alters Room data
 * or merges two distinct final messages produced by Hermes.
 */
internal fun p14VisibleEntryMessages(messages:List<P14ProductMessageEntity>):List<P14ProductMessageEntity> {
    val finished = messages.filter {
        it.role == "assistant" && it.deliveryState == "SYNCED" && !it.tombstone && it.bodyText.isNotBlank()
    }.groupBy { it.conversationId to it.bodyText }
    return messages.filter { row ->
        if (row.role != "assistant" || row.deliveryState != "STREAMING" || row.bodyText.isBlank()) {
            true
        } else {
            val candidates = finished[row.conversationId to row.bodyText].orEmpty()
            candidates.none { final ->
                final.createdAtEpochMs >= row.createdAtEpochMs &&
                    final.createdAtEpochMs - row.createdAtEpochMs <= 3_600_000L
            }
        }
    }
}

internal class P14ProductRepository(
    context: Context,
    private val http: OkHttpClient,
    private val database: P14ProductDatabase = P14ProductDatabase.get(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val dao = database.dao()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val credentialStore = P14RuntimeCredentialStore(appContext)

    val entries: Flow<List<P14EntryEntity>> = dao.observeEntries()
    private val sourceIndex = appContext.getSharedPreferences("p14-entry-source-index-v1", Context.MODE_PRIVATE)
    private val sourceIndexVersion = MutableStateFlow(0L)
    private val editLock = Mutex()
    fun inboxRows(archived:Boolean=false,limit:Int=60,query:String="",view:String=""):Flow<List<P14InboxRow>> =
        combine(dao.observeInboxRows(archived,limit,query,view),sourceIndexVersion) { rows,_ ->
            rows.map { row -> verifiedSource(row.conversationId)?.let { row.copy(bodyText=it.bodyText) } ?: row }
        }
    fun sourceMessage(conversationId:String):Flow<P14ProductMessageEntity?> =
        combine(dao.observeFirstUser(conversationId),sourceIndexVersion) { _,_ -> verifiedSource(conversationId) }
    fun editConflicts(conversationId:String) = dao.observeConflicts(conversationId)
    private fun indexSource(entryId:String,messageId:String) {
        if(sourceIndex.getString(entryId,null)!=messageId) {
            check(sourceIndex.edit().putString(entryId,messageId).commit())
            sourceIndexVersion.value++
        }
    }
    private suspend fun verifiedSource(conversationId:String):P14ProductMessageEntity? {
        val conversation=dao.conversation(conversationId) ?: return null
        val id=dao.captureOperation(conversation.entryId)?.messageId ?: sourceIndex.getString(conversation.entryId,null) ?: return null
        return dao.message(id)?.takeIf { it.conversationId==conversationId && it.role=="user" }
    }
    /** Discover the original through authoritative create events or complete server history, never a guessed recent user turn. */
    suspend fun ensureEntrySource(conversationId:String):P14ProductMessageEntity? = withContext(Dispatchers.IO) {
        verifiedSource(conversationId)?.let { return@withContext it }
        val conversation=dao.conversation(conversationId) ?: return@withContext null
        if(credentialStore.activeConnection()==null) return@withContext null
        var offset=0
        while(true) {
            val count=loadEntryHistory(conversation.entryId,offset,100)
            offset+=count
            if(count<100) break
        }
        val source=dao.listMessages(conversationId).firstOrNull { it.role=="user" } ?: return@withContext null
        indexSource(conversation.entryId,source.messageId)
        source
    }
    /** A revision of the source Message; Entry.body_text and CREATE payload remain immutable evidence. */
    suspend fun acceptEntryRevision(conversationId:String,text:String,expectedRevision:Int?=null):P14AcceptedMutation = withContext(Dispatchers.IO) {
        editLock.withLock {
            val normalized=text.trim();require(normalized.isNotEmpty()) { "记录内容不能为空" }
            val source=verifiedSource(conversationId) ?: error("原始记录尚未恢复，编辑草稿已保留")
            require(!source.tombstone) { "已删除的来源不能修改" }
            val conversation=requireNotNull(dao.conversation(conversationId))
            val operationId=UUID.randomUUID().toString()
            database.withTransaction {
                val current=requireNotNull(dao.message(source.messageId))
                if(current.bodyText==normalized) return@withTransaction
                require(expectedRevision==null || current.revision==expectedRevision) { "记录已被其他设备修改，编辑草稿已保留" }
                dao.upsertMessage(current.copy(bodyText=normalized,revision=current.revision+1,deliveryState="EDIT_PENDING"))
                dao.insertOutbox(P14ChatOutboxEntity(operationId,"EDIT_SOURCE_MESSAGE",source.messageId,conversationId,
                    conversation.entryId,conversation.externalSessionId,buildJsonObject {
                        put("text",normalized);put("base_revision",current.revision)
                    }.toString(),createdAtEpochMs=System.currentTimeMillis()))
            }
            P14AcceptedMutation(conversation.entryId,conversationId,source.messageId,operationId)
        }
    }
    /** Existing pin/archive protocol, now durably queued before showing the state. */
    suspend fun acceptOrganization(conversationId:String,pinned:Boolean?=null,archived:Boolean?=null) = withContext(Dispatchers.IO) {
        editLock.withLock {
            database.withTransaction {
                val current=requireNotNull(dao.conversation(conversationId));val operationId=UUID.randomUUID().toString()
                dao.upsertConversation(current.copy(pinned=pinned ?: current.pinned,archived=archived ?: current.archived,stateRevision=current.stateRevision+1))
                dao.insertOutbox(P14ChatOutboxEntity(operationId,"SET_CONVERSATION_STATE","state:$conversationId",conversationId,
                    current.entryId,current.externalSessionId,buildJsonObject {
                        put("base_revision",current.stateRevision);pinned?.let { put("pinned",it) };archived?.let { put("archived",it) }
                    }.toString(),createdAtEpochMs=System.currentTimeMillis()))
            }
        }
    }

    /** Explicit user resolution, never silent last-writer-wins. Original Capture remains unchanged. */
    suspend fun resolveConflicts(conversationId:String,keepLocal:Boolean) = withContext(Dispatchers.IO) {
        val connection=credentialStore.activeConnection() ?: error("请连接服务端后处理冲突")
        val snapshot=verifiedSource(conversationId)
        val conversation=requireNotNull(dao.conversation(conversationId))
        val edits=snapshot?.let {dao.messageEdits(it.messageId)}.orEmpty().filter {it.state=="CONFLICT"}
        if(edits.isNotEmpty()&&snapshot!=null) {
            val state=json.parseToJsonElement(get(connection,"${connection.baseUrl}/conversations/$conversationId/messages/${snapshot.messageId}")).jsonObject
            check(state["deleted"]?.jsonPrimitive?.booleanOrNull!=true) {"云端来源已删除；本机修订仍然保留"}
            val base=state["revision"]?.jsonPrimitive?.intOrNull ?: error("服务端未返回修订号")
            val remoteText=state.stringOrEmpty("body_text")
            editLock.withLock {database.withTransaction {
                val current=requireNotNull(dao.message(snapshot.messageId))
                check(current.bodyText==snapshot.bodyText&&current.revision==snapshot.revision) {"本机内容已变化，请重新处理冲突"}
                edits.forEach {dao.setMutationState(it.clientOperationId,"SUPERSEDED")}
                if(keepLocal) {
                    val id=UUID.randomUUID().toString()
                    dao.upsertMessage(current.copy(revision=base+1,deliveryState="EDIT_PENDING"))
                    dao.insertOutbox(P14ChatOutboxEntity(id,"EDIT_SOURCE_MESSAGE",current.messageId,conversationId,conversation.entryId,conversation.externalSessionId,
                        buildJsonObject {put("base_revision",base);put("text",current.bodyText)}.toString(),createdAtEpochMs=System.currentTimeMillis()))
                } else dao.upsertMessage(current.copy(bodyText=remoteText,revision=base,deliveryState="SYNCED"))
            }}
        }
        val states=dao.pendingStateConflicts(conversationId)
        if(states.isNotEmpty()) {
            val state=json.parseToJsonElement(get(connection,"${connection.baseUrl}/conversations/$conversationId/state")).jsonObject
            val base=state["revision"]?.jsonPrimitive?.intOrNull ?: error("服务端未返回状态版本")
            editLock.withLock {database.withTransaction {
                val current=requireNotNull(dao.conversation(conversationId))
                check(current.stateRevision==conversation.stateRevision) {"本机整理状态已变化，请重试"}
                states.forEach {dao.setMutationState(it.clientOperationId,"SUPERSEDED")}
                if(keepLocal) {
                    val id=UUID.randomUUID().toString()
                    dao.upsertConversation(current.copy(stateRevision=base+1))
                    dao.insertOutbox(P14ChatOutboxEntity(id,"SET_CONVERSATION_STATE","state:$conversationId",conversationId,current.entryId,current.externalSessionId,
                        buildJsonObject {put("base_revision",base);put("pinned",current.pinned);put("archived",current.archived)}.toString(),createdAtEpochMs=System.currentTimeMillis()))
                } else dao.upsertConversation(current.copy(pinned=state["pinned"]?.jsonPrimitive?.booleanOrNull ?: current.pinned,
                    archived=state["archived"]?.jsonPrimitive?.booleanOrNull ?: current.archived,stateRevision=base))
            }}
        }
    }

    /** A first-page Room projection alone does not prove server history is exhausted. */
    fun inboxHasOlderServerEntries():Boolean = bootstrapPrefs.getBoolean("more",false)
    suspend fun directoryPage(offset:Int,limit:Int=20,view:String="chat"):List<P14EntryDirectoryItem> = withContext(Dispatchers.IO) {
        directoryMutex.withLock {
            require(offset>=0 && limit in 1..100)
            require(view in setOf("chat","todo","idea","research","execute"))
            fun coverageAfter():Long? = if(bootstrapPrefs.getBoolean("more",false)) {
                // Room stores milliseconds, whereas the server keyset may contain microseconds.
                // Hold the entire boundary millisecond back until it is completely covered.
                val boundary=requireNotNull(bootstrapPrefs.getString("before_time",null)) { "Bootstrap coverage boundary absent" }
                OffsetDateTime.parse(boundary).toInstant().toEpochMilli()
            } else null
            suspend fun page() = if(view=="chat") dao.coveredDirectoryPage(limit,offset,coverageAfter())
                else dao.directoryPageForView(view,limit,offset)
            var raw=page()
            while(view=="chat" && raw.size<limit && bootstrapPrefs.getBoolean("more",false)) {
                val connection=credentialStore.activeConnection() ?: error("Older Entry history requires an active connection")
                // Failure is surfaced to the caller; a partial page must not masquerade as EOF.
                bootstrapNextPage(connection)
                raw=page()
            }
            raw.map { c -> P14EntryDirectoryItem(c.entryId,c.conversationId,dao.entry(c.entryId)?.bodyText.orEmpty(),c.pinned,c.archived) }
        }
    }
    val pendingOutboxCount: Flow<Int> = dao.observePendingOutboxCount()
    fun actions(conversationId:String):Flow<List<P14ActionEntity>> = dao.observeActions(conversationId)
    fun entryViews(entryId:String):Flow<List<P14EntryViewEntity>> = dao.observeEntryViews(entryId)
    suspend fun assignEntryView(entryId:String,view:String,enabled:Boolean):String = withContext(Dispatchers.IO) {
        require(view in setOf("todo","idea","research","execute"))
        require(dao.entry(entryId)!=null) { "Unknown Entry" }
        val id=UUID.randomUUID().toString()
        database.withTransaction {
            dao.upsertEntryView(P14EntryViewEntity(entryId,view,enabled))
            dao.insertViewOutbox(P14ViewOutboxEntity(id,entryId,view,enabled))
        }
        id
    }
    fun entryFlow(entryId: String): Flow<P14EntryEntity?> = dao.observeEntry(entryId)
    fun messages(conversationId:String,limit:Int=50):Flow<List<P14ProductMessageEntity>> = dao.observeMessagesPaged(conversationId,limit)
    fun conversationFlow(conversationId: String): Flow<P14ProductConversationEntity?> = dao.observeConversation(conversationId)
    fun attachments(conversationId:String,limit:Int=50):Flow<List<P14ProductAttachmentEntity>> = dao.observeEntryAttachments(conversationId)
    suspend fun entry(entryId: String) = dao.entry(entryId)
    suspend fun conversation(conversationId: String) = dao.conversation(conversationId)
    suspend fun conversationForId(conversationId:String) = dao.conversation(conversationId)

    /** Resolve a cold notification target without advancing the global bootstrap or replay cursor. */
    suspend fun ensureConversationForId(conversationId:String):P14ProductConversationEntity? = withContext(Dispatchers.IO) {
        dao.conversation(conversationId)?.let { return@withContext it }
        val connection=credentialStore.activeConnection() ?: return@withContext null
        val url=connection.baseUrl.toHttpUrl().newBuilder().addPathSegments("bootstrap/entries")
            .addQueryParameter("conversation_id",conversationId).addQueryParameter("limit","1").build()
        val root=json.parseToJsonElement(get(connection,url.toString())).jsonObject
        val items=root["items"]?.jsonArray ?: error("Entry lookup list absent")
        val item=items.singleOrNull()?.jsonObject ?: return@withContext null
        check(item.requiredString("conversation_id")==conversationId) { "Entry lookup target mismatch" }
        database.withTransaction { seedBootstrapEntry(item) }
        dao.conversation(conversationId)
    }
    suspend fun conversationForExternal(externalSessionId: String) = dao.conversationByExternalSession(externalSessionId)
    suspend fun message(messageId: String) = dao.message(messageId)

    suspend fun acceptRikkaMessage(
        externalSessionId: String,
        localConversationId: String,
        message: UIMessage,
        assistantId: String?,
        modelId: String?,
        deferAi: Boolean = false,
        initialViews: Set<String> = emptySet(),
    ): P14AcceptedMutation = withContext(Dispatchers.IO) {
        require(message.role == MessageRole.USER) { "P14 local acceptance only accepts user messages" }
        val ids = compatIds(externalSessionId)
        val existing = dao.conversationByExternalSession(externalSessionId)
        // Retrying the exact same saved draft after a crash must not reset ACKED work,
        // overwrite a synced message or create a second Entry.
        dao.message(message.id.toString())?.let { saved ->
            require(saved.conversationId == ids.conversationId) { "Message identity reused across conversations" }
            return@withContext P14AcceptedMutation(ids.entryId,ids.conversationId,message.id.toString(),"rk-op-${message.id}")
        }
        val now = System.currentTimeMillis()
        val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.trim()
        val attachmentRows = productAttachments(message, ids.conversationId, now)
        require(text.isNotBlank() || attachmentRows.isNotEmpty()) { "P14 message has no user content" }
        val operationId = "rk-op-${message.id}"
        val opType = if (existing == null) "CREATE_ENTRY_WITH_MESSAGE" else "SEND_MESSAGE"
        val reply = message.parts.asSequence().mapNotNull { it.metadata?.get("p14_reply") as? JsonObject }.firstOrNull()
        val payload = buildJsonObject {
            put("text", text)
            if (existing == null && deferAi) put("defer_ai", true)
            reply?.get("message_id")?.jsonPrimitive?.contentOrNull?.let { put("reply_to_message_id", it) }
            (reply?.get("quote") as? JsonObject)?.let { put("quote", it) }
        }.toString()

        database.withTransaction {
            if (existing == null) {
                dao.upsertEntry(P14EntryEntity(ids.entryId, text, now))
                dao.upsertConversation(P14ProductConversationEntity(ids.conversationId, ids.entryId, externalSessionId, localConversationId, now, assistantId = assistantId, modelId = modelId))
            } else if (existing.localConversationId == null || existing.assistantId == null || existing.modelId == null) {
                dao.upsertConversation(existing.copy(
                    localConversationId = existing.localConversationId ?: localConversationId,
                    assistantId = existing.assistantId ?: assistantId,
                    modelId = existing.modelId ?: modelId,
                ))
            }
            dao.upsertMessage(
                P14ProductMessageEntity(
                    messageId = message.id.toString(),
                    conversationId = ids.conversationId,
                    role = "user",
                    bodyText = text,
                    deliveryState = "LOCAL_PENDING",
                    createdAtEpochMs = now,
                )
            )
            for (attachment in attachmentRows) dao.upsertAttachment(attachment)
            dao.insertOutbox(
                P14ChatOutboxEntity(
                    clientOperationId = operationId,
                    operationType = opType,
                    messageId = message.id.toString(),
                    conversationId = ids.conversationId,
                    entryId = ids.entryId,
                    externalSessionId = externalSessionId,
                    payloadJson = payload,
                    createdAtEpochMs = now,
                )
            )
            if(existing==null) initialViews.filter {it in setOf("todo","idea","research","execute")}.forEach {view ->
                dao.upsertEntryView(P14EntryViewEntity(ids.entryId,view,true))
                val viewOp=UUID.nameUUIDFromBytes("$operationId:view:$view".toByteArray()).toString()
                dao.insertViewOutbox(P14ViewOutboxEntity(viewOp,ids.entryId,view,true))
            }
        }
        P14AcceptedMutation(ids.entryId, ids.conversationId, message.id.toString(), operationId)
    }

    suspend fun acceptEntryContinuation(conversationId: String, text: String): P14AcceptedMutation = withContext(Dispatchers.IO) {
        val conversation = requireNotNull(dao.conversation(conversationId)) { "Unknown P14 conversation" }
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Message cannot be blank" }
        val messageId = UUID.randomUUID().toString()
        val operationId = "rk-op-$messageId"
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.upsertMessage(P14ProductMessageEntity(messageId, conversationId, "user", normalized, deliveryState = "LOCAL_PENDING", createdAtEpochMs = now))
            dao.insertOutbox(
                P14ChatOutboxEntity(
                    operationId, "SEND_MESSAGE", messageId, conversationId, conversation.entryId,
                    conversation.externalSessionId, buildJsonObject {
                        put("text", normalized)
                        verifiedSource(conversationId)?.takeIf { it.revision>1 }?.let { source ->
                            put("reply_to_message_id",source.messageId)
                            put("quote",buildJsonObject { put("text",source.bodyText) })
                        }
                    }.toString(), createdAtEpochMs = now,
                )
            )
        }
        P14AcceptedMutation(conversation.entryId, conversationId, messageId, operationId)
    }

    suspend fun syncAndFlushActive(): Boolean = withContext(Dispatchers.IO) {
        val connection = credentialStore.activeConnection() ?: return@withContext false
        val manifest = P14ManifestPolicy.load(appContext, connection, http)
        P14ManifestPolicy.requireChat(manifest)
        replay(connection)
        P14DurableAttachmentUploader(appContext, http).uploadPending(connection, manifest)
        flush(connection, manifest)
        flushObservations(connection)
        flushViewAssignments(connection)
        replay(connection)
        true
    }

    /** Explicit policy-gated Location Observation: never a user Entry or Inbox Notification. */
    suspend fun acceptLocationObservation(latitude:Double,longitude:Double,accuracyM:Double?,permissionState:String="foreground"):String = withContext(Dispatchers.IO) {
        require(P14ProfileStore(appContext).locationEnabled()) { "Location Observation is disabled by Profile" }
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        require(permissionState in setOf("foreground","background"))
        val profile=P14ProfileStore(appContext)
        val (profileId,profileVersion)=profile.profileIdentity()
        val id=UUID.randomUUID().toString()
        database.withTransaction {
            dao.upsertObservation(P14ObservationEntity(id,"LOCATION",latitude,longitude,accuracyM,System.currentTimeMillis(),
                profileId=profileId,profileVersion=profileVersion,
                sourceDeviceId=credentialStore.deviceId(),permissionState=permissionState,
                policyMode="significant-change",policyMinDistanceM=profile.locationMinDistanceMeters().toDouble(),
                retentionDays=profile.retentionDays()))
            dao.insertObservationOutbox(P14ObservationOutboxEntity("obs-op-$id",id))
        }
        id
    }

    suspend fun recentObservations():List<P14ObservationEntity> = dao.recentObservations()
    suspend fun linkedObservationIds(entryId:String):List<String> = withContext(Dispatchers.IO) {
        val c=credentialStore.activeConnection() ?: return@withContext emptyList()
        val text=get(c,"${c.baseUrl}/entries/$entryId/context/observations")
        json.parseToJsonElement(text).jsonArray.map { it.jsonObject.requiredString("observation_id") }
    }

    suspend fun currentCursor(): Long = dao.syncState(SYNC_SCOPE)?.lastSyncSeq ?: 0L

    suspend fun replayActive(): Boolean = withContext(Dispatchers.IO) {
        val connection = credentialStore.activeConnection() ?: return@withContext false
        replay(connection)
        true
    }

    suspend fun replay(connection: P14RuntimeConnection): Long {
        var cursor = dao.syncState(SYNC_SCOPE)?.lastSyncSeq ?: 0L
        if (cursor == 0L && dao.pendingOutbox().isEmpty()) {
            // New device: bound first Entry index and establish a consistent replay watermark.
            // Older objects remain server-authoritative and are fetched lazily by Entry ID.
            runCatching { bootstrapFirstPage(connection) }
            cursor = dao.syncState(SYNC_SCOPE)?.lastSyncSeq ?: 0L
        }
        while (true) {
            val url = connection.baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("sync/events")
                .addQueryParameter("after", cursor.toString())
                .addQueryParameter("limit", "500")
                .build()
            val replay = json.decodeFromString<P14SyncReplayDto>(get(connection, url.toString()))
            cursor = applyEvents(replay.events)
            if (!replay.has_more) return cursor
        }
    }

    private val bootstrapPrefs get() = appContext.getSharedPreferences("p14-entry-bootstrap",Context.MODE_PRIVATE)

    private suspend fun bootstrapFirstPage(connection:P14RuntimeConnection) {
        val url=connection.baseUrl.toHttpUrl().newBuilder().addPathSegments("bootstrap/entries")
            .addQueryParameter("limit","50").build()
        val root=json.parseToJsonElement(get(connection,url.toString())).jsonObject
        val watermark=root["watermark"]?.jsonPrimitive?.longOrNull ?: error("Bootstrap watermark absent")
        val items=root["items"]?.jsonArray ?: error("Bootstrap list absent")
        // Save the older-page locator FIRST. A crash between Room commit and prefs
        // write would strand all older Entries behind a committed replay watermark.
        // If Room later fails, its cursor remains zero and bootstrap can safely retry.
        check(bootstrapPrefs.edit()
            .putString("snapshot",root["snapshot_at"]?.jsonPrimitive?.contentOrNull)
            .putString("before_time",root["next_before_created_at"]?.jsonPrimitive?.contentOrNull)
            .putString("before_id",root["next_before_entry_id"]?.jsonPrimitive?.contentOrNull)
            .putBoolean("more",root["has_more"]?.jsonPrimitive?.contentOrNull=="true").commit())
        database.withTransaction {
            for(raw in items) seedBootstrapEntry(raw.jsonObject)
            dao.upsertSyncState(P14ProductSyncStateEntity(SYNC_SCOPE,watermark))
        }
    }

    private suspend fun seedBootstrapEntry(item:JsonObject) {
        val entryId=item.requiredString("entry_id")
        val convoId=item.requiredString("conversation_id")
        val created=item.epochMs("created_at",Instant.now().toString())
        val previous=dao.entry(entryId)
        if(previous==null) dao.upsertEntry(P14EntryEntity(entryId,item.stringOrEmpty("body_text"),created))
        if(dao.conversation(convoId)==null)
            dao.upsertConversation(P14ProductConversationEntity(convoId,entryId,"remote-bootstrap:$convoId",null,created))
    }

    /** Keyset paging of older server-authoritative Entries; no historical event flood. */
    private suspend fun bootstrapNextPage(connection:P14RuntimeConnection):Boolean {
        val before=requireNotNull(bootstrapPrefs.getString("before_time",null)) { "Bootstrap boundary absent" }
        val beforeId=requireNotNull(bootstrapPrefs.getString("before_id",null)) { "Bootstrap boundary identity absent" }
        val anchor=requireNotNull(bootstrapPrefs.getString("snapshot",null)) { "Bootstrap snapshot absent" }
        val url=connection.baseUrl.toHttpUrl().newBuilder().addPathSegments("bootstrap/entries")
            .addQueryParameter("limit","50").addQueryParameter("snapshot_at",anchor)
            .addQueryParameter("before_created_at",before).addQueryParameter("before_entry_id",beforeId).build()
        val root=json.parseToJsonElement(get(connection,url.toString())).jsonObject
        val entries=root["items"]?.jsonArray ?: error("Bootstrap list absent")
        check(entries.size<=50) { "Bootstrap page exceeds the requested bound" }
        val responseAnchor=root["snapshot_at"]?.jsonPrimitive?.contentOrNull ?: error("Bootstrap snapshot absent")
        check(OffsetDateTime.parse(responseAnchor).toInstant()==OffsetDateTime.parse(anchor).toInstant()) { "Bootstrap snapshot changed" }
        val more=root["has_more"]?.jsonPrimitive?.contentOrNull?.let {
            when(it) { "true" -> true; "false" -> false; else -> error("Invalid bootstrap continuation") }
        } ?: error("Bootstrap continuation absent")
        val nextTime=root["next_before_created_at"]?.jsonPrimitive?.contentOrNull
        val nextId=root["next_before_entry_id"]?.jsonPrimitive?.contentOrNull
        if(more) {
            check(entries.isNotEmpty()) { "Bootstrap continuation made no progress" }
            val next=OffsetDateTime.parse(requireNotNull(nextTime)).toInstant()
            val previous=OffsetDateTime.parse(before).toInstant()
            check(next<previous || (next==previous && requireNotNull(nextId)<beforeId)) { "Bootstrap continuation did not move backwards" }
        }
        database.withTransaction { for(item in entries) seedBootstrapEntry(item.jsonObject) }
        check(bootstrapPrefs.edit().putString("before_time",nextTime).putString("before_id",nextId)
            .putBoolean("more",more).commit()) { "Bootstrap continuation was not persisted" }
        return entries.isNotEmpty()
    }

    /** Lazily materialize a single Entry's messages, without changing the authoritative cursor. */
    suspend fun seedWorkEntry(entryId:String,conversationId:String,bodyText:String) = database.withTransaction {
        if(dao.entry(entryId)==null) dao.upsertEntry(P14EntryEntity(entryId,bodyText,System.currentTimeMillis()))
        if(dao.conversation(conversationId)==null)
            dao.upsertConversation(P14ProductConversationEntity(conversationId,entryId,"remote-bootstrap:$conversationId",null,System.currentTimeMillis()))
    }

    /** Load current Action and View cards for an older Entry without zero-cursor full replay. */
    suspend fun hydrateEntryState(entryId:String,conversationId:String) = withContext(Dispatchers.IO) {
        val c=credentialStore.activeConnection() ?: return@withContext
        val actions=json.parseToJsonElement(get(c,"${c.baseUrl}/conversations/$conversationId/actions")).jsonArray
        val views=json.parseToJsonElement(get(c,"${c.baseUrl}/entries/$entryId/views")).jsonArray
        database.withTransaction {
            for (item in actions) {
                val a=item.jsonObject
                val id=a.requiredString("action_id")
                val prior=dao.action(id)
                dao.upsertAction(P14ActionEntity(id,conversationId,a.stringOrEmpty("action_type"),
                    a.stringOrEmpty("status"),a["result_text"]?.jsonPrimitive?.contentOrNull,
                    a["destination_ref"]?.jsonPrimitive?.contentOrNull,
                    prior?.createdAtEpochMs?:System.currentTimeMillis()))
            }
            for (item in views) {
                val view=item.jsonPrimitive.contentOrNull ?: continue
                if(view in setOf("todo","idea","research","execute")) dao.upsertEntryView(P14EntryViewEntity(entryId,view,true))
            }
        }
    }

    suspend fun loadEntryHistory(entryId:String,offset:Int=0,limit:Int=50):Int = withContext(Dispatchers.IO) {
        val connection=credentialStore.activeConnection() ?: return@withContext 0
        val url=connection.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("bootstrap/entries/$entryId/messages")
            .addQueryParameter("offset",offset.toString()).addQueryParameter("limit",limit.coerceIn(1,100).toString()).build()
        val root=json.parseToJsonElement(get(connection,url.toString())).jsonObject
        val items=root["items"]?.jsonArray ?: return@withContext 0
        database.withTransaction {
            for(raw in items) {
                val item=raw.jsonObject
                val mid=item.requiredString("message_id")
                val existing=dao.message(mid)
                if(existing!=null && existing.deliveryState!="SYNCED") continue
                val convoId=item.requiredString("conversation_id")
                val time=item.epochMs("created_at",Instant.now().toString())
                val revision=item["revision"]?.jsonPrimitive?.intOrNull ?: 1
                if(existing==null || revision>=existing.revision) {
                    dao.upsertMessage(P14ProductMessageEntity(mid,convoId,item.stringOrEmpty("role"),
                        item.stringOrEmpty("body_text"),revision=revision,
                        tombstone=item["tombstone"]?.jsonPrimitive?.contentOrNull=="true",
                        deliveryState="SYNCED",createdAtEpochMs=time))
                }
                item["attachments"]?.jsonArray?.forEach { a ->
                    val att=a.jsonObject
                    storeRemoteAttachment(att, mid, convoId, time)
                }
            }
        }
        items.size
    }

    suspend fun applyLiveEvents(eventsJson: String): Long {
        val root = json.parseToJsonElement(eventsJson).jsonObject
        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return dao.syncState(SYNC_SCOPE)?.lastSyncSeq ?: 0L
        if (type !in setOf("replay", "events")) return dao.syncState(SYNC_SCOPE)?.lastSyncSeq ?: 0L
        val events = json.decodeFromJsonElement(ListSerializer(P14SyncEventDto.serializer()), root["events"] ?: JsonArray(emptyList()))
        return applyEvents(events)
    }

    private suspend fun applyEvents(events: List<P14SyncEventDto>): Long = database.withTransaction {
        var cursor = dao.syncState(SYNC_SCOPE)?.lastSyncSeq ?: 0L
        for (event in events) {
            if (event.sync_seq <= cursor) continue
            val expected = cursor + 1
            check(event.sync_seq == expected) { "P14 sync gap: expected $expected, got ${event.sync_seq}" }
            when (event.event_type) {
                "conversation.message.created" -> applyMessageCreated(event)
                "assistant.draft.updated" -> applyAssistantDraft(event)
                "conversation.message.edited" -> applyMessageEdited(event)
                "conversation.message.deleted" -> applyMessageDeleted(event)
                "conversation.state.updated" -> applyConversationState(event)
                "observation.created" -> applyObservation(event)
                "conversation.quick_action.created" -> applyActionCreated(event)
                "conversation.action.result" -> applyActionResult(event)
                "conversation.action.decision" -> applyActionDecision(event)
                "entry.view.updated" -> applyViewUpdated(event)
                else -> Unit // forward-compatible: unknown durable events still advance the cursor
            }
            event.caused_by_operation_id?.let {
                dao.ackByOperationId(it, System.currentTimeMillis())
                dao.ackViewOutbox(it)
            }
            cursor = event.sync_seq
        }
        dao.upsertSyncState(P14ProductSyncStateEntity(SYNC_SCOPE, cursor))
        cursor
    }

    private suspend fun applyMessageCreated(event: P14SyncEventDto) {
        val payload = event.payload
        payload["entry"]?.jsonObject?.let { item ->
            val id = item.requiredString("entry_id")
            val existing = dao.entry(id)
            dao.upsertEntry(P14EntryEntity(id, item.stringOrEmpty("body_text"), existing?.createdAtEpochMs ?: item.epochMs("created_at", event.occurred_at)))
        }
        payload["conversation"]?.jsonObject?.let { item ->
            val id = item.requiredString("conversation_id")
            val entryId = item.requiredString("entry_id")
            val existing = dao.conversation(id)
            dao.upsertConversation(
                P14ProductConversationEntity(
                    id, entryId,
                    existing?.externalSessionId ?: "remote:$id",
                    existing?.localConversationId,
                    existing?.createdAtEpochMs ?: item.epochMs("created_at", event.occurred_at),
                    pinned = existing?.pinned ?: false,
                    archived = existing?.archived ?: false,
                    stateRevision = existing?.stateRevision ?: 0,
                    assistantId = existing?.assistantId,
                    modelId = existing?.modelId,
                )
            )
        }
        val item = payload["message"]?.jsonObject ?: return
        val messageId = item.requiredString("message_id")
        val conversationId = item.requiredString("conversation_id")
        val existingMessage = dao.message(messageId)
        payload["entry"]?.jsonObject?.get("entry_id")?.jsonPrimitive?.contentOrNull?.let { indexSource(it,messageId) }
        val preserveRevision=existingMessage?.deliveryState=="EDIT_PENDING" && dao.messageEdits(messageId).isNotEmpty()
        dao.upsertMessage(
            P14ProductMessageEntity(
                messageId,
                conversationId,
                item.requiredString("role").lowercase(),
                if(preserveRevision) existingMessage!!.bodyText else item.stringOrEmpty("body_text"),
                if(preserveRevision) existingMessage!!.revision else item["revision"]?.jsonPrimitive?.intOrNull ?: existingMessage?.revision ?: 1,
                item["tombstone"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                if(preserveRevision) "EDIT_PENDING" else "SYNCED",
                item.epochMs("created_at", event.occurred_at),
            )
        )
        if(!preserveRevision) dao.markMessageSynced(messageId)
        if (item.requiredString("role").lowercase() == "assistant") {
            // Server outbound events already carry this authoritative relation.
            // Both rows and the sync cursor advance atomically in applyEvents().
            event.payload["finalizes_draft_id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { draftId ->
                    dao.deleteFinalizedDraft("draft:$conversationId:$draftId")
                }
        }
        item["attachments"]?.jsonArray?.forEachIndexed { index, raw ->
            val a = raw.jsonObject
            storeRemoteAttachment(a, messageId, conversationId,
                (existingMessage?.createdAtEpochMs ?: System.currentTimeMillis()) + index)
        }
    }

    /** Replay and history refresh must not erase an already materialized original. */
    private suspend fun storeRemoteAttachment(item:JsonObject,messageId:String,conversationId:String,time:Long) {
        val remoteId=item.requiredString("attachment_id")
        val prior=dao.attachmentsForMessage(messageId).firstOrNull { it.remoteAttachmentId==remoteId }
        dao.upsertAttachment(P14ProductAttachmentEntity(
            prior?.localAttachmentId ?: "remote:$remoteId",remoteId,messageId,conversationId,
            item.stringOrEmpty("kind").ifBlank { "FILE" },
            item.stringOrEmpty("mime_type").ifBlank { "application/octet-stream" },
            item.stringOrEmpty("file_name").ifBlank { "attachment" },
            prior?.localLocator.orEmpty(),item["byte_size"]?.jsonPrimitive?.longOrNull,
            item["duration_ms"]?.jsonPrimitive?.longOrNull,"SYNCED",prior?.createdAtEpochMs ?: time))
    }

    /** Lazy authenticated binary retrieval; wire content_url is deliberately never trusted.
     * Metadata stays available while download fails. An incomplete temp file is never a locator.
     */
    suspend fun materializeAttachment(localAttachmentId:String):Uri = withContext(Dispatchers.IO) {
        mediaDownloadMutex.withLock {
            val attachment=requireNotNull(dao.attachment(localAttachmentId)) { "Attachment metadata unavailable" }
            attachment.localLocator.takeIf { it.startsWith("file:") }?.let { locator ->
                val file=Uri.parse(locator).path?.let(::File)
                if(file?.isFile==true) return@withLock Uri.fromFile(file)
            }
            val connection=credentialStore.activeConnection() ?: error("Pair P14 before downloading media")
            val remoteId=requireNotNull(attachment.remoteAttachmentId) { "Attachment has not reached Server" }
            val metadataUrl=connection.baseUrl.toHttpUrl().newBuilder().addPathSegment("attachments").addPathSegment(remoteId).build()
            val mediaHttp=http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val metadataRequest=Request.Builder().url(metadataUrl).header("Authorization","Bearer ${connection.bearerToken}").get().build()
            val metadata=mediaHttp.newCall(metadataRequest).execute().use { response ->
                if(!response.isSuccessful) throw P14RemoteHttpException(response.code,"Attachment metadata unavailable")
                json.parseToJsonElement(requireNotNull(response.body).string()).jsonObject
            }
            require(metadata.requiredString("attachment_id")==remoteId && metadata.stringOrEmpty("state")=="READY") { "Attachment is not ready" }
            val expectedSize=requireNotNull(metadata["byte_size"]?.jsonPrimitive?.longOrNull) { "Attachment size unavailable" }
            require(expectedSize in 0..MAX_DOWNLOAD_BYTES) { "Attachment exceeds download limit" }
            attachment.byteSize?.let { require(it==expectedSize) { "Attachment size changed" } }
            val expectedHash=metadata["sha256"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            expectedHash?.let { require(it.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid attachment digest" } }
            val root=File(appContext.filesDir,"p14_remote_media").apply { mkdirs() }
            val identity=MessageDigest.getInstance("SHA-256").digest(remoteId.toByteArray()).joinToString("") { "%02x".format(it) }
            val extension=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(attachment.mimeType)
                ?.takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
            val destination=File(root,identity+(extension?.let { ".$it" } ?: ".bin"))
            val temporary=File.createTempFile("download-", ".part",root)
            try {
                val contentUrl=metadataUrl.newBuilder().addPathSegment("content").build()
                val request=Request.Builder().url(contentUrl).header("Authorization","Bearer ${connection.bearerToken}").get().build()
                mediaHttp.newCall(request).execute().use { response ->
                    if(!response.isSuccessful) throw P14RemoteHttpException(response.code,"Attachment content unavailable")
                    val body=requireNotNull(response.body) { "Attachment body missing" }
                    val announced=body.contentLength()
                    require(announced<0 || announced==expectedSize) { "Attachment content length changed" }
                    val digest=MessageDigest.getInstance("SHA-256")
                    var total=0L
                    body.byteStream().use { input -> temporary.outputStream().use { output ->
                        val buffer=ByteArray(64*1024)
                        while(true) {
                            val count=input.read(buffer)
                            if(count<0) break
                            total+=count
                            require(total<=expectedSize && total<=MAX_DOWNLOAD_BYTES) { "Attachment stream exceeds size" }
                            digest.update(buffer,0,count);output.write(buffer,0,count)
                        }
                        output.fd.sync()
                    } }
                    require(total==expectedSize) { "Attachment stream incomplete" }
                    val hash=digest.digest().joinToString("") { "%02x".format(it) }
                    expectedHash?.let { require(hash.equals(it,ignoreCase=true)) { "Attachment digest mismatch" } }
                }
                check(temporary.renameTo(destination)) { "Cannot finalize attachment download" }
                val locator=Uri.fromFile(destination)
                dao.setAttachmentLocalLocator(localAttachmentId,locator.toString())
                locator
            } finally { temporary.delete() }
        }
    }

    private suspend fun applyAssistantDraft(event: P14SyncEventDto) {
        val p = event.payload
        val conversationId = p.requiredString("conversation_id")
        val draftId = p.requiredString("draft_id")
        val messageId = "draft:$conversationId:$draftId"
        val existing = dao.message(messageId)
        dao.upsertMessage(P14ProductMessageEntity(messageId, conversationId, "assistant", p.stringOrEmpty("body_text"), deliveryState = "STREAMING", createdAtEpochMs = existing?.createdAtEpochMs ?: epochMs(event.occurred_at)))
    }

    private suspend fun applyMessageEdited(event: P14SyncEventDto) {
        val p = event.payload
        val id = p["message_id"]?.jsonPrimitive?.contentOrNull ?: event.entity_id
        val existing = dao.message(id) ?: return
        val pending=dao.messageEdits(id)
        val revision=p["revision"]?.jsonPrimitive?.intOrNull ?: existing.revision
        // An acknowledgement of an earlier local edit must not replace a later offline revision.
        if(pending.isNotEmpty() && pending.last().clientOperationId!=event.caused_by_operation_id) return
        if(revision<existing.revision) return
        dao.upsertMessage(existing.copy(bodyText=p["body_text"]?.jsonPrimitive?.contentOrNull ?: existing.bodyText,
            revision=revision,deliveryState="SYNCED"))
    }

    private suspend fun applyMessageDeleted(event: P14SyncEventDto) {
        val p = event.payload
        val id = p["message_id"]?.jsonPrimitive?.contentOrNull ?: event.entity_id
        val existing = dao.message(id) ?: return
        dao.upsertMessage(existing.copy(tombstone = true, revision = p["revision"]?.jsonPrimitive?.intOrNull ?: existing.revision))
    }

    private suspend fun applyConversationState(event: P14SyncEventDto) {
        val p=event.payload
        val id=p["conversation_id"]?.jsonPrimitive?.contentOrNull ?: event.entity_id
        val existing=dao.conversation(id) ?: return
        if((p["revision"]?.jsonPrimitive?.intOrNull ?: 0)<existing.stateRevision) return
        dao.upsertConversation(existing.copy(
            pinned=p["pinned"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: existing.pinned,
            archived=p["archived"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: existing.archived,
            stateRevision=p["revision"]?.jsonPrimitive?.intOrNull ?: existing.stateRevision,
        ))
    }

    private suspend fun applyActionCreated(event:P14SyncEventDto) {
        val p=event.payload
        val id=p.requiredString("action_id")
        val existing=dao.action(id)
        dao.upsertAction(P14ActionEntity(id,p.requiredString("conversation_id"),p.stringOrEmpty("action_type"),
            existing?.status ?: "accepted",existing?.resultText,existing?.destinationRef,
            existing?.createdAtEpochMs ?: p.epochMs("created_at",event.occurred_at)))
    }

    private suspend fun applyActionDecision(event:P14SyncEventDto) {
        val p=event.payload
        val id=p.requiredString("action_id")
        val old=dao.action(id) ?: return
        dao.upsertAction(old.copy(status=p.stringOrEmpty("status")))
    }

    private suspend fun applyActionResult(event:P14SyncEventDto) {
        val p=event.payload
        val id=p.requiredString("action_id")
        val existing=dao.action(id)
        dao.upsertAction(P14ActionEntity(id,p.requiredString("conversation_id"),
            existing?.actionType ?: "action",p.stringOrEmpty("status"),
            p["result_text"]?.jsonPrimitive?.contentOrNull,
            p["destination_ref"]?.jsonPrimitive?.contentOrNull,
            existing?.createdAtEpochMs ?: p.epochMs("created_at",event.occurred_at)))
    }

    private suspend fun applyViewUpdated(event:P14SyncEventDto) {
        val p=event.payload
        val view=p.requiredString("view_name")
        if(view !in setOf("todo","idea","research","execute")) return
        val enabled=p["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: return
        dao.upsertEntryView(P14EntryViewEntity(p.requiredString("entry_id"),view,enabled))
    }

    private suspend fun flushViewAssignments(connection:P14RuntimeConnection) {
        for (operation in dao.pendingViewOutbox()) {
            dao.setViewOutboxState(operation.operationId,"IN_FLIGHT")
            try {
                val body=buildJsonObject {
                    put("client_operation_id",operation.operationId)
                    put("enabled",operation.enabled)
                }
                post(connection,"entries/${operation.entryId}/views/${operation.viewName}",body.toString())
                dao.setViewOutboxState(operation.operationId,"ACKED")
            } catch(error:Exception) {
                dao.setViewOutboxState(operation.operationId,"RETRY")
                throw error
            }
        }
    }

    private suspend fun applyObservation(event:P14SyncEventDto) {
        val p=event.payload
        val id=p.requiredString("observation_id")
        val old=dao.observation(id)
        val lat=p["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return
        val long=p["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return
        dao.upsertObservation(P14ObservationEntity(id,"LOCATION",lat,long,
            p["accuracy_m"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            old?.capturedAtEpochMs ?: p.epochMs("captured_at",event.occurred_at),"SYNCED",
            profileId=p.stringOrEmpty("profile_id").ifBlank { old?.profileId ?: "legacy-unattributed" },
            profileVersion=p["profile_version"]?.jsonPrimitive?.intOrNull ?: old?.profileVersion ?: 1,
            sourceDeviceId=p.stringOrEmpty("source_device_id").ifBlank { old?.sourceDeviceId ?: "unknown" },
            permissionState=p.stringOrEmpty("permission_state").ifBlank { old?.permissionState ?: "unspecified" },
            policyMode=p.stringOrEmpty("policy_mode").ifBlank { "significant-change" },
            policyMinDistanceM=p["policy_min_distance_m"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 500.0,
            retentionDays=p["retention_days"]?.jsonPrimitive?.intOrNull ?: 7))
    }

    private suspend fun flushObservations(connection:P14RuntimeConnection) {
        for (operation in dao.pendingObservationOutbox()) {
            val observation=dao.observation(operation.observationId) ?: continue
            dao.setObservationOutboxState(operation.operationId,"IN_FLIGHT")
            try {
                val body=buildJsonObject {
                    put("client_operation_id",operation.operationId)
                    put("observation_id",observation.observationId)
                    put("latitude",observation.latitude)
                    put("longitude",observation.longitude)
                    observation.accuracyM?.let{put("accuracy_m",it)}
                    put("captured_at",Instant.ofEpochMilli(observation.capturedAtEpochMs).toString())
                    put("profile_id",observation.profileId)
                    put("profile_version",observation.profileVersion)
                    put("permission_state",observation.permissionState)
                    put("policy_mode",observation.policyMode)
                    put("policy_min_distance_m",observation.policyMinDistanceM)
                    put("retention_days",observation.retentionDays)
                    // Source-device identity is server-authenticated; never trust a caller-provided field.
                }
                post(connection,"observations/location",body.toString())
                dao.setObservationOutboxState(operation.operationId,"ACKED")
                dao.setObservationState(observation.observationId,"SYNCED")
            } catch (error:Exception) {
                dao.setObservationOutboxState(operation.operationId,"RETRY")
                throw error
            }
        }
        val days=P14ProfileStore(appContext).retentionDays()
        dao.purgeSyncedObservations(System.currentTimeMillis()-days*86_400_000L)
    }

    private suspend fun flush(connection: P14RuntimeConnection, manifest: P14EffectiveManifest) {
        // Later revisions and turns must not overtake an unaccepted capture/revision.
        // Independent conversations can continue syncing when one is blocked.
        val blockedConversations=mutableSetOf<String>()
        for (operation in dao.pendingOutbox()) {
            if(operation.conversationId in blockedConversations) continue
            if(dao.observeConflicts(operation.conversationId).first().isNotEmpty()) {
                blockedConversations+=operation.conversationId
                continue
            }
            val attachments = dao.attachmentsForMessage(operation.messageId)
            if (attachments.any { it.state != "SYNCED" || it.remoteAttachmentId == null }) {
                blockedConversations+=operation.conversationId
                continue
            }
            dao.markOutboxInFlight(operation.clientOperationId)
            try {
                val payload = json.parseToJsonElement(operation.payloadJson).jsonObject
                if(operation.operationType in setOf("EDIT_SOURCE_MESSAGE","SET_CONVERSATION_STATE")) {
                    val mutation=buildJsonObject {
                        put("client_operation_id",operation.clientOperationId)
                        payload.forEach { (key,value) -> put(key,value) }
                    }
                    val path=if(operation.operationType=="EDIT_SOURCE_MESSAGE") {
                        require("edit" in manifest.messageCapabilities) { "服务端未启用修订功能" }
                        "conversations/${operation.conversationId}/messages/${operation.messageId}/edit"
                    } else "conversations/${operation.conversationId}/state"
                    post(connection,path,mutation.toString())
                    dao.markOutboxAcked(operation.clientOperationId,System.currentTimeMillis())
                    continue
                }
                if (payload["reply_to_message_id"]?.jsonPrimitive?.contentOrNull != null) {
                    require("reply" in manifest.messageCapabilities) { "P14 Manifest disabled reply" }
                }
                val attachmentIds = attachments.mapNotNull { it.remoteAttachmentId }
                val body = buildJsonObject {
                    put("client_operation_id", operation.clientOperationId)
                    if (operation.operationType == "CREATE_ENTRY_WITH_MESSAGE") {
                        put("entry_id", operation.entryId)
                        put("conversation_id", operation.conversationId)
                    }
                    put("message_id", operation.messageId)
                    put("text", payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("attachment_ids", buildJsonArray { attachmentIds.forEach { add(JsonPrimitive(it)) } })
                    payload["reply_to_message_id"]?.jsonPrimitive?.contentOrNull?.let { put("reply_to_message_id", it) }
                    (payload["quote"] as? JsonObject)?.let { put("quote", it) }
                }
                val path = if (operation.operationType == "CREATE_ENTRY_WITH_MESSAGE") {
                    if (payload["defer_ai"]?.jsonPrimitive?.booleanOrNull == true) "entries/capture" else "entries"
                } else {
                    "conversations/${operation.conversationId}/messages"
                }
                post(connection, path, body.toString())
                dao.markOutboxAcked(operation.clientOperationId, System.currentTimeMillis())
            } catch (error: P14RemoteHttpException) {
                blockedConversations+=operation.conversationId
                if (error.status == 409) {
                    if(operation.operationType in setOf("EDIT_SOURCE_MESSAGE","SET_CONVERSATION_STATE")) {
                        dao.setMutationState(operation.clientOperationId,"CONFLICT","REVISION_CONFLICT")
                        continue
                    }
                    replay(connection)
                }
                val stillPending = dao.pendingOutbox().any { it.clientOperationId == operation.clientOperationId }
                if (stillPending) dao.markOutboxRetry(operation.clientOperationId, "HTTP_${error.status}")
            } catch (error: Throwable) {
                blockedConversations+=operation.conversationId
                dao.markOutboxRetry(operation.clientOperationId, error.javaClass.simpleName.take(80))
            }
        }
    }

    private fun productAttachments(message: UIMessage, conversationId: String, now: Long): List<P14ProductAttachmentEntity> =
        message.parts.mapIndexedNotNull { index, part ->
            val locator: String
            val kind: String
            val name: String
            val mime: String
            when (part) {
                is UIMessagePart.Image -> { locator = part.url; kind = "IMAGE"; name = (if(part.metadata?.get("p14_media_role")?.jsonPrimitive?.contentOrNull=="scan") "scan-" else "") + (Uri.parse(locator).lastPathSegment ?: "image"); mime = appContext.contentResolver.getType(Uri.parse(locator)) ?: URLConnection.guessContentTypeFromName(name) ?: "image/jpeg" }
                is UIMessagePart.Video -> { locator = part.url; kind = "VIDEO"; name = Uri.parse(locator).lastPathSegment ?: "video"; mime = appContext.contentResolver.getType(Uri.parse(locator)) ?: URLConnection.guessContentTypeFromName(name) ?: "video/mp4" }
                is UIMessagePart.Audio -> { locator = part.url; kind = durableP14AudioKind(part); name = Uri.parse(locator).lastPathSegment ?: "audio"; mime = part.metadata?.get("p14_mime_type")?.jsonPrimitive?.contentOrNull ?: appContext.contentResolver.getType(Uri.parse(locator)) ?: URLConnection.guessContentTypeFromName(name) ?: "audio/mpeg" }
                is UIMessagePart.Document -> { locator = part.url; kind = "FILE"; name = part.fileName.ifBlank { Uri.parse(locator).lastPathSegment ?: "file" }; mime = part.mime.ifBlank { "application/octet-stream" } }
                else -> return@mapIndexedNotNull null
            }
            val size = runCatching { appContext.contentResolver.openAssetFileDescriptor(Uri.parse(locator), "r")?.use { it.length.takeIf { value -> value >= 0 } } }.getOrNull()
            P14ProductAttachmentEntity(
                localAttachmentId = "${message.id}:$index",
                remoteAttachmentId = null,
                messageId = message.id.toString(),
                conversationId = conversationId,
                kind = kind,
                mimeType = mime,
                fileName = name,
                localLocator = locator,
                byteSize = size,
                durationMs = part.metadata?.get("p14_duration_ms")?.jsonPrimitive?.longOrNull,
                state = "PENDING",
                createdAtEpochMs = now + index,
            )
        }

    private fun get(connection: P14RuntimeConnection, url: String): String = execute(
        Request.Builder().url(url).header("Authorization", "Bearer ${connection.bearerToken}").get().build()
    )

    private fun post(connection: P14RuntimeConnection, path: String, payload: String): String = execute(
        Request.Builder().url("${connection.baseUrl}/${path.trimStart('/')}")
            .header("Authorization", "Bearer ${connection.bearerToken}")
            .post(payload.toRequestBody(JSON_MEDIA)).build()
    )

    private fun execute(request: Request): String = http.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw P14RemoteHttpException(response.code, text.take(800))
        text
    }

    private data class CompatIds(val entryId: String, val conversationId: String)
    private fun compatIds(externalSessionId: String): CompatIds {
        val bytes = MessageDigest.getInstance("SHA-256").digest("rikkahub\u0000$externalSessionId".toByteArray())
        val digest = bytes.joinToString("") { "%02x".format(it) }.take(40)
        return CompatIds("rk-e-$digest", "rk-c-$digest")
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("P14 sync payload missing $key")
    private fun JsonObject.stringOrEmpty(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.epochMs(key: String, fallback: String): Long =
        this[key]?.jsonPrimitive?.contentOrNull?.let(::epochMs) ?: epochMs(fallback)
    private fun epochMs(value: String): Long = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    companion object {
        private const val SYNC_SCOPE = "account"
        private const val MAX_DOWNLOAD_BYTES=512L*1024*1024
        private val directoryMutex=Mutex()
        private val mediaDownloadMutex=Mutex()
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
