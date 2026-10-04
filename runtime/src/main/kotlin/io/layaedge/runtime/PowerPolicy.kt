package io.layaedge.runtime

enum class PowerMode {
    LOW_POWER,
    BALANCED,
    INTERACTIVE
}

data class PowerPolicy(
    val mode: PowerMode = PowerMode.BALANCED,
    val idleReleaseMs: Long = when (mode) {
        PowerMode.LOW_POWER -> 15_000
        PowerMode.BALANCED -> 30_000
        PowerMode.INTERACTIVE -> 120_000
    },
    val intraOpThreads: Int = when (mode) {
        PowerMode.LOW_POWER -> 1
        PowerMode.BALANCED -> 2
        PowerMode.INTERACTIVE -> 4
    },
    val maxConcurrentRequests: Int = 1,
    val allowBackgroundWarmup: Boolean = false,
) {
    init {
        require(idleReleaseMs >= 0)
        require(intraOpThreads > 0)
        require(maxConcurrentRequests > 0)
    }
}
