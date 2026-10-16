package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Durable attachment transport shared by foreground generation and WorkManager recovery. */
internal class P14DurableAttachmentUploader(
    context: Context,
    httpClient: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val dao = P14ProductDatabase.get(appContext).dao()
    private val client = httpClient.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cachedRecipientKey: String? = null

    suspend fun uploadPending(connection: P14RuntimeConnection) {
        for (attachment in dao.pendingAttachments()) {
            try {
                val remoteId = upload(connection, attachment)
                dao.setAttachmentState(attachment.localAttachmentId, "SYNCED", remoteId)
            } catch (_: Throwable) {
                dao.setAttachmentState(attachment.localAttachmentId, "RETRY", null)
            }
        }
    }

    suspend fun ensureUploaded(connection: P14RuntimeConnection, attachment: P14ProductAttachmentEntity): String {
        attachment.remoteAttachmentId?.takeIf { attachment.state == "SYNCED" }?.let { return it }
        return try {
            val remoteId = upload(connection, attachment)
            dao.setAttachmentState(attachment.localAttachmentId, "SYNCED", remoteId)
            remoteId
        } catch (error: Throwable) {
            dao.setAttachmentState(attachment.localAttachmentId, "RETRY", null)
            throw error
        }
    }

    private fun upload(connection: P14RuntimeConnection, attachment: P14ProductAttachmentEntity): String {
        require(attachment.localLocator.startsWith("file:")) { "P14 durable attachment must be a managed local file" }
        val source = attachment.localLocator.toUri().toFile()
        require(source.isFile) { "P14 durable attachment file is missing" }
        val recipientKey = recipientKey(connection)
        val encryptedDir = File(appContext.cacheDir, "p14-durable").apply { mkdirs() }
        val cipher = File.createTempFile("attachment-", ".p20e2ee", encryptedDir)
        try {
            val encrypted = P20E2EE.encrypt(
                source = source,
                destination = cipher,
                recipientPublicKey = recipientKey,
                displayName = attachment.fileName,
                mimeType = attachment.mimeType,
            )
            val created = control(
                connection,
                "POST",
                "/remote-attachments",
                buildJsonObject {
                    put("ciphertext_size", encrypted.size)
                    put("ciphertext_sha256", encrypted.sha256)
                    val mediaRole = when (attachment.kind) {
                        "VOICE" -> "voice"
                        "AUDIO" -> "audio"
                        else -> null
                    }
                    mediaRole?.let { put("media_role", it) }
                    attachment.durationMs?.let { put("duration_ms", it) }
                },
            )
            val transitId = created.requireString("transit_id")
            val partSize = created["part_size"]?.jsonPrimitive?.longOrNull ?: error("P14 part size missing")
            val partCount = created["part_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: error("P14 part count missing")
            val urls = parsePartUrls(created).toMutableMap()
            (1..partCount).filterNot(urls::containsKey).chunked(100).forEach { batch ->
                val issued = control(
                    connection,
                    "POST",
                    "/remote-attachments/$transitId/parts",
                    buildJsonObject { put("part_numbers", buildJsonArray { batch.forEach { add(JsonPrimitive(it)) } }) },
                )
                urls.putAll(parsePartUrls(issued))
            }
            for (partNumber in 1..partCount) {
                val offset = (partNumber - 1L) * partSize
                val length = minOf(partSize, encrypted.size - offset)
                var uploadUrl = urls[partNumber] ?: error("P14 upload URL missing")
                if (!putPart(uploadUrl, encrypted.ciphertext, offset, length)) {
                    val refreshed = control(
                        connection,
                        "POST",
                        "/remote-attachments/$transitId/parts",
                        buildJsonObject { put("part_numbers", buildJsonArray { add(JsonPrimitive(partNumber)) }) },
                    )
                    uploadUrl = parsePartUrls(refreshed)[partNumber] ?: error("P14 refreshed upload URL missing")
                    check(putPart(uploadUrl, encrypted.ciphertext, offset, length)) { "P14 durable attachment upload failed" }
                }
            }
            val completed = control(connection, "POST", "/remote-attachments/$transitId/complete", buildJsonObject {})
            return completed["attachment"]?.jsonObject?.get("attachment_id")?.jsonPrimitive?.contentOrNull
                ?: error("P14 imported attachment id missing")
        } finally {
            cipher.delete()
        }
    }

    private fun recipientKey(connection: P14RuntimeConnection): String {
        cachedRecipientKey?.let { return it }
        val response = control(connection, "GET", "/remote-attachments/capabilities")
        return response["recipient_key"]?.jsonObject?.get("key")?.jsonPrimitive?.contentOrNull
            ?.also { cachedRecipientKey = it }
            ?: error("P14 recipient key missing")
    }

    private fun parsePartUrls(payload: JsonObject): Map<Int, String> = buildMap {
        payload["parts"]?.jsonArray?.forEach { item ->
            val obj = item.jsonObject
            val number = obj["part_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@forEach
            val url = obj["upload_url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            put(number, url)
        }
    }

    private fun control(connection: P14RuntimeConnection, method: String, path: String, body: JsonObject? = null): JsonObject {
        val builder = Request.Builder()
            .url("${connection.baseUrl.trimEnd('/')}${path}")
            .header("Authorization", "Bearer ${connection.bearerToken}")
            .header("Accept", "application/json")
        if (method == "POST") builder.post((body ?: buildJsonObject {}).toString().toRequestBody(JSON_MEDIA)) else builder.get()
        return client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw P14RemoteHttpException(response.code, text.take(800))
            json.parseToJsonElement(text).jsonObject
        }
    }

    private fun putPart(url: String, file: File, offset: Long, length: Long): Boolean {
        val request = Request.Builder().url(url)
            .header("Content-Type", "application/octet-stream")
            .header("Origin", "https://yun.139.com")
            .header("Referer", "https://yun.139.com/")
            .put(FileSliceRequestBody(file, offset, length)).build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    private class FileSliceRequestBody(private val file: File, private val offset: Long, private val length: Long) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = length
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

    private fun JsonObject.requireString(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("P14 response omitted $key")
    private companion object { val JSON_MEDIA = "application/json; charset=utf-8".toMediaType() }
}
