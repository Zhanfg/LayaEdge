package io.layaedge.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerPolicyTest {
    @Test
    fun lowPowerUsesOneInferenceThread() {
        val policy = PowerPolicy(PowerMode.LOW_POWER)
        assertEquals(1, policy.intraOpThreads)
        assertEquals(15_000, policy.idleReleaseMs)
    }

    @Test
    fun interactiveKeepsModelWarmLonger() {
        val policy = PowerPolicy(PowerMode.INTERACTIVE)
        assertEquals(4, policy.intraOpThreads)
        assertEquals(120_000, policy.idleReleaseMs)
    }
}
