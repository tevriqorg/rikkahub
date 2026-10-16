package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal object P14ManifestPolicy {
    private val json=Json { ignoreUnknownKeys=true; explicitNulls=false }

    fun load(context:Context,connection:P14RuntimeConnection,http:OkHttpClient):P14EffectiveManifest = runCatching {
        val url=connection.baseUrl.toHttpUrl().newBuilder().addPathSegment("remote-manifest")
            .addQueryParameter("client_protocol",P14RikkaCapabilities.PROTOCOL.toString()).build()
        val request=Request.Builder().url(url).header("Authorization","Bearer ${connection.bearerToken}").get().build()
        val body=http.newCall(request).execute().use { response ->
            val text=response.body?.string().orEmpty()
            if(!response.isSuccessful) throw P14RemoteHttpException(response.code,text.take(800))
            text
        }
        val dto=json.decodeFromString<P14ManifestDto>(body)
        context.getSharedPreferences("p14-manifest",Context.MODE_PRIVATE).edit()
            .putString(connection.baseUrl,json.encodeToString(dto)).apply()
        dto.effective()
    }.getOrElse {
        val raw=context.getSharedPreferences("p14-manifest",Context.MODE_PRIVATE).getString(connection.baseUrl,null)
        if(raw!=null) runCatching { json.decodeFromString<P14ManifestDto>(raw).effective("LAST_KNOWN_GOOD") }
            .getOrElse { fallbackP14Manifest() }
        else fallbackP14Manifest()
    }

    fun requireChat(manifest:P14EffectiveManifest) {
        require(manifest.compatibility!="UPGRADE_REQUIRED") { "P14 Server requires a newer client" }
        require("chat" in manifest.enabledViews && "text" in manifest.messageCapabilities) { "P14 Manifest disabled remote chat" }
    }

    fun requireAttachment(manifest:P14EffectiveManifest,kind:String,size:Long?) {
        val capability=when(kind.uppercase()) {
            "IMAGE" -> "image"
            "VOICE" -> "voice"
            "AUDIO" -> "audio"
            "VIDEO" -> "video"
            else -> "file"
        }
        require(capability in manifest.messageCapabilities) { "P14 Manifest disabled $capability attachments" }
        if(size!=null) require(size<=manifest.maxAttachmentBytes) { "Attachment exceeds P14 Manifest upload limit" }
    }
}
