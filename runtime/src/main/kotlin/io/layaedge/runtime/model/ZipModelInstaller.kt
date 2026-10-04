package io.layaedge.runtime.model

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class ZipModelInstaller(
    private val modelsRoot: File,
    private val maxUncompressedBytes: Long = 3L * 1024 * 1024 * 1024,
) {
    fun install(
        input: InputStream,
        slot: String = "laya-multilingual",
    ): ModelBundle {
        require(slot.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsafe model slot name" }
        modelsRoot.mkdirs()

        val staging = modelsRoot.resolve(".$slot.staging")
        val destination = modelsRoot.resolve(slot)
        val backup = modelsRoot.resolve(".$slot.backup")
        staging.deleteRecursively()
        backup.deleteRecursively()
        staging.mkdirs()

        var total = 0L
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue

                    val output = staging.resolve(entry.name).canonicalFile
                    require(output.path.startsWith(staging.canonicalPath + File.separator)) {
                        "Unsafe ZIP entry: ${entry.name}"
                    }
                    output.parentFile?.mkdirs()

                    output.outputStream().buffered().use { sink ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            total += read
                            require(total <= maxUncompressedBytes) {
                                "Model package exceeds uncompressed size limit"
                            }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            }

            ModelBundle.open(staging, verify = true)

            if (destination.exists()) {
                check(destination.renameTo(backup)) {
                    "Could not stage existing model for replacement"
                }
            }

            check(staging.renameTo(destination)) {
                "Could not activate imported model"
            }
            backup.deleteRecursively()
            return ModelBundle.open(destination, verify = false)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (!destination.exists() && backup.exists()) {
                backup.renameTo(destination)
            }
            throw error
        }
    }
}
