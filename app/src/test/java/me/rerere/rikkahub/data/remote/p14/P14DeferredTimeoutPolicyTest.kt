package me.rerere.rikkahub.data.remote.p14

import me.rerere.rikkahub.service.isP14DeferredTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P14DeferredTimeoutPolicyTest {
    @Test fun onlyExplicitP14RemoteTimeoutIsRecoverable() {
        assertTrue(isP14DeferredTimeout(IllegalStateException(
            "Hermes remote loop did not return a final reply before timeout")))
        assertTrue(isP14DeferredTimeout(RuntimeException("transport",IllegalStateException("timeout_error"))))
        assertFalse(isP14DeferredTimeout(IllegalStateException("Provider 401 invalid credential")))
        assertFalse(isP14DeferredTimeout(IllegalStateException("Network timeout while syncing attachments")))
        assertFalse(isP14DeferredTimeout(IllegalStateException("User paused message queue")))
    }
}
