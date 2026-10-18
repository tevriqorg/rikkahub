package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
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

internal class P14ProductRepository(
    context: Context,
    private val http: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val database = P14ProductDatabase.get(appContext)
    private val dao = database.dao()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val credentialStore = P14RuntimeCredentialStore(appContext)

    val entries: Flow<List<P14EntryEntity>> = dao.observeEntries()
    suspend fun directoryPage(offset:Int,limit:Int=20,view:String="chat"):List<P14EntryDirectoryItem> = withContext(Dispatchers.IO) {
        require(offset>=0 && limit in 1..100)
        require(view in setOf("chat","todo","idea","research","execute"))
        val raw = if(view=="chat") dao.directoryPage(limit,offset) else dao.directoryPageForView(view,limit,offset)
        raw.map { c ->
            P14EntryDirectoryItem(c.entryId,c.conversationId,dao.entry(c.entryId)?.bodyText.orEmpty(),c.pinned,c.archived)
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
    fun attachments(conversationId:String,limit:Int=50):Flow<List<P14ProductAttachmentEntity>> = dao.observeAttachmentsPaged(conversationId,limit)
    suspend fun entry(entryId: String) = dao.entry(entryId)
    suspend fun conversation(conversationId: String) = dao.conversation(conversationId)
    suspend fun conversationForExternal(externalSessionId: String) = dao.conversationByExternalSession(externalSessionId)
    suspend fun message(messageId: String) = dao.message(messageId)

    suspend fun acceptRikkaMessage(
        externalSessionId: String,
        localConversationId: String,
        message: UIMessage,
        assistantId: String,
        modelId: String,
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
                    conversation.externalSessionId, buildJsonObject { put("text", normalized) }.toString(), createdAtEpochMs = now,
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
                policyMode="significant-change",policyMinDistanceM=profile.locationMinDistanceMeters(),
                retentionDays=profile.retentionDays()))
            dao.insertObservationOutbox(P14ObservationOutboxEntity("obs-op-$id",id))
        }
        id
    }

    suspend fun recentObservations():List<P14ObservationEntity> = dao.recentObservations()

    suspend fun currentCursor(): Long = dao.syncState(SYNC_SCOPE)?.lastSyncSeq ?: 0L

    suspend fun replayActive(): Boolean = withContext(Dispatchers.IO) {
        val connection = credentialStore.activeConnection() ?: return@withContext false
        replay(connection)
        true
    }

    suspend fun replay(connection: P14RuntimeConnection): Long {
        var cursor = dao.syncState(SYNC_SCOPE)?.lastSyncSeq ?: 0L
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
        dao.upsertMessage(
            P14ProductMessageEntity(
                messageId,
                conversationId,
                item.requiredString("role").lowercase(),
                item.stringOrEmpty("body_text"),
                item["revision"]?.jsonPrimitive?.intOrNull ?: existingMessage?.revision ?: 1,
                item["tombstone"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                "SYNCED",
                existingMessage?.createdAtEpochMs ?: item.epochMs("created_at", event.occurred_at),
            )
        )
        dao.markMessageSynced(messageId)
        item["attachments"]?.jsonArray?.forEachIndexed { index, raw ->
            val a = raw.jsonObject
            val remoteId = a.requiredString("attachment_id")
            dao.upsertAttachment(
                P14ProductAttachmentEntity(
                    localAttachmentId = "remote:$remoteId",
                    remoteAttachmentId = remoteId,
                    messageId = messageId,
                    conversationId = conversationId,
                    kind = a.stringOrEmpty("kind").ifBlank { "FILE" },
                    mimeType = a.stringOrEmpty("mime_type").ifBlank { "application/octet-stream" },
                    fileName = a.stringOrEmpty("file_name").ifBlank { "attachment" },
                    localLocator = "",
                    byteSize = a["byte_size"]?.jsonPrimitive?.longOrNull,
                    durationMs = a["duration_ms"]?.jsonPrimitive?.longOrNull,
                    state = "SYNCED",
                    createdAtEpochMs = (existingMessage?.createdAtEpochMs ?: System.currentTimeMillis()) + index,
                )
            )
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
        dao.upsertMessage(existing.copy(bodyText = p["body_text"]?.jsonPrimitive?.contentOrNull ?: existing.bodyText, revision = p["revision"]?.jsonPrimitive?.intOrNull ?: existing.revision))
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
        for (operation in dao.pendingOutbox()) {
            val attachments = dao.attachmentsForMessage(operation.messageId)
            if (attachments.any { it.state != "SYNCED" || it.remoteAttachmentId == null }) continue
            dao.markOutboxInFlight(operation.clientOperationId)
            try {
                val payload = json.parseToJsonElement(operation.payloadJson).jsonObject
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
                    "entries"
                } else {
                    "conversations/${operation.conversationId}/messages"
                }
                post(connection, path, body.toString())
                dao.markOutboxAcked(operation.clientOperationId, System.currentTimeMillis())
            } catch (error: P14RemoteHttpException) {
                if (error.status == 409) {
                    replay(connection)
                }
                val stillPending = dao.pendingOutbox().any { it.clientOperationId == operation.clientOperationId }
                if (stillPending) dao.markOutboxRetry(operation.clientOperationId, "HTTP_${error.status}")
            } catch (error: Throwable) {
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
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
