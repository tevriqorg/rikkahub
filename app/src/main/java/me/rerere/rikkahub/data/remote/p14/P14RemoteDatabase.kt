package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import androidx.room.*
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
)
@Dao interface P14RemoteDao {
    @Upsert suspend fun upsertNotifications(items:List<P14NotificationEntity>)
    @Upsert suspend fun upsertInteraction(item:P14InteractionEntity)
    @Query("SELECT * FROM p14_notifications WHERE state != 'RESOLVED' ORDER BY created_at DESC") fun observeNotifications():Flow<List<P14NotificationEntity>>
    @Query("SELECT COUNT(*) FROM p14_notifications WHERE state='UNREAD'") fun observeUnreadCount():Flow<Int>
    @Query("SELECT * FROM p14_notifications WHERE notification_id=:id LIMIT 1") suspend fun notification(id:String):P14NotificationEntity?
    @Query("SELECT * FROM p14_interactions WHERE interaction_id=:id LIMIT 1") suspend fun interaction(id:String):P14InteractionEntity?
    @Query("DELETE FROM p14_notifications") suspend fun clearNotifications()
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun enqueueOperation(item:P14OperationEntity)
    @Query("SELECT * FROM p14_operations WHERE state IN ('PENDING','RETRY') ORDER BY rowid ASC LIMIT :limit") suspend fun pendingOperations(limit:Int=50):List<P14OperationEntity>
    @Query("UPDATE p14_operations SET state=:state, attempts=attempts+1 WHERE operation_id=:id") suspend fun setOperationState(id:String,state:String)

}
@Database(entities=[P14NotificationEntity::class,P14InteractionEntity::class,P14OperationEntity::class],version=2,exportSchema=false)
abstract class P14RemoteDatabase:RoomDatabase(){ abstract fun dao():P14RemoteDao
    companion object { @Volatile private var instance:P14RemoteDatabase?=null
        fun get(context:Context)=instance?:synchronized(this){instance?:Room.databaseBuilder(context.applicationContext,P14RemoteDatabase::class.java,"rikkahub-p14-remote.db").fallbackToDestructiveMigration().build().also{instance=it}}
    }
}
