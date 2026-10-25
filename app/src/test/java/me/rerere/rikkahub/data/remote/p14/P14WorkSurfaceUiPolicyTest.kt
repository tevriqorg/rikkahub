package me.rerere.rikkahub.data.remote.p14

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VIEWS-001: app View cards retain local-first classification and stable identity. */
class P14WorkSurfaceUiPolicyTest {
    @Test fun entryFirstShowsLocalOnceButKeepsUnseenRemoteEntries() {
        val items=listOf(
            P14WorkItem("entry",null,"entry-a","conv-a","idea","captured"),
            P14WorkItem("entry","entry-b","entry-b","conv-b","idea","captured"),
            P14WorkItem("action","action-1","entry-a","conv-a","todo.create","pending")
        )
        val shown=p14VisibleWorkItems(items,setOf("entry-a"))
        assertEquals(2,shown.size)
        assertFalse(shown.any { it.source=="entry" && it.entryId=="entry-a" })
        assertTrue(shown.any { it.source=="entry" && it.entryId=="entry-b" })
        assertTrue(shown.any { it.source=="action" && it.entryId=="entry-a" })
    }

    @Test fun multipleIdeaEntriesWithoutSourceIdsHaveDistinctKeys() {
        val first=P14WorkItem("entry",null,"entry-a","conv-a","idea","captured")
        val second=P14WorkItem("entry",null,"entry-b","conv-b","idea","captured")
        assertEquals("work:entry:entry-a",first.stableListKey())
        assertEquals("work:entry:entry-b",second.stableListKey())
        assertTrue(first.stableListKey()!=second.stableListKey())
        assertEquals("work:action:action-1",
            P14WorkItem("action","action-1","entry-a","conv-a","todo.create","pending").stableListKey())
    }

    @Test fun unseenRemoteTodoEntryAppearsEvenBeforeLocalSync() {
        val remote=P14WorkItem("entry","entry-todo","entry-todo","conv-todo","todo","filed")
        assertEquals(listOf(remote),p14VisibleWorkItems(listOf(remote),emptySet()))
        assertTrue(p14VisibleWorkItems(listOf(remote),setOf("entry-todo")).isEmpty())
    }
}
