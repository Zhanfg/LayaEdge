package io.layaedge.runtime

interface DecisionEngine : AutoCloseable {
    suspend fun decide(request: DecisionRequest): DecisionResult
    suspend fun warmUp() = Unit
    suspend fun release()
    override fun close()
}
