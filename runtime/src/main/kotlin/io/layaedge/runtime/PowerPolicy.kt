package io.layaedge.runtime

data class PowerPolicy(
    val idleReleaseMs: Long = 30_000,
    val maxConcurrentRequests: Int = 1,
    val batchWindowMs: Long = 8,
    val allowBackgroundWarmup: Boolean = false
) {
    init {
        require(idleReleaseMs >= 0)
        require(maxConcurrentRequests > 0)
        require(batchWindowMs >= 0)
    }
}
