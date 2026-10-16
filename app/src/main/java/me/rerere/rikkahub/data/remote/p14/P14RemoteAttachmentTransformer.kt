package me.rerere.rikkahub.data.remote.p14

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.AssistantRuntimeMode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.RandomAccessFile
import java.net.URLConnection
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

internal class P14RemoteAttachmentTransformer(
    private val filesManager: FilesManager,
    httpClient: OkHttpClient,
) : InputMessageTransformer {
    private val client = httpClient.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cachedRecipientKey: String? = null

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (ctx.assistant.runtimeMode != AssistantRuntimeMode.REMOTE || ctx.model.modelId != "p14-hermes") {
            return messages
        }
        val provider = ctx.model.findProvider(ctx.settings.providers) as? ProviderSetting.OpenAI ?: return messages
        val userIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (userIndex < 0) return messages
        val user = messages[userIndex]
        val attachmentCount = user.parts.count { it.isRemoteAttachment() }
        if (attachmentCount == 0) return listOf(user)
        require(attachmentCount <= 20) { "P14 Remote supports at most 20 attachments per message" }

        return withContext(Dispatchers.IO) {
            val recipientKey = getRecipientKey(provider)
            val completed = AtomicInteger(0)
            val attachmentSemaphore = Semaphore(REMOTE_ATTACHMENT_CONCURRENCY)
            val transformedParts = coroutineScope {
                user.parts.mapIndexed { index, part ->
                    async {
                        if (!part.isRemoteAttachment() || part.p14AttachmentId() != null) {
                            return@async index to part
                        }
                        attachmentSemaphore.withPermit {
                            val source = resolveSource(ctx, part)
                            val encryptedDir = File(ctx.context.cacheDir, "p14-remote").apply { mkdirs() }
                            val cipher = File(encryptedDir, "${Uuid.random()}.p20e2ee")
                            try {
                                ctx.processingStatus.value =
                                    "Remote: encrypting ${source.displayName} · ${completed.get()}/$attachmentCount complete"
                                val encrypted = P20E2EE.encrypt(
                                    source = source.file,
                                    destination = cipher,
                                    recipientPublicKey = recipientKey,
                                    displayName = source.displayName,
                                    mimeType = source.mimeType,
                                )
                                ctx.processingStatus.value =
                                    "Remote: uploading ${source.displayName} · ${completed.get()}/$attachmentCount complete"
                                val mediaRole = part.metadata?.get(P14_MEDIA_ROLE)?.jsonPrimitive?.contentOrNull
                                val durationMs = part.metadata?.get(P14_DURATION_MS)?.jsonPrimitive?.longOrNull
                                val attachmentId = uploadAndImport(
                                    provider = provider,
                                    encrypted = encrypted,
                                    mediaRole = mediaRole,
                                    durationMs = durationMs,
                                ) { doneParts, totalParts ->
                                    ctx.processingStatus.value =
                                        "Remote: uploading ${source.displayName} · part $doneParts/$totalParts"
                                }
                                val done = completed.incrementAndGet()
                                ctx.processingStatus.value = "Remote: attachments $done/$attachmentCount ready"
                                index to part.withP14AttachmentId(attachmentId)
                            } finally {
                                cipher.delete()
                            }
                        }
                    }
                }.awaitAll().sortedBy { it.first }.map { it.second }
            }
            listOf(user.copy(parts = transformedParts))
        }
    }

    private suspend fun resolveSource(ctx: TransformerContext, part: UIMessagePart): AttachmentSource {
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Document -> part.url
            else -> error("Unsupported attachment part")
        }
        require(url.startsWith("file:")) { "P14 Remote attachment must be a local file" }
        val file = url.toUri().toFile()
        require(file.isFile) { "Attachment file is missing" }
        val relativePath = FileUtils.getRelativePathInFilesDir(ctx.context.filesDir, file)
        val managed = relativePath?.let { filesManager.getByRelativePath(it) }
        val displayName = when (part) {
            is UIMessagePart.Document -> part.fileName.takeIf { it.isNotBlank() }
            else -> managed?.displayName
        } ?: file.name
        val mimeType = when (part) {
            is UIMessagePart.Document -> part.mime.takeIf { it.isNotBlank() && it != "text/*" }
            else -> managed?.mimeType?.takeIf { it.isNotBlank() }
        } ?: URLConnection.guessContentTypeFromName(displayName) ?: when (part) {
            is UIMessagePart.Image -> "image/jpeg"
            is UIMessagePart.Video -> "video/mp4"
            is UIMessagePart.Audio -> "audio/mpeg"
            else -> "application/octet-stream"
        }
        return AttachmentSource(file, displayName, mimeType)
    }

    private fun getRecipientKey(provider: ProviderSetting.OpenAI): String {
        cachedRecipientKey?.let { return it }
        val response = control(provider, "GET", "/remote-attachments/capabilities")
        val max = response["max_plaintext_bytes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (max != null && max <= 0L) error("P14 Remote reported an invalid attachment limit")
        val key = response["recipient_key"]?.jsonObject?.get("key")?.jsonPrimitive?.contentOrNull
            ?: error("P14 Remote did not return a receiver public key")
        cachedRecipientKey = key
        return key
    }

    private suspend fun uploadAndImport(
        provider: ProviderSetting.OpenAI,
        encrypted: P20E2EE.EncryptResult,
        mediaRole: String?,
        durationMs: Long?,
        onPartProgress: (Int, Int) -> Unit,
    ): String {
        val created = control(
            provider,
            "POST",
            "/remote-attachments",
            buildJsonObject {
                put("ciphertext_size", encrypted.size)
                put("ciphertext_sha256", encrypted.sha256)
                mediaRole?.let { put("media_role", it) }
                durationMs?.let { put("duration_ms", it) }
            },
        )
        val transitId = created["transit_id"]?.jsonPrimitive?.contentOrNull ?: error("P14 transit id missing")
        val partSize = created["part_size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: error("P14 part size missing")
        val partCount = created["part_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: error("P14 part count missing")
        val initial = parsePartUrls(created)
        val urls = initial.toMutableMap()
        val missing = (1..partCount).filterNot(urls::containsKey)
        missing.chunked(100).forEach { batch ->
            val issued = control(
                provider,
                "POST",
                "/remote-attachments/$transitId/parts",
                buildJsonObject {
                    put("part_numbers", kotlinx.serialization.json.buildJsonArray { batch.forEach { add(JsonPrimitive(it)) } })
                },
            )
            urls.putAll(parsePartUrls(issued))
        }

        val completedParts = AtomicInteger(0)
        for (partNumber in 1..partCount) {
                        val offset = (partNumber - 1L) * partSize
                        val length = minOf(partSize, encrypted.size - offset)
                        var uploadUrl = urls[partNumber] ?: error("P14 did not issue upload URL for part $partNumber")
                        var response = putPart(uploadUrl, encrypted.ciphertext, offset, length)
                        if (!response) {
                            val refreshed = control(
                                provider,
                                "POST",
                                "/remote-attachments/$transitId/parts",
                                buildJsonObject {
                                    put("part_numbers", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(partNumber)) })
                                },
                            )
                            uploadUrl = parsePartUrls(refreshed)[partNumber] ?: error("P14 failed to refresh upload URL")
                            response = putPart(uploadUrl, encrypted.ciphertext, offset, length)
                        }
                        check(response) { "CMCloud upload failed for part $partNumber" }
            onPartProgress(completedParts.incrementAndGet(), partCount)
        }

        val completed = control(provider, "POST", "/remote-attachments/$transitId/complete", buildJsonObject {})
        return completed["attachment"]?.jsonObject?.get("attachment_id")?.jsonPrimitive?.contentOrNull
            ?: error("P14 did not return imported attachment id")
    }

    private fun parsePartUrls(payload: JsonObject): Map<Int, String> = buildMap {
        payload["parts"]?.jsonArray?.forEach { item ->
            val obj = item.jsonObject
            val number = obj["part_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@forEach
            val url = obj["upload_url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            put(number, url)
        }
    }

    private fun putPart(url: String, file: File, offset: Long, length: Long): Boolean {
        val body = FileSliceRequestBody(file, offset, length)
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/octet-stream")
            .header("Origin", "https://yun.139.com")
            .header("Referer", "https://yun.139.com/")
            .put(body)
            .build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    private fun control(
        provider: ProviderSetting.OpenAI,
        method: String,
        path: String,
        body: JsonObject? = null,
    ): JsonObject {
        val builder = Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Accept", "application/json")
        if (method == "POST") {
            builder.post((body ?: buildJsonObject {}).toString().toRequestBody("application/json".toMediaType()))
        } else {
            builder.get()
        }
        val response = client.newCall(builder.build()).execute()
        response.use {
            val text = it.body.string()
            check(it.isSuccessful) { "P14 Remote attachment request failed: HTTP ${it.code} $text" }
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private fun UIMessagePart.isRemoteAttachment(): Boolean =
        this is UIMessagePart.Image || this is UIMessagePart.Video || this is UIMessagePart.Audio || this is UIMessagePart.Document

    private fun UIMessagePart.p14AttachmentId(): String? =
        metadata?.get(P14_ATTACHMENT_ID)?.jsonPrimitive?.contentOrNull

    private fun UIMessagePart.withP14AttachmentId(attachmentId: String): UIMessagePart {
        val merged = buildJsonObject {
            metadata?.forEach { (key, value) -> put(key, value) }
            put(P14_ATTACHMENT_ID, attachmentId)
        }
        return when (this) {
            is UIMessagePart.Image -> copy(metadata = merged)
            is UIMessagePart.Video -> copy(metadata = merged)
            is UIMessagePart.Audio -> copy(metadata = merged)
            is UIMessagePart.Document -> copy(metadata = merged)
            else -> this
        }
    }

    private data class AttachmentSource(val file: File, val displayName: String, val mimeType: String)

    private class FileSliceRequestBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                var remaining = length
                val buffer = ByteArray(1024 * 1024)
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(count > 0) { "Encrypted attachment ended during upload" }
                    sink.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    }

    private companion object {
        const val P14_ATTACHMENT_ID = "p14_attachment_id"
        const val P14_MEDIA_ROLE = "p14_media_role"
        const val P14_DURATION_MS = "p14_duration_ms"
        const val REMOTE_ATTACHMENT_CONCURRENCY = 3
    }
}
