package me.rerere.rikkahub.data.remote.p14

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class P14NotificationDto(
    @SerialName("notification_id") val notificationId:String,
    @SerialName("producer_id") val producerId:String,
    @SerialName("producer_event_id") val producerEventId:String,
    @SerialName("conversation_id") val conversationId:String?=null,
    val title:String, val body:String, val severity:String,
    @SerialName("context_refs") val contextRefs:JsonObject=JsonObject(emptyMap()),
    val provenance:JsonObject=JsonObject(emptyMap()),
    @SerialName("interaction_id") val interactionId:String?=null,
    val state:String, val revision:Int,
    @SerialName("expires_at") val expiresAt:String?=null,
    @SerialName("created_at") val createdAt:String,
    @SerialName("updated_at") val updatedAt:String,
)
@Serializable data class P14InteractionOption(val id:String,val label:String,val style:String?=null)
@Serializable data class P14InteractionDto(
    @SerialName("interaction_id") val interactionId:String,
    @SerialName("conversation_id") val conversationId:String,
    val kind:String,
    @SerialName("prompt_text") val promptText:String,
    val options:List<P14InteractionOption> = emptyList(),
    @SerialName("allow_free_text") val allowFreeText:Boolean=false,
    val status:String,val response:String?=null,
    @SerialName("created_at") val createdAt:String,
    @SerialName("responded_at") val respondedAt:String?=null,
)
@Serializable data class P14InteractionContext(
    @SerialName("notification_id") val notificationId:String,
    val interaction:P14InteractionDto,
    val actionable:Boolean,
    val reason:String?=null,
)
@Serializable data class P14ManifestDto(
    @SerialName("schema_version") val schemaVersion:String,
    @SerialName("manifest_version") val manifestVersion:Int,
    @SerialName("minimum_client_protocol") val minimumClientProtocol:Int,
    @SerialName("maximum_client_protocol") val maximumClientProtocol:Int,
    val compatibility:String,
    @SerialName("enabled_views") val enabledViews:List<String> = emptyList(),
    @SerialName("message_capabilities") val messageCapabilities:List<String> = emptyList(),
    @SerialName("quick_action_types") val quickActionTypes:List<String> = emptyList(),
    @SerialName("upload_limits") val uploadLimits:P14UploadLimits=P14UploadLimits(),
    @SerialName("feature_flags") val featureFlags:Map<String,Boolean> = emptyMap(),
)
@Serializable data class P14UploadLimits(
    @SerialName("max_attachment_bytes") val maxAttachmentBytes:Long=512L*1024*1024,
    @SerialName("max_attachments_per_message") val maxAttachmentsPerMessage:Int=20,
)
data class P14EffectiveManifest(
    val source:String,val compatibility:String,val enabledViews:Set<String>,val messageCapabilities:Set<String>,
    val quickActionTypes:List<String>,val maxAttachmentBytes:Long,val maxAttachmentsPerMessage:Int,
)
object P14RikkaCapabilities {
    const val PROTOCOL=1; const val SCHEMA="p14.remote-manifest/v1"
    val views=setOf("chat","inbox","activity")
    val messages=setOf("text","image","voice","audio","video","file","reply","edit","delete","quick_action","notification_interaction")
}
fun P14ManifestDto.effective(source:String="REMOTE"):P14EffectiveManifest {
    require(schemaVersion==P14RikkaCapabilities.SCHEMA)
    val caps=messageCapabilities.toSet().intersect(P14RikkaCapabilities.messages)
    return P14EffectiveManifest(source,compatibility,enabledViews.toSet().intersect(P14RikkaCapabilities.views),
        if(compatibility=="UPGRADE_REQUIRED") emptySet() else caps,
        if(compatibility=="UPGRADE_REQUIRED" || "quick_action" !in caps) emptyList() else quickActionTypes.distinct(),
        uploadLimits.maxAttachmentBytes.coerceAtLeast(1),uploadLimits.maxAttachmentsPerMessage.coerceIn(1,100))
}
fun fallbackP14Manifest()=P14EffectiveManifest("BUILTIN_FALLBACK","LIMITED_MODE",setOf("chat","inbox"),setOf("text"),emptyList(),16L*1024*1024,1)
