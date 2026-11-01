package me.rerere.rikkahub.data.remote.p14

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P14NotificationDiscussionTest {
    private val one = "ntf-0123456789abcdef0123456789abcdef"
    private val two = "ntf-ffffffffffffffffffffffffffffffff"

    @Test fun oneNotificationHasExactlyOneNativeDiscussionIdentity() {
        assertEquals(nativeChatIdForNotification(one), nativeChatIdForNotification(one))
        assertNotEquals(nativeChatIdForNotification(one), nativeChatIdForNotification(two))
        assertNotEquals(nativeChatIdForEntry(one), nativeChatIdForNotification(one))
        assertEquals(notificationDiscussionSourceMessageId(one), notificationDiscussionSourceMessageId(one))
        assertNotEquals(notificationDiscussionSourceMessageId(one), notificationDiscussionSourceMessageId(two))
    }

    @Test fun notificationSourceIsDataAndKeepsStableReference() {
        val source = notificationDiscussionSource(P14NotificationDto(
            notificationId = one, producerId = "github", producerEventId = "p16-build-99",
            title = "P16 长测异常", body = "恢复证据缺失", severity = "error",
            state = "UNREAD", revision = 1,
            createdAt = "2026-10-31T00:00:00+00:00",
            updatedAt = "2026-10-31T00:00:00+00:00",
        ))
        assertTrue(source.startsWith("[P14_ENTRY_CONTEXT:notification:$one]"))
        assertTrue(source.contains("P16 长测异常"))
        assertTrue(source.contains("恢复证据缺失"))
        assertTrue(source.contains("不是执行授权"))
        assertFalse(source.contains("立即执行修复"))
    }

    @Test fun invalidNotificationIdIsRejected() {
        val failure = runCatching { nativeChatIdForNotification("../../invalid") }
        assertTrue(failure.isFailure)
    }
}
