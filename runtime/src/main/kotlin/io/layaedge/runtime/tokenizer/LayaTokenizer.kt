package io.layaedge.runtime.tokenizer

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import io.layaedge.runtime.DecisionQuestion
import org.json.JSONObject
import java.io.Closeable
import java.nio.file.Path

data class EncodedRow(
    val inputIds: LongArray,
    val markerPositions: LongArray,
    val question: DecisionQuestion,
    val options: List<String>,
)

class LayaTokenizer(
    tokenizerJson: Path,
    tokenizerConfigJson: Path,
) : Closeable {
    private val tokenizer = HuggingFaceTokenizer.newInstance(
        tokenizerJson,
        tokenizerConfigJson,
        mapOf("addSpecialTokens" to "false"),
    )

    private val tokenizerConfig = JSONObject(tokenizerConfigJson.toFile().readText())
    private val clsId = specialId(configToken("cls_token", configToken("bos_token", "<bos>")))
    private val sepId = specialId(configToken("sep_token", configToken("eos_token", "<eos>")))
    private val maskToken = configToken("mask_token", "<mask>")
    private val maskId = specialId(maskToken)
    val padId: Long = specialId(configToken("pad_token", "<pad>"))

    fun buildRows(
        state: String,
        questions: List<DecisionQuestion>,
        maxLen: Int,
        headMaxLen: Int,
    ): List<EncodedRow> {
        val cleanState = state.replace(maskToken, " ")
        val stateIds = encode(cleanState)
        return questions.map { buildRow(stateIds, it, maxLen, headMaxLen) }
    }

    private fun buildRow(
        stateIds: LongArray,
        question: DecisionQuestion,
        maxLen: Int,
        headMaxLen: Int,
    ): EncodedRow {
        require(maxLen >= 16) { "maxLen is too small" }
        require(headMaxLen in 8..maxLen) { "headMaxLen must be within 8..maxLen" }

        val options = renderOptions(question)
        require(options.isNotEmpty()) { "Question ${question.id} has no options" }

        val cleanInstruction = question.instruction.replace(maskToken, " ")
        var head = encode("${question.type.wireName} question: $cleanInstruction").toList()
        var optionIds = options.map { option ->
            longArrayOf(maskId) + encode(" " + option.replace(maskToken, " ")).takeArray(48)
        }

        var optionBudget = headMaxLen - optionIds.sumOf { it.size }
        if (optionBudget < 16) {
            val per = maxOf(4, (headMaxLen - 16) / maxOf(1, optionIds.size))
            optionIds = optionIds.map { it.takeArray(per) }
            optionBudget = headMaxLen - optionIds.sumOf { it.size }
        }
        head = head.take(maxOf(8, optionBudget))

        val ids = ArrayList<Long>(maxLen)
        ids += clsId
        ids += head
        ids += sepId

        val markers = LongArray(optionIds.size)
        optionIds.forEachIndexed { index, option ->
            markers[index] = ids.size.toLong()
            ids += option.toList()
        }
        ids += sepId

        val room = maxOf(0, maxLen - ids.size - 1)
        ids += stateIds.takeArray(room).toList()
        ids += sepId

        require(ids.size <= maxLen) { "Sequence exceeded maxLen" }
        require(markers.all { it < ids.size }) {
            "Question ${question.id}: option markers do not fit the sequence budget"
        }

        return EncodedRow(
            inputIds = ids.toLongArray(),
            markerPositions = markers,
            question = question,
            options = options,
        )
    }

    fun encode(text: String): LongArray =
        tokenizer.encode(text, false, false).ids

    private fun configToken(key: String, fallback: String): String {
        val raw = tokenizerConfig.opt(key) ?: return fallback
        return when (raw) {
            is String -> raw
            is JSONObject -> raw.optString("content", fallback)
            else -> fallback
        }
    }

    private fun specialId(token: String): Long {
        val ids = tokenizer.encode(token, false, false).ids
        require(ids.size == 1) {
            "Tokenizer did not resolve special token '$token' to one id (got ${ids.size})"
        }
        return ids[0]
    }

    private fun renderOptions(question: DecisionQuestion): List<String> = when (question) {
        is DecisionQuestion.Choice -> question.options.map { (label, description) ->
            if (description.isNullOrEmpty()) label else "$label: $description"
        }

        is DecisionQuestion.Score -> question.levels.mapIndexed { index, description ->
            "level $index: $description"
        }

        is DecisionQuestion.YesNo -> listOf(
            "${question.falseLabel}: " +
                (question.falseDescription?.takeIf { it.isNotEmpty() }
                    ?: "no, the statement does not hold"),
            "${question.trueLabel}: " +
                (question.trueDescription?.takeIf { it.isNotEmpty() }
                    ?: "yes, the statement holds"),
        )
    }

    private fun LongArray.takeArray(limit: Int): LongArray =
        if (size <= limit) this else copyOfRange(0, limit)

    override fun close() {
        tokenizer.close()
    }
}
