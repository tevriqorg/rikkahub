package me.rerere.rikkahub.data.remote.p14

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class P14CapabilityRegistryTest {
    private val config="""schema: p14.profile/v1
id: test
version: 1
capture:
  voice: {enabled: true, required: false}
  scan: {enabled: false}
  uncompiled: {enabled: true, required: false}
observation:
  location: {enabled: true, required: false}
"""
    @Test fun effectiveStatusDoesNotInventRuntimeOrGrantPermission() {
        val items=P14CapabilityRegistry.resolve(config,emptySet()).associateBy { it.id }
        assertEquals(P14Availability.PERMISSION_REQUIRED,items["voice"]?.state)
        assertEquals(P14Availability.DISABLED,items["scan"]?.state)
        assertEquals(P14Availability.UNSUPPORTED,items["uncompiled"]?.state)
        assertEquals(P14Availability.PERMISSION_REQUIRED,items["observation.location"]?.state)
        assertEquals(P14Availability.READY,P14CapabilityRegistry.resolve(config,setOf("MICROPHONE","LOCATION")).first { it.id=="voice" }.state)
    }
    @Test fun requiredUncompiledFailsWithoutActivating() {
        assertThrows(IllegalArgumentException::class.java) {
            P14CapabilityRegistry.activation(config.replace("uncompiled: {enabled: true, required: false}","uncompiled: {enabled: true, required: true}"),emptySet())
        }
    }
}
