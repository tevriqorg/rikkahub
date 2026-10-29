package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "p14_entries")
data class P14EntryEntity(
    @PrimaryKey @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "body_text") val bodyText: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Entity(
    tableName = "p14_conversations",
    indices = [Index(value = ["external_session_id"], unique = true), Index("entry_id")],
)
data class P14ProductConversationEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "external_session_id") val externalSessionId: String,
    @ColumnInfo(name = "local_conversation_id") val localConversationId: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    @ColumnInfo(name = "state_revision") val stateRevision: Int = 0,
    @ColumnInfo(name = "assistant_id") val assistantId: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
)

@Entity(
    tableName = "p14_messages",
    indices = [Index("conversation_id"), Index("delivery_state"), Index("created_at_epoch_ms")],
)
data class P14ProductMessageEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val role: String,
    @ColumnInfo(name = "body_text") val bodyText: String,
    val revision: Int = 1,
    val tombstone: Boolean = false,
    @ColumnInfo(name = "delivery_state") val deliveryState: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Entity(
    tableName = "p14_attachments",
    indices = [Index("message_id"), Index("conversation_id"), Index("state")],
)
data class P14ProductAttachmentEntity(
    @PrimaryKey @ColumnInfo(name = "local_attachment_id") val localAttachmentId: String,
    @ColumnInfo(name = "remote_attachment_id") val remoteAttachmentId: String?,
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val kind: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "local_locator") val localLocator: String,
    @ColumnInfo(name = "byte_size") val byteSize: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val state: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Entity(tableName = "p14_chat_outbox", indices = [Index("state"), Index("conversation_id")])
data class P14ChatOutboxEntity(
    @PrimaryKey @ColumnInfo(name = "client_operation_id") val clientOperationId: String,
    @ColumnInfo(name = "operation_type") val operationType: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "external_session_id") val externalSessionId: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    val state: String = "PENDING",
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error_class") val lastErrorClass: String? = null,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "acked_at_epoch_ms") val ackedAtEpochMs: Long? = null,
)

@Entity(tableName="p14_actions",indices=[Index("conversation_id")])
data class P14ActionEntity(
    @PrimaryKey @ColumnInfo(name="action_id") val actionId:String,
    @ColumnInfo(name="conversation_id") val conversationId:String,
    @ColumnInfo(name="action_type") val actionType:String,
    val status:String,
    @ColumnInfo(name="result_text") val resultText:String?=null,
    @ColumnInfo(name="destination_ref") val destinationRef:String?=null,
    @ColumnInfo(name="created_at_epoch_ms") val createdAtEpochMs:Long,
)

@Entity(tableName="p14_entry_views",primaryKeys=["entry_id","view_name"])
data class P14EntryViewEntity(
    @ColumnInfo(name="entry_id") val entryId:String,
    @ColumnInfo(name="view_name") val viewName:String,
    val enabled:Boolean,
)

@Entity(tableName="p14_view_outbox",indices=[Index("state")])
data class P14ViewOutboxEntity(
    @PrimaryKey @ColumnInfo(name="operation_id") val operationId:String,
    @ColumnInfo(name="entry_id") val entryId:String,
    @ColumnInfo(name="view_name") val viewName:String,
    val enabled:Boolean,
    val state:String="PENDING",
)

@Entity(tableName = "p14_observations", indices = [Index("state"), Index("captured_at_epoch_ms")])
data class P14ObservationEntity(
    @PrimaryKey @ColumnInfo(name="observation_id") val observationId:String,
    val kind:String,
    val latitude:Double,
    val longitude:Double,
    @ColumnInfo(name="accuracy_m") val accuracyM:Double?,
    @ColumnInfo(name="captured_at_epoch_ms") val capturedAtEpochMs:Long,
    val state:String="PENDING",
    @ColumnInfo(name="profile_id") val profileId:String="legacy-unattributed",
    @ColumnInfo(name="profile_version") val profileVersion:Int=1,
    @ColumnInfo(name="source_device_id") val sourceDeviceId:String="unknown",
    @ColumnInfo(name="permission_state") val permissionState:String="unspecified",
    @ColumnInfo(name="policy_mode") val policyMode:String="significant-change",
    @ColumnInfo(name="policy_min_distance_m") val policyMinDistanceM:Double=500.0,
    @ColumnInfo(name="retention_days") val retentionDays:Int=7,
)

@Entity(tableName="p14_observation_outbox", indices=[Index("state")])
data class P14ObservationOutboxEntity(
    @PrimaryKey @ColumnInfo(name="operation_id") val operationId:String,
    @ColumnInfo(name="observation_id") val observationId:String,
    val state:String="PENDING",
)

@Entity(tableName = "p14_product_sync_state")
data class P14ProductSyncStateEntity(
    @PrimaryKey val scope: String,
    @ColumnInfo(name = "last_sync_seq") val lastSyncSeq: Long,
)

/** A projected Inbox row: one stable Entry and its existing Conversation. */
data class P14InboxRow(
    val entryId: String,
    val conversationId: String,
    val bodyText: String,
    val createdAtEpochMs: Long,
    val pinned: Boolean,
    val archived: Boolean,
    val views: String? = null,
    val thumbnailLocator: String? = null,
    val thumbnailAttachmentId: String? = null,
    val attachmentCount: Int = 0,
    val messageVersion: Int = 0,
)

@Dao
interface P14ProductDao {
    @Upsert suspend fun upsertAction(item:P14ActionEntity)
    @Query("SELECT * FROM p14_actions WHERE action_id=:id LIMIT 1") suspend fun action(id:String):P14ActionEntity?
    @Query("SELECT * FROM p14_actions WHERE conversation_id=:conversationId ORDER BY created_at_epoch_ms DESC") fun observeActions(conversationId:String):Flow<List<P14ActionEntity>>
    @Upsert suspend fun upsertEntryView(item:P14EntryViewEntity)
    @Query("SELECT * FROM p14_entry_views WHERE entry_id=:entryId") fun observeEntryViews(entryId:String):Flow<List<P14EntryViewEntity>>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertViewOutbox(item:P14ViewOutboxEntity):Long
    @Query("SELECT * FROM p14_view_outbox WHERE state IN ('PENDING','RETRY','IN_FLIGHT') ORDER BY rowid ASC LIMIT :limit") suspend fun pendingViewOutbox(limit:Int=30):List<P14ViewOutboxEntity>
    @Query("UPDATE p14_view_outbox SET state=:state WHERE operation_id=:id") suspend fun setViewOutboxState(id:String,state:String)
    @Query("UPDATE p14_view_outbox SET state='ACKED' WHERE operation_id=:id") suspend fun ackViewOutbox(id:String)
    @Upsert suspend fun upsertObservation(item:P14ObservationEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertObservationOutbox(item:P14ObservationOutboxEntity):Long
    @Query("SELECT * FROM p14_observations WHERE observation_id=:id LIMIT 1") suspend fun observation(id:String):P14ObservationEntity?
    @Query("SELECT * FROM p14_observation_outbox WHERE state IN ('PENDING','RETRY','IN_FLIGHT') ORDER BY rowid ASC LIMIT :limit") suspend fun pendingObservationOutbox(limit:Int=25):List<P14ObservationOutboxEntity>
    @Query("UPDATE p14_observation_outbox SET state=:state WHERE operation_id=:id") suspend fun setObservationOutboxState(id:String,state:String)
    @Query("UPDATE p14_observations SET state=:state WHERE observation_id=:id") suspend fun setObservationState(id:String,state:String)
    @Query("DELETE FROM p14_observations WHERE captured_at_epoch_ms < :cutoff AND state='SYNCED'") suspend fun purgeSyncedObservations(cutoff:Long)
    @Query("SELECT * FROM p14_observations ORDER BY captured_at_epoch_ms DESC LIMIT :limit") suspend fun recentObservations(limit:Int=20):List<P14ObservationEntity>
    @Upsert suspend fun upsertEntry(item: P14EntryEntity)
    @Upsert suspend fun upsertConversation(item: P14ProductConversationEntity)
    @Upsert suspend fun upsertMessage(item: P14ProductMessageEntity)
    /** A finalized Hermes message replaces its exact transient draft, never a user message. */
    @Query("DELETE FROM p14_messages WHERE message_id=:draftMessageId AND delivery_state='STREAMING' AND role='assistant'")
    suspend fun deleteFinalizedDraft(draftMessageId:String):Int
    @Upsert suspend fun upsertAttachment(item: P14ProductAttachmentEntity)
    @Query("SELECT * FROM p14_attachments WHERE local_attachment_id=:id LIMIT 1") suspend fun attachment(id:String):P14ProductAttachmentEntity?
    @Query("UPDATE p14_attachments SET local_locator=:locator WHERE local_attachment_id=:id") suspend fun setAttachmentLocalLocator(id:String,locator:String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertOutbox(item: P14ChatOutboxEntity): Long
    @Upsert suspend fun upsertSyncState(item: P14ProductSyncStateEntity)

    /** Live, locally backed TickTick-style Inbox. Does not fork Entry or Conversation. */
    @Query("""
        SELECT c.entry_id AS entryId, c.conversation_id AS conversationId,
               e.body_text AS bodyText, c.created_at_epoch_ms AS createdAtEpochMs,
               c.pinned AS pinned, c.archived AS archived,
               (SELECT group_concat(v.view_name, ',') FROM p14_entry_views v WHERE v.entry_id=c.entry_id AND v.enabled=1) AS views,
               (SELECT a.local_locator FROM p14_attachments a WHERE a.conversation_id=c.conversation_id AND a.mime_type LIKE 'image/%' ORDER BY a.created_at_epoch_ms ASC LIMIT 1) AS thumbnailLocator,
               (SELECT a.local_attachment_id FROM p14_attachments a WHERE a.conversation_id=c.conversation_id AND a.mime_type LIKE 'image/%' ORDER BY a.created_at_epoch_ms ASC LIMIT 1) AS thumbnailAttachmentId,
               (SELECT count(*) FROM p14_attachments a WHERE a.conversation_id=c.conversation_id) AS attachmentCount,
               (SELECT coalesce(sum(m.revision),0) FROM p14_messages m WHERE m.conversation_id=c.conversation_id) AS messageVersion
        FROM p14_conversations c
        INNER JOIN p14_entries e ON e.entry_id = c.entry_id
        WHERE c.archived = :archived
          AND (:view = '' OR EXISTS (SELECT 1 FROM p14_entry_views v WHERE v.entry_id=c.entry_id AND v.view_name=:view AND v.enabled=1))
          AND (:query = '' OR instr(lower(e.body_text),lower(:query))>0 OR EXISTS (SELECT 1 FROM p14_messages m WHERE m.conversation_id=c.conversation_id AND instr(lower(m.body_text),lower(:query))>0))
        ORDER BY c.pinned DESC, c.created_at_epoch_ms DESC, c.entry_id DESC
        LIMIT :limit
    """)
    fun observeInboxRows(archived:Boolean,limit:Int,query:String="",view:String=""):Flow<List<P14InboxRow>>
    @Query("SELECT * FROM p14_entries ORDER BY created_at_epoch_ms DESC") fun observeEntries(): Flow<List<P14EntryEntity>>
    @Query("SELECT * FROM p14_conversations ORDER BY pinned DESC, created_at_epoch_ms DESC, entry_id DESC LIMIT :limit OFFSET :offset") suspend fun directoryPage(limit:Int,offset:Int):List<P14ProductConversationEntity>
    @Query("SELECT * FROM p14_conversations WHERE :coverageAfter IS NULL OR local_conversation_id IS NOT NULL OR created_at_epoch_ms > :coverageAfter ORDER BY pinned DESC, created_at_epoch_ms DESC, entry_id DESC LIMIT :limit OFFSET :offset") suspend fun coveredDirectoryPage(limit:Int,offset:Int,coverageAfter:Long?):List<P14ProductConversationEntity>
    @Query("SELECT c.* FROM p14_conversations c JOIN p14_entry_views v ON c.entry_id=v.entry_id WHERE v.view_name=:viewName AND v.enabled=1 ORDER BY c.pinned DESC,c.created_at_epoch_ms DESC,c.entry_id DESC LIMIT :limit OFFSET :offset") suspend fun directoryPageForView(viewName:String,limit:Int,offset:Int):List<P14ProductConversationEntity>
    @Query("SELECT * FROM p14_entries WHERE entry_id=:entryId LIMIT 1") suspend fun entry(entryId: String): P14EntryEntity?
    @Query("SELECT * FROM p14_entries WHERE entry_id=:entryId LIMIT 1") fun observeEntry(entryId: String): Flow<P14EntryEntity?>
    @Query("SELECT * FROM p14_conversations WHERE conversation_id=:conversationId LIMIT 1") suspend fun conversation(conversationId: String): P14ProductConversationEntity?
    @Query("SELECT * FROM p14_conversations WHERE conversation_id=:conversationId LIMIT 1") fun observeConversation(conversationId: String): Flow<P14ProductConversationEntity?>
    @Query("SELECT * FROM p14_conversations WHERE external_session_id=:externalSessionId LIMIT 1") suspend fun conversationByExternalSession(externalSessionId: String): P14ProductConversationEntity?
    @Query("SELECT * FROM p14_conversations WHERE local_conversation_id=:localId LIMIT 1") suspend fun conversationByLocalId(localId: String): P14ProductConversationEntity?
    @Query("SELECT * FROM p14_conversations WHERE local_conversation_id IS NOT NULL ORDER BY created_at_epoch_ms DESC") suspend fun localConversations(): List<P14ProductConversationEntity>
    @Query("SELECT * FROM p14_messages WHERE message_id=:messageId LIMIT 1") suspend fun message(messageId: String): P14ProductMessageEntity?
    @Query("SELECT * FROM p14_messages WHERE conversation_id=:conversationId AND role='user' ORDER BY created_at_epoch_ms ASC,message_id ASC LIMIT 1") fun observeFirstUser(conversationId:String):Flow<P14ProductMessageEntity?>
    @Query("SELECT * FROM p14_chat_outbox WHERE entry_id=:entryId AND operation_type='CREATE_ENTRY_WITH_MESSAGE' ORDER BY created_at_epoch_ms ASC LIMIT 1") suspend fun captureOperation(entryId:String):P14ChatOutboxEntity?
    @Query("SELECT * FROM p14_chat_outbox WHERE message_id=:messageId AND operation_type='EDIT_SOURCE_MESSAGE' AND state NOT IN ('ACKED','SUPERSEDED') ORDER BY created_at_epoch_ms ASC,rowid ASC") suspend fun messageEdits(messageId:String):List<P14ChatOutboxEntity>
    @Query("SELECT * FROM p14_chat_outbox WHERE conversation_id=:conversationId AND state='CONFLICT' ORDER BY created_at_epoch_ms DESC") fun observeConflicts(conversationId:String):Flow<List<P14ChatOutboxEntity>>
    @Query("UPDATE p14_chat_outbox SET state=:state,last_error_class=:error WHERE client_operation_id=:id") suspend fun setMutationState(id:String,state:String,error:String?=null)

    @Query("SELECT * FROM (SELECT * FROM p14_messages WHERE conversation_id=:conversationId ORDER BY created_at_epoch_ms DESC, message_id DESC LIMIT :limit) ORDER BY created_at_epoch_ms ASC, message_id ASC") fun observeMessagesPaged(conversationId:String,limit:Int):Flow<List<P14ProductMessageEntity>>
    @Query("SELECT * FROM p14_messages WHERE conversation_id=:conversationId ORDER BY created_at_epoch_ms ASC, message_id ASC") suspend fun listMessages(conversationId: String): List<P14ProductMessageEntity>
    @Query("SELECT * FROM p14_attachments WHERE message_id=:messageId ORDER BY created_at_epoch_ms ASC") suspend fun attachmentsForMessage(messageId: String): List<P14ProductAttachmentEntity>
    @Query("SELECT * FROM p14_attachments WHERE message_id IN (SELECT message_id FROM p14_messages WHERE conversation_id=:conversationId ORDER BY created_at_epoch_ms DESC, message_id DESC LIMIT :limit) ORDER BY created_at_epoch_ms ASC") fun observeAttachmentsPaged(conversationId:String,limit:Int):Flow<List<P14ProductAttachmentEntity>>
    @Query("SELECT * FROM p14_attachments WHERE state IN ('PENDING','RETRY') ORDER BY created_at_epoch_ms ASC LIMIT :limit") suspend fun pendingAttachments(limit: Int = 20): List<P14ProductAttachmentEntity>
    @Query("UPDATE p14_attachments SET state=:state, remote_attachment_id=:remoteAttachmentId WHERE local_attachment_id=:localAttachmentId") suspend fun setAttachmentState(localAttachmentId: String, state: String, remoteAttachmentId: String?)

    @Query("SELECT * FROM p14_chat_outbox WHERE state IN ('PENDING','RETRY','IN_FLIGHT') ORDER BY created_at_epoch_ms ASC LIMIT :limit") suspend fun pendingOutbox(limit: Int = 50): List<P14ChatOutboxEntity>
    @Query("UPDATE p14_chat_outbox SET state='IN_FLIGHT', attempts=attempts+1, last_error_class=NULL WHERE client_operation_id=:id") suspend fun markOutboxInFlight(id: String)
    @Query("UPDATE p14_chat_outbox SET state='RETRY', last_error_class=:errorClass WHERE client_operation_id=:id") suspend fun markOutboxRetry(id: String, errorClass: String)
    @Query("UPDATE p14_chat_outbox SET state='ACKED', last_error_class=NULL, acked_at_epoch_ms=:ackedAt WHERE client_operation_id=:id") suspend fun markOutboxAcked(id: String, ackedAt: Long)
    @Query("UPDATE p14_chat_outbox SET state='ACKED', last_error_class=NULL, acked_at_epoch_ms=:ackedAt WHERE client_operation_id=:operationId") suspend fun ackByOperationId(operationId: String, ackedAt: Long)
    @Query("UPDATE p14_messages SET delivery_state='SYNCED' WHERE message_id=:messageId") suspend fun markMessageSynced(messageId: String)
    @Query("SELECT COUNT(*) FROM p14_chat_outbox WHERE state IN ('PENDING','RETRY','IN_FLIGHT')") fun observePendingOutboxCount(): Flow<Int>

    @Query("SELECT * FROM p14_product_sync_state WHERE scope=:scope LIMIT 1") suspend fun syncState(scope: String): P14ProductSyncStateEntity?
}

@Database(
    entities = [
        P14EntryEntity::class,
        P14ProductConversationEntity::class,
        P14ProductMessageEntity::class,
        P14ProductAttachmentEntity::class,
        P14ChatOutboxEntity::class,
        P14ProductSyncStateEntity::class,
        P14ObservationEntity::class,
        P14ObservationOutboxEntity::class,
        P14ActionEntity::class,
        P14EntryViewEntity::class,
        P14ViewOutboxEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class P14ProductDatabase : RoomDatabase() {
    abstract fun dao(): P14ProductDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existingColumns = db.query("PRAGMA table_info(`p14_conversations`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                }
                if ("pinned" !in existingColumns) {
                    db.execSQL("ALTER TABLE `p14_conversations` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
                }
                if ("archived" !in existingColumns) {
                    db.execSQL("ALTER TABLE `p14_conversations` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
                }
                if ("state_revision" !in existingColumns) {
                    db.execSQL("ALTER TABLE `p14_conversations` ADD COLUMN `state_revision` INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `p14_conversations` ADD COLUMN `assistant_id` TEXT")
                db.execSQL("ALTER TABLE `p14_conversations` ADD COLUMN `model_id` TEXT")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `p14_observations` (`observation_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `accuracy_m` REAL, `captured_at_epoch_ms` INTEGER NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`observation_id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_p14_observations_state` ON `p14_observations` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_p14_observations_captured_at_epoch_ms` ON `p14_observations` (`captured_at_epoch_ms`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `p14_observation_outbox` (`operation_id` TEXT NOT NULL, `observation_id` TEXT NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`operation_id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_p14_observation_outbox_state` ON `p14_observation_outbox` (`state`)")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `p14_actions` (`action_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `action_type` TEXT NOT NULL, `status` TEXT NOT NULL, `result_text` TEXT, `destination_ref` TEXT, `created_at_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`action_id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_p14_actions_conversation_id` ON `p14_actions` (`conversation_id`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `p14_entry_views` (`entry_id` TEXT NOT NULL, `view_name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`entry_id`,`view_name`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `p14_view_outbox` (`operation_id` TEXT NOT NULL, `entry_id` TEXT NOT NULL, `view_name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`operation_id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_p14_view_outbox_state` ON `p14_view_outbox` (`state`)")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE p14_observations ADD COLUMN profile_id TEXT NOT NULL DEFAULT 'legacy-unattributed'")
                db.execSQL("ALTER TABLE p14_observations ADD COLUMN profile_version INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE p14_observations ADD COLUMN source_device_id TEXT NOT NULL DEFAULT 'unknown'")
                db.execSQL("ALTER TABLE p14_observations ADD COLUMN permission_state TEXT NOT NULL DEFAULT 'unspecified'")
                db.execSQL("ALTER TABLE p14_observations ADD COLUMN policy_mode TEXT NOT NULL DEFAULT 'significant-change'")
                db.execSQL("ALTER TABLE p14_observations ADD COLUMN policy_min_distance_m REAL NOT NULL DEFAULT 500.0")
                db.execSQL("ALTER TABLE p14_observations ADD COLUMN retention_days INTEGER NOT NULL DEFAULT 7")
            }
        }

        @Volatile private var instance: P14ProductDatabase? = null
        fun get(context: Context): P14ProductDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                P14ProductDatabase::class.java,
                "rikkahub-p14-product.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { instance = it }
        }
    }
}
