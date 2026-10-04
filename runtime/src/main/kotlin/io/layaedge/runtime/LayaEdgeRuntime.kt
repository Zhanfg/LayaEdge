package io.layaedge.runtime

import io.layaedge.runtime.backend.InferenceBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LayaEdgeRuntime(
    private val backend: InferenceBackend,
    private val policy: PowerPolicy = PowerPolicy(),
) : DecisionEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private var releaseJob: Job? = null

    override suspend fun warmUp() {
        if (!policy.allowBackgroundWarmup) return
        gate.withLock {
            cancelIdleRelease()
            backend.load()
            scheduleIdleRelease()
        }
    }

    override suspend fun decide(request: DecisionRequest): DecisionResult =
        gate.withLock {
            cancelIdleRelease()
            try {
                backend.load()
                backend.run(request)
            } finally {
                scheduleIdleRelease()
            }
        }

    override suspend fun release() {
        gate.withLock {
            cancelIdleRelease()
            backend.unload()
        }
    }

    override fun close() {
        releaseJob?.cancel()
        scope.cancel()
        backend.close()
    }

    private fun cancelIdleRelease() {
        releaseJob?.cancel()
        releaseJob = null
    }

    private fun scheduleIdleRelease() {
        if (policy.idleReleaseMs == Long.MAX_VALUE) return
        releaseJob = scope.launch {
            delay(policy.idleReleaseMs)
            gate.withLock {
                backend.unload()
            }
        }
    }
}
