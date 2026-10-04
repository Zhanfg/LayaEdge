package io.layaedge.runtime.model

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class ZipModelInstaller(
    private val modelsRoot: File,
    private val maxUncompressedBytes: Long = 3L * 1024 * 1024 * 1024,
) {
    private data class Budget(var extractedBytes: Long = 0L)

    fun install(
        input: InputStream,
        slot: String = "laya-multilingual",
    ): ModelBundle {
        require(slot.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsafe model slot name" }
        modelsRoot.mkdirs()

        val staging = modelsRoot.resolve(".$slot.staging")
        val nested = modelsRoot.resolve(".$slot.nested")
        val destination = modelsRoot.resolve(slot)
        val backup = modelsRoot.resolve(".$slot.backup")
        listOf(staging, nested, backup).forEach { it.deleteRecursively() }
        staging.mkdirs()

        val budget = Budget()
        try {
            extractZip(input, staging, budget)
            val packageRoot = locatePackageRoot(staging, nested, budget)
            ModelBundle.open(packageRoot, verify = true)

            if (destination.exists()) {
                check(destination.renameTo(backup)) {
                    "Could not stage existing model for replacement"
                }
            }

            if (packageRoot != staging) {
                staging.deleteRecursively()
            }
            check(packageRoot.renameTo(destination)) {
                "Could not activate imported model"
            }
            backup.deleteRecursively()
            return ModelBundle.open(destination, verify = false)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            nested.deleteRecursively()
            if (!destination.exists() && backup.exists()) {
                backup.renameTo(destination)
            }
            throw error
        }
    }

    private fun locatePackageRoot(
        staging: File,
        nested: File,
        budget: Budget,
    ): File {
        if (staging.resolve("manifest.json").isFile) return staging

        val nestedArchives = staging.listFiles()
            ?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
            .orEmpty()

        require(nestedArchives.size == 1) {
            "ZIP does not contain a model manifest or exactly one nested model package"
        }

        nested.mkdirs()
        FileInputStream(nestedArchives.single()).use { inner ->
            extractZip(inner, nested, budget)
        }
        require(nested.resolve("manifest.json").isFile) {
            "Nested ZIP does not contain a model manifest"
        }
        return nested
    }

    private fun extractZip(
        input: InputStream,
        target: File,
        budget: Budget,
    ) {
        val canonicalRoot = target.canonicalFile
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue

                val output = target.resolve(entry.name).canonicalFile
                require(output.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Unsafe ZIP entry: ${entry.name}"
                }
                output.parentFile?.mkdirs()

                output.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        budget.extractedBytes += read
                        require(budget.extractedBytes <= maxUncompressedBytes) {
                            "Model package exceeds uncompressed size limit"
                        }
                        sink.write(buffer, 0, read)
                    }
                }
            }
        }
    }
}
