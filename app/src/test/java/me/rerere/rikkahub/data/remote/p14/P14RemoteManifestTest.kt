package me.rerere.rikkahub.data.remote.p14

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P14RemoteManifestTest {
    @Test
    fun remoteManifestOnlyEnablesCapabilitiesCompiledIntoRikkaHub() {
        val effective = manifest(
            views = listOf("chat", "inbox", "remote-code"),
            capabilities = listOf("text", "image", "download-and-execute-plugin"),
        ).effective()

        assertEquals(setOf("chat", "inbox"), effective.enabledViews)
        assertEquals(setOf("text", "image"), effective.messageCapabilities)
        assertFalse("remote-code" in effective.enabledViews)
        assertFalse("download-and-execute-plugin" in effective.messageCapabilities)
    }

    @Test
    fun upgradeRequiredIsExplicitReadOnlyMode() {
        val effective = manifest(compatibility = "UPGRADE_REQUIRED").effective()
        assertEquals("UPGRADE_REQUIRED", effective.compatibility)
        assertTrue(effective.messageCapabilities.isEmpty())
    }


    @Test
    fun quickActionsComeOnlyFromCompatibleServerManifest() {
        val enabled = manifest(
            capabilities = listOf("text", "quick_action"),
            quickActions = listOf("content.accept", "test.retry"),
        ).effective()
        assertEquals(listOf("content.accept", "test.retry"), enabled.quickActionTypes)

        val upgrade = manifest(
            compatibility = "UPGRADE_REQUIRED",
            capabilities = listOf("text", "quick_action"),
            quickActions = listOf("content.accept"),
        ).effective()
        assertTrue(upgrade.quickActionTypes.isEmpty())
    }

    @Test
    fun manifestLimitsAttachmentTypeAndSize() {
        val effective = manifest(capabilities = listOf("text", "image")).effective()
        P14ManifestPolicy.requireAttachment(effective, "IMAGE", 1024)
        assertTrue(runCatching { P14ManifestPolicy.requireAttachment(effective, "VIDEO", 1024) }.isFailure)
        assertTrue(runCatching { P14ManifestPolicy.requireAttachment(effective, "IMAGE", effective.maxAttachmentBytes + 1) }.isFailure)
    }

    @Test
    fun builtinFallbackIsConservativeAndExplicit() {
        val fallback = fallbackP14Manifest()
        assertEquals("BUILTIN_FALLBACK", fallback.source)
        assertEquals("LIMITED_MODE", fallback.compatibility)
        assertEquals(setOf("text"), fallback.messageCapabilities)
        assertEquals(setOf("chat", "inbox"), fallback.enabledViews)
        assertEquals(16L * 1024 * 1024, fallback.maxAttachmentBytes)
        assertEquals(1, fallback.maxAttachmentsPerMessage)
    }

    private fun manifest(
        compatibility: String = "COMPATIBLE",
        views: List<String> = listOf("chat", "inbox", "activity"),
        capabilities: List<String> = listOf("text", "image", "voice", "file"),
        quickActions: List<String> = emptyList(),
    ) = P14ManifestDto(
        schemaVersion = P14RikkaCapabilities.SCHEMA,
        manifestVersion = 7,
        minimumClientProtocol = 1,
        maximumClientProtocol = 1,
        compatibility = compatibility,
        enabledViews = views,
        messageCapabilities = capabilities,
        quickActionTypes = quickActions,
    )
}
