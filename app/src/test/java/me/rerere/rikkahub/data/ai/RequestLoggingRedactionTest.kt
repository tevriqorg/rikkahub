package me.rerere.rikkahub.data.ai

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RequestLoggingRedactionTest {
    @Test fun deviceBearerCannotBePersistedEvenWithMixedCaseHeaders() {
        val headers = Headers.Builder()
            .add("Authorization", "Bearer secret-device-token")
            .add("proxy-authorization", "Basic secret-proxy")
            .add("X-API-Key", "secret-key")
            .add("Accept", "application/json")
            .build()
        val safe = headers.toSafeLogMap()
        assertEquals("██", safe["Authorization"])
        assertEquals("██", safe["proxy-authorization"])
        assertEquals("██", safe["X-API-Key"])
        assertEquals("application/json", safe["Accept"])
        assertFalse(safe.values.any { it.contains("secret") })
    }
}
