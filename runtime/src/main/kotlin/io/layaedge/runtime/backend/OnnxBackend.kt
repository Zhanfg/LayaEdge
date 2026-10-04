package io.layaedge.runtime.backend

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import io.layaedge.runtime.DecisionAnswer
import io.layaedge.runtime.DecisionQuestion
import io.layaedge.runtime.DecisionRequest
import io.layaedge.runtime.DecisionResult
import io.layaedge.runtime.DecisionType
import io.layaedge.runtime.model.ModelBundle
import io.layaedge.runtime.tokenizer.EncodedRow
import io.layaedge.runtime.tokenizer.LayaTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln

enum class OnnxExecutionProvider {
    CPU,
    NNAPI,
}

class OnnxBackend(
    private val bundle: ModelBundle,
    private val provider: OnnxExecutionProvider = OnnxExecutionProvider.CPU,
    private val intraOpThreads: Int = 2,
) : InferenceBackend {
    override val id: String = "onnx-${provider.name.lowercase()}"

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var tokenizer: LayaTokenizer? = null

    override suspend fun load() = withContext(Dispatchers.IO) {
        if (session != null) return@withContext
        synchronized(this@OnnxBackend) {
            if (session != null) return@synchronized

            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(intraOpThreads)
                setInterOpNumThreads(1)
                if (provider == OnnxExecutionProvider.NNAPI) {
                    addNnapi()
                }
            }

            session = env.createSession(bundle.modelFile.absolutePath, options)
            tokenizer = LayaTokenizer(
                tokenizerJson = bundle.tokenizerFile.toPath(),
                tokenizerConfigJson = bundle.tokenizerConfigFile.toPath(),
            )
        }
    }

    override suspend fun run(request: DecisionRequest): DecisionResult =
        withContext(Dispatchers.Default) {
            if (session == null) load()
            val localSession = checkNotNull(session)
            val localTokenizer = checkNotNull(tokenizer)

            val started = SystemClock.elapsedRealtime()
            val rows = localTokenizer.buildRows(
                state = request.state,
                questions = request.questions,
                maxLen = bundle.config.maxLen,
                headMaxLen = bundle.config.headMaxLen,
            )
            require(rows.isNotEmpty()) { "At least one question is required" }

            val batch = collate(rows, localTokenizer.padId)
            val inputs = linkedMapOf(
                "input_ids" to OnnxTensor.createTensor(env, batch.inputIds),
                "attention_mask" to OnnxTensor.createTensor(env, batch.attentionMask),
                "marker_pos" to OnnxTensor.createTensor(env, batch.markerPositions),
                "marker_mask" to OnnxTensor.createTensor(env, batch.markerMask),
                "qtype" to OnnxTensor.createTensor(env, batch.qtype),
            )

            try {
                localSession.run(inputs).use { outputs ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = outputs[0].value as Array<FloatArray>
                    @Suppress("UNCHECKED_CAST")
                    val actLogits = outputs[1].value as Array<FloatArray>

                    val answers = LinkedHashMap<String, DecisionAnswer>(rows.size)
                    rows.forEachIndexed { index, row ->
                        val k = row.markerPositions.size
                        val temperature = temperatureFor(row.question.type, k)
                        val probs = softmax(logits[index], k, temperature)
                        val act = softmax(actLogits[index], actLogits[index].size, 1f)
                        answers[row.question.id] =
                            decode(row, probs, act.firstOrNull() ?: 0f)
                    }

                    DecisionResult(
                        answers = answers,
                        backend = id,
                        modelId = bundle.modelId,
                        latencyMs = SystemClock.elapsedRealtime() - started,
                        inputTokens = batch.attentionMask.sumOf { mask ->
                            mask.count { it == 1L }
                        },
                    )
                }
            } finally {
                inputs.values.forEach { it.close() }
            }
        }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        synchronized(this@OnnxBackend) {
            tokenizer?.close()
            tokenizer = null
            session?.close()
            session = null
        }
    }

    override fun isLoaded(): Boolean = session != null

    override fun close() {
        synchronized(this) {
            tokenizer?.close()
            tokenizer = null
            session?.close()
            session = null
        }
    }

    private fun temperatureFor(type: DecisionType, optionCount: Int): Float {
        val bucket = when (type) {
            DecisionType.CHOICE -> when (optionCount) {
                2 -> "choice:2"
                in 3..5 -> "choice:3-5"
                in 6..10 -> "choice:6-10"
                else -> "choice:11+"
            }

            DecisionType.SCORE -> when (optionCount) {
                in 3..5 -> "score:3-5"
                else -> "score:$optionCount"
            }

            DecisionType.YES_NO -> "noul:2"
        }

        return bundle.config.temperatureByOptions[bucket]
            ?: bundle.config.temperatures[type.qtype.toInt()]
    }

    private fun decode(
        row: EncodedRow,
        probabilities: FloatArray,
        actProbability: Float,
    ): DecisionAnswer {
        val answerConfidence = probabilities.maxOrNull() ?: 0f
        return when (val question = row.question) {
            is DecisionQuestion.Choice -> {
                val labels = question.options.keys.toList()
                val index = probabilities.indices.maxBy { probabilities[it] }
                DecisionAnswer.Choice(
                    questionId = question.id,
                    choice = labels[index],
                    probabilities = labels.zip(probabilities.toList()).toMap(),
                    confidence = entropyConfidence(probabilities),
                    answerConfidence = answerConfidence,
                    actProbability = actProbability,
                )
            }

            is DecisionQuestion.Score -> {
                val expected = probabilities.indices.sumOf { index ->
                    index.toDouble() * probabilities[index].toDouble()
                }.toFloat()
                DecisionAnswer.Score(
                    questionId = question.id,
                    score = expected,
                    legend = question.levels.mapIndexed { index, value ->
                        index.toString() to value
                    }.toMap(),
                    probabilities = probabilities.mapIndexed { index, value ->
                        index.toString() to value
                    }.toMap(),
                    confidence = entropyConfidence(probabilities),
                    answerConfidence = answerConfidence,
                    actProbability = actProbability,
                )
            }

            is DecisionQuestion.YesNo -> {
                val pTrue = probabilities.getOrElse(1) { 0f }
                DecisionAnswer.YesNo(
                    questionId = question.id,
                    probabilityTrue = pTrue,
                    probabilities = linkedMapOf(
                        question.falseLabel to probabilities.getOrElse(0) { 0f },
                        question.trueLabel to pTrue,
                    ),
                    confidence = maxOf(pTrue, 1f - pTrue),
                    answerConfidence = answerConfidence,
                    actProbability = actProbability,
                )
            }
        }
    }

    private fun softmax(
        values: FloatArray,
        count: Int,
        temperature: Float,
    ): FloatArray {
        require(count > 0)
        val safeTemperature = temperature.coerceAtLeast(1e-4f)
        val max = (0 until count).maxOf { values[it] / safeTemperature }
        val expValues = FloatArray(count)
        var sum = 0.0

        for (index in 0 until count) {
            val value = exp((values[index] / safeTemperature - max).toDouble()).toFloat()
            expValues[index] = value
            sum += value.toDouble()
        }

        if (sum <= 0.0) return FloatArray(count) { 1f / count }

        for (index in expValues.indices) {
            expValues[index] = (expValues[index].toDouble() / sum).toFloat()
        }
        return expValues
    }

    private fun entropyConfidence(probabilities: FloatArray): Float {
        if (probabilities.size <= 1) return 1f

        var entropy = 0.0
        probabilities.forEach { p ->
            if (p > 0f) {
                entropy -= p.toDouble() * ln(p.toDouble())
            }
        }

        val maxEntropy = ln(probabilities.size.toDouble())
        return (1.0 - entropy / maxEntropy).toFloat().coerceIn(0f, 1f)
    }

    private data class Batch(
        val inputIds: Array<LongArray>,
        val attentionMask: Array<LongArray>,
        val markerPositions: Array<LongArray>,
        val markerMask: Array<BooleanArray>,
        val qtype: LongArray,
    )

    private fun collate(rows: List<EncodedRow>, padId: Long): Batch {
        val maxSequence = rows.maxOf { it.inputIds.size }
        val maxMarkers = rows.maxOf { it.markerPositions.size }

        val inputIds = Array(rows.size) { LongArray(maxSequence) { padId } }
        val attention = Array(rows.size) { LongArray(maxSequence) }
        val markerPositions = Array(rows.size) { LongArray(maxMarkers) }
        val markerMask = Array(rows.size) { BooleanArray(maxMarkers) }
        val qtype = LongArray(rows.size)

        rows.forEachIndexed { rowIndex, row ->
            row.inputIds.copyInto(inputIds[rowIndex])
            repeat(row.inputIds.size) { attention[rowIndex][it] = 1L }
            row.markerPositions.copyInto(markerPositions[rowIndex])
            repeat(row.markerPositions.size) { markerMask[rowIndex][it] = true }
            qtype[rowIndex] = row.question.type.qtype
        }

        return Batch(
            inputIds = inputIds,
            attentionMask = attention,
            markerPositions = markerPositions,
            markerMask = markerMask,
            qtype = qtype,
        )
    }
}
