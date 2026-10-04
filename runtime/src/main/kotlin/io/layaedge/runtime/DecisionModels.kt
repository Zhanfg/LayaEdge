package io.layaedge.runtime

enum class DecisionType(val wireName: String, val qtype: Long) {
    CHOICE("choice", 0),
    SCORE("score", 1),
    YES_NO("noul", 2)
}

sealed interface DecisionQuestion {
    val id: String
    val instruction: String
    val type: DecisionType

    data class Choice(
        override val id: String,
        override val instruction: String,
        val options: LinkedHashMap<String, String?>,
    ) : DecisionQuestion {
        override val type: DecisionType = DecisionType.CHOICE
    }

    data class Score(
        override val id: String,
        override val instruction: String,
        val levels: List<String>,
    ) : DecisionQuestion {
        override val type: DecisionType = DecisionType.SCORE
    }

    data class YesNo(
        override val id: String,
        override val instruction: String,
        val falseDescription: String? = null,
        val trueDescription: String? = null,
        val falseLabel: String = "false",
        val trueLabel: String = "true",
    ) : DecisionQuestion {
        override val type: DecisionType = DecisionType.YES_NO
    }
}

data class DecisionRequest(
    val state: String,
    val questions: List<DecisionQuestion>,
    val priority: Priority = Priority.NORMAL,
)

enum class Priority {
    BACKGROUND,
    NORMAL,
    INTERACTIVE
}

sealed interface DecisionAnswer {
    val questionId: String
    val confidence: Float
    val answerConfidence: Float
    val actProbability: Float
    val probabilities: Map<String, Float>

    data class Choice(
        override val questionId: String,
        val choice: String,
        override val probabilities: Map<String, Float>,
        override val confidence: Float,
        override val answerConfidence: Float,
        override val actProbability: Float,
    ) : DecisionAnswer

    data class Score(
        override val questionId: String,
        val score: Float,
        val legend: Map<String, String>,
        override val probabilities: Map<String, Float>,
        override val confidence: Float,
        override val answerConfidence: Float,
        override val actProbability: Float,
    ) : DecisionAnswer

    data class YesNo(
        override val questionId: String,
        val probabilityTrue: Float,
        override val probabilities: Map<String, Float>,
        override val confidence: Float,
        override val answerConfidence: Float,
        override val actProbability: Float,
    ) : DecisionAnswer
}

data class DecisionResult(
    val answers: Map<String, DecisionAnswer>,
    val backend: String,
    val modelId: String,
    val latencyMs: Long,
    val inputTokens: Int,
)
