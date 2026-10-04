package io.layaedge.runtime.backend

import io.layaedge.runtime.DecisionRequest
import io.layaedge.runtime.DecisionResult

interface InferenceBackend : AutoCloseable {
    val id: String
    suspend fun load()
    suspend fun run(request: DecisionRequest): DecisionResult
    suspend fun unload()
    fun isLoaded(): Boolean
    override fun close()
}
