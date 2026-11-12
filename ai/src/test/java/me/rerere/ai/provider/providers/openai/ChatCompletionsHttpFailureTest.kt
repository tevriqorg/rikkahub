package me.rerere.ai.provider.providers.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChatCompletionsHttpFailureTest {
    @Test fun plainText500ReportsHttpStatusInsteadOfJsonDecoderError() {
        val error = parseChatCompletionHttpFailure(500, "Internal Server Error", null)
        assertTrue(error is IOException)
        assertTrue(error.message.orEmpty().contains("HTTP 500"))
        assertTrue(error.message.orEmpty().contains("Internal Server Error"))
        assertFalse(error.toString().contains("JsonDecodingException"))
    }

    @Test fun html502IsNotLeakedToUser() {
        val error = parseChatCompletionHttpFailure(502, "<html>gateway error</html>", null)
        assertEquals("HTTP 502", error.message)
    }

    @Test fun transportFailurePreservesCauseWhenNoResponse() {
        val cause = IOException("Connection reset")
        val error = parseChatCompletionHttpFailure(null, null, cause)
        assertEquals(cause, error.cause)
        assertTrue(error.message.orEmpty().contains("Connection reset"))
    }
}
