package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName="p14_notifications", indices=[Index("state"),Index("conversation_id")])
data class P14NotificationEntity(
    @PrimaryKey @ColumnInfo(name="notification_id") val notificationId:String,
    @ColumnInfo(name="conversation_id") val conversationId:String?, val title:String,val body:String,val severity:String,
    @ColumnInfo(name="interaction_id") val interactionId:String?, val state:String,val revision:Int,
    @ColumnInfo(name="expires_at") val expiresAt:String?, @ColumnInfo(name="created_at") val createdAt:String,
    @ColumnInfo(name="updated_at") val updatedAt:String,
)
@Entity(tableName="p14_interactions", indices=[Index("status"),Index("conversation_id")])
data class P14InteractionEntity(
    @PrimaryKey @ColumnInfo(name="interaction_id") val interactionId:String,
    @ColumnInfo(name="conversation_id") val conversationId:String,
    @ColumnInfo(name="notification_id") val notificationId:String?, val prompt:String,
    @ColumnInfo(name="options_json") val optionsJson:String, val status:String,val response:String?, val actionable:Boolean,
)
@Entity(tableName="p14_operations", indices=[Index("state")])
data class P14OperationEntity(
    @PrimaryKey @ColumnInfo(name="operation_id") val operationId:String,
    @ColumnInfo(name="notification_id") val notificationId:String,
    val type:String,val payload:String,val state:String="PENDING",val attempts:Int=0,
    @ColumnInfo(name="conversation_id") val conversationId:String?=null,
    @ColumnInfo(name="action_id") val actionId:String?=null,
)
@Dao interface P14RemoteDao {
    @Upsert suspend fun upsertNotifications(items:List<P14NotificationEntity>)
    @Upsert suspend fun upsertInteraction(item:P14InteractionEntity)
    @Query("SELECT * FROM p14_notifications WHERE state != 'RESOLVED' ORDER BY created_at DESC") fun observeNotifications():Flow<List<P14NotificationEntity>>
    @Query("SELECT COUNT(*) FROM p14_notifications WHERE state='UNREAD'") fun observeUnreadCount():Flow<Int>
    @Query("SELECT * FROM p14_notifications WHERE notification_id=:id LIMIT 1") suspend fun notification(id:String):P14NotificationEntity?
    @Query("SELECT * FROM p14_interactions WHERE interaction_id=:id LIMIT 1") suspend fun interaction(id:String):P14InteractionEntity?
    @Query("UPDATE p14_interactions SET status='RESPONDED', response=:response WHERE interaction_id=:id AND status='PENDING'") suspend fun markInteractionResponded(id:String,response:String):Int
    @Query("DELETE FROM p14_notifications") suspend fun clearNotifications()
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun enqueueOperation(item:P14OperationEntity)
    @Query("SELECT * FROM p14_operations WHERE state IN ('PENDING','RETRY','IN_FLIGHT') ORDER BY rowid ASC LIMIT :limit") suspend fun pendingOperations(limit:Int=50):List<P14OperationEntity>
    @Query("UPDATE p14_operations SET state=:state, attempts=attempts+1 WHERE operation_id=:id") suspend fun setOperationState(id:String,state:String)

}
@Database(entities=[P14NotificationEntity::class,P14InteractionEntity::class,P14OperationEntity::class],version=3,exportSchema=false)
abstract class P14RemoteDatabase:RoomDatabase(){ abstract fun dao():P14RemoteDao
    companion object { @Volatile private var instance:P14RemoteDatabase?=null
        // Preserve durable notification, interaction, and pending operations on legacy v1 upgrades.
        // A missing future migration must fail closed, never clear a user's queued response.
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Retain existing tables and rows if a legacy version 1 database is encountered.
                // Add the fields introduced by v2 only when absent to preserve existing rows.
                val columns = db.query("PRAGMA table_info(`p14_interactions`)").use { cursor ->
                    val idx = cursor.getColumnIndexOrThrow("name")
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(idx)) }
                }
                if ("actionable" !in columns) {
                    db.execSQL("ALTER TABLE `p14_interactions` ADD COLUMN `actionable` INTEGER NOT NULL DEFAULT 0")
                }
            }
        }
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `p14_operations` ADD COLUMN `conversation_id` TEXT")
                db.execSQL("ALTER TABLE `p14_operations` ADD COLUMN `action_id` TEXT")
            }
        }
        fun get(context:Context)=instance?:synchronized(this){instance?:Room.databaseBuilder(context.applicationContext,P14RemoteDatabase::class.java,"rikkahub-p14-remote.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3).build().also{instance=it}}
    }
}
