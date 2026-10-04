package io.layaedge.runtime

interface DecisionEngine {
    suspend fun decide(request: DecisionRequest): DecisionResult

    suspend fun warmUp() = Unit

    suspend fun release()
}
