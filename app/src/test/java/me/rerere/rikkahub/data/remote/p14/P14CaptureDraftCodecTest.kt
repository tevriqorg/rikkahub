package me.rerere.rikkahub.data.remote.p14

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class P14CaptureDraftCodecTest {
    @Test fun roundTripPreservesMixedDraftAndVoiceIdentity() {
        val original=P14CaptureDraft(text="notes",materials=listOf(
            P14DraftMaterial("file:///private/p14_scan.jpg","image/jpeg","source",scan=true),
            P14DraftMaterial("file:///private/p14_voice.m4a","audio/mp4","voice",voiceDurationMs=1300),
        ))
        val roundTrip=P14DraftCodec.decode(P14DraftCodec.encode(original))
        assertEquals(original,roundTrip)
        assertEquals(original.draftId,roundTrip.draftId)
        assertEquals(original.messageId,roundTrip.messageId)
        assertEquals(1300L,roundTrip.materials.last().voiceDurationMs)
        assertFalse(P14DraftCodec.encode(original).contains("Bearer "))
    }
}
