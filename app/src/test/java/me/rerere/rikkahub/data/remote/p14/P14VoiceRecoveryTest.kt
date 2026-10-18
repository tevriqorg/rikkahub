package me.rerere.rikkahub.data.remote.p14

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class P14VoiceRecoveryTest {
    @Test fun voiceMetadataPreservesTrueVoiceInDurableOutbox() {
        val voice = UIMessagePart.Audio("file:///voice.m4a",buildJsonObject{put("p14_media_role","voice_message")})
        val importedAudio = UIMessagePart.Audio("file:///song.mp3")
        assertEquals("VOICE",durableP14AudioKind(voice))
        assertEquals("AUDIO",durableP14AudioKind(importedAudio))
    }
}
