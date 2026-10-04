package io.layaedge.runtime

enum class DecisionType {
    CHOICE,
    SCORE,
    YES_NO
}

data class DecisionQuestion(
    val id: String,
    val type: DecisionType,
    val instruction: String,
    val criteria: List<String> = emptyList()
)

data class DecisionRequest(
    val state: String,
    val questions: List<DecisionQuestion>,
    val priority: Priority = Priority.NORMAL
)

enum class Priority {
    BACKGROUND,
    NORMAL,
    INTERACTIVE
}

data class DecisionAnswer(
    val questionId: String,
    val value: String,
    val confidence: Float
)

data class DecisionResult(
    val answers: List<DecisionAnswer>,
    val backend: String,
    val modelId: String,
    val latencyMs: Long
)
