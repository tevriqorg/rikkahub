package me.rerere.rikkahub.data.remote.p14

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class P14ProfileContractTest {
    private val base="""
      schema: p14.profile/v1
      id: test
      version: 1
      capture:
        draft_mode: single-active
        text: {enabled: true}
      observation:
        location:
          enabled: false
          policy: {mode: significant-change, min_distance_m: 500}
          retention: {local_days: 7}
    """.trimIndent()

    private fun rejects(source:String) {
        try { P14ProfileContract.validate(source); fail("Accepted unsafe Profile") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun supportedProfileIsValidAndLocationDefaultsOff() {
        val result=P14ProfileContract.validate(base)
        assertTrue(result.unsupportedOptional.isEmpty())
        assertEquals(false,result.locationEnabled)
        assertEquals(7,result.localRetentionDays)
    }
    @Test fun optionalUnknownCapabilityDoesNotBecomeExecutable() {
        val result=P14ProfileContract.validate(base.replace("  text: {enabled: true}","  text: {enabled: true}\n  lidar: {enabled: true, required: false}"))
        assertTrue("lidar" in result.unsupportedOptional)
    }
    @Test fun unsafeOrRequiredUnsupportedConfigFailsClosed() {
        rejects(base.replace("text: {enabled: true}","lidar: {enabled: true, required: true}"))
        rejects(base.replace("min_distance_m: 500","min_distance_m: 1"))
        rejects(base.replace("mode: significant-change","mode: continuous-gps"))
        rejects(base.replace("local_days: 7","local_days: 90"))
        rejects(base+"\ntoken: do-not-ever-import")
        rejects(base.replace("p14.profile/v1","p14.profile/v2"))
        rejects(base+"\nnew_runtime: {required: true, enabled: true}")
    }
    @Test(timeout=60000) fun concurrentPoliciesRemainIndependentAndSecretsStayRejected() {
        val pool=java.util.concurrent.Executors.newFixedThreadPool(8)
        val start=java.util.concurrent.CountDownLatch(1)
        try {
            val tasks=(0 until 8).map { worker -> pool.submit {
                start.await()
                repeat(200) { index ->
                    val enabled=(worker+index)%2==0
                    val days=1+(worker+index)%30
                    val source=base.replace("id: test","id: concurrent-$worker-$index")
                        .replace("enabled: false","enabled: $enabled")
                        .replace("local_days: 7","local_days: $days")
                    val result=P14ProfileContract.validate(source)
                    assertEquals(enabled,result.locationEnabled)
                    assertEquals(days,result.localRetentionDays)
                    val states=P14CapabilityRegistry.activation(source,setOf("LOCATION","BACKGROUND_LOCATION"))
                    assertEquals(P14Availability.READY,states.single { it.id=="text" }.state)
                    assertEquals(if(enabled) P14Availability.READY else P14Availability.DISABLED,
                        states.single { it.id=="observation.location" }.state)
                    if(index%20==0) rejects(source+"\ntoken: synthetic-forbidden")
                }
            } }
            start.countDown()
            tasks.forEach { it.get(45,java.util.concurrent.TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

}
