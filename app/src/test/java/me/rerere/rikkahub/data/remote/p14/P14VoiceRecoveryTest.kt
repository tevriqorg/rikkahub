package me.rerere.rikkahub.data.remote.p14

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class P14VoiceRecoveryTest {
    @Test fun voiceMetadataPreservesTrueVoiceInDurableOutbox() {
        val voice = UIMessagePart.Audio("file:///voice.m4a",buildJsonObject{put("p14_media_role","voice_message")})
        val importedAudio = UIMessagePart.Audio("file:///song.mp3")
        assertEquals("VOICE",durableP14AudioKind(voice))
        assertEquals("AUDIO",durableP14AudioKind(importedAudio))
    }

    @Test fun recoveringAcceptedVoiceKeepsVoiceMetadataForChatProjection() {
        val voice = P14ProductAttachmentEntity(
            localAttachmentId="att",remoteAttachmentId=null,messageId="m",conversationId="c",kind="VOICE",
            mimeType="audio/mp4",fileName="voice.m4a",localLocator="file:///private/voice.m4a",
            byteSize=120L,durationMs=1300,state="PENDING",createdAtEpochMs=1L,
        )
        val restored = recoverP14AttachmentPart(voice) as UIMessagePart.Audio
        assertNotNull(restored.metadata)
        assertEquals("voice_message", restored.metadata?.get("p14_media_role")?.jsonPrimitive?.content)
        assertEquals("VOICE", durableP14AudioKind(restored))
    }
}
