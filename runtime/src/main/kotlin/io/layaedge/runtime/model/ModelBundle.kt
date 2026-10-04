package io.layaedge.runtime.model

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class LayaModelConfig(
    val maxLen: Int,
    val headMaxLen: Int,
    val temperatures: FloatArray,
    val temperatureByOptions: Map<String, Float>,
)

data class ModelBundle(
    val root: File,
    val modelId: String,
    val variant: String,
    val modelFile: File,
    val tokenizerFile: File,
    val tokenizerConfigFile: File,
    val configFile: File,
    val config: LayaModelConfig,
) {
    companion object {
        fun open(root: File, verify: Boolean = true): ModelBundle {
            val manifestFile = root.resolve("manifest.json")
            require(manifestFile.isFile) { "Missing manifest.json in ${root.absolutePath}" }

            val manifest = JSONObject(manifestFile.readText())
            require(manifest.getInt("schema_version") == 1) { "Unsupported model manifest schema" }

            val modelFile = root.resolve("model.onnx")
            val tokenizerFile = root.resolve("tokenizer/tokenizer.json")
            val tokenizerConfigFile = root.resolve("tokenizer/tokenizer_config.json")
            val configFile = root.resolve("rl_agent_config.json")
            listOf(modelFile, tokenizerFile, tokenizerConfigFile, configFile).forEach {
                require(it.isFile) { "Missing model package file: ${it.relativeTo(root)}" }
            }

            if (verify) {
                val fileSpecs = manifest.getJSONObject("files")
                fileSpecs.keys().forEach { relative ->
                    val spec = fileSpecs.getJSONObject(relative)
                    val file = root.resolve(relative)
                    require(file.isFile) { "Manifest file missing: $relative" }
                    val expectedSize = spec.getLong("size_bytes")
                    require(file.length() == expectedSize) {
                        "Size mismatch for $relative: ${file.length()} != $expectedSize"
                    }
                    val expectedHash = spec.getString("sha256")
                    val actualHash = sha256(file)
                    require(actualHash.equals(expectedHash, ignoreCase = true)) {
                        "SHA-256 mismatch for $relative"
                    }
                }
            }

            val cfg = JSONObject(configFile.readText())
            val temperatureJson = cfg.optJSONArray("temperature")
            val temperatures = FloatArray(3) { index ->
                temperatureJson?.optDouble(index, 1.0)?.toFloat() ?: 1f
            }
            val byOptionsJson = cfg.optJSONObject("temperature_by_options")
            val byOptions = buildMap {
                if (byOptionsJson != null) {
                    byOptionsJson.keys().forEach { key ->
                        put(key, byOptionsJson.getDouble(key).toFloat())
                    }
                }
            }

            return ModelBundle(
                root = root,
                modelId = manifest.getString("model_id"),
                variant = manifest.getString("variant"),
                modelFile = modelFile,
                tokenizerFile = tokenizerFile,
                tokenizerConfigFile = tokenizerConfigFile,
                configFile = configFile,
                config = LayaModelConfig(
                    maxLen = cfg.optInt("max_len", 1024),
                    headMaxLen = cfg.optInt("head_max_len", 256),
                    temperatures = temperatures,
                    temperatureByOptions = byOptions,
                ),
            )
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
