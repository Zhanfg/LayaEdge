package io.layaedge.app

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import io.layaedge.runtime.DecisionQuestion
import io.layaedge.runtime.DecisionRequest
import io.layaedge.runtime.LayaEdgeRuntime
import io.layaedge.runtime.PowerMode
import io.layaedge.runtime.PowerPolicy
import io.layaedge.runtime.backend.OnnxBackend
import io.layaedge.runtime.backend.OnnxExecutionProvider
import io.layaedge.runtime.model.ModelBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class DeviceBenchmark(
    context: Context,
    private val bundleDir: java.io.File,
) {
    private val appContext = context.applicationContext
    private val battery = appContext.getSystemService(BatteryManager::class.java)
    private val power = appContext.getSystemService(PowerManager::class.java)

    private data class Profile(
        val name: String,
        val provider: OnnxExecutionProvider,
        val threads: Int,
        val mode: PowerMode,
    )

    private data class Snapshot(
        val elapsedMs: Long,
        val processCpuMs: Long,
        val pssKb: Long,
        val thermalStatus: Int?,
        val currentUa: Int?,
        val energyNwh: Long?,
    )

    suspend fun run(warmRuns: Int = 5): String = withContext(Dispatchers.Default) {
        require(warmRuns >= 3)
        val bundle = ModelBundle.open(bundleDir, verify = true)
        val profiles = listOf(
            Profile("cpu-low-power", OnnxExecutionProvider.CPU, 1, PowerMode.LOW_POWER),
            Profile("cpu-balanced", OnnxExecutionProvider.CPU, 2, PowerMode.BALANCED),
            Profile("nnapi-requested", OnnxExecutionProvider.NNAPI, 2, PowerMode.BALANCED),
        )

        buildString {
            appendLine("LayaEdge device benchmark")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("model=${bundle.modelId}")
            appendLine("variant=${bundle.variant}")
            appendLine("warm_runs=$warmRuns")
            appendLine()

            for (profile in profiles) {
                System.gc()
                delay(250)
                val before = snapshot()
                var engine: LayaEdgeRuntime? = null

                try {
                    val policy = PowerPolicy(
                        mode = profile.mode,
                        idleReleaseMs = Long.MAX_VALUE,
                        intraOpThreads = profile.threads,
                    )
                    engine = LayaEdgeRuntime(
                        backend = OnnxBackend(
                            bundle = bundle,
                            provider = profile.provider,
                            intraOpThreads = profile.threads,
                        ),
                        policy = policy,
                    )

                    val coldStart = SystemClock.elapsedRealtime()
                    val cold = engine.decide(request())
                    val coldTotal = SystemClock.elapsedRealtime() - coldStart

                    val warmTotals = ArrayList<Long>(warmRuns)
                    val warmInference = ArrayList<Long>(warmRuns)
                    repeat(warmRuns) {
                        val started = SystemClock.elapsedRealtime()
                        val result = engine.decide(request())
                        warmTotals += SystemClock.elapsedRealtime() - started
                        warmInference += result.latencyMs
                    }

                    val after = snapshot()
                    appendLine("[${profile.name}]")
                    appendLine("cold_total_ms=$coldTotal")
                    appendLine("cold_inference_ms=${cold.latencyMs}")
                    appendLine("warm_total_p50_ms=${percentile(warmTotals, 0.50)}")
                    appendLine("warm_total_p95_ms=${percentile(warmTotals, 0.95)}")
                    appendLine("warm_inference_p50_ms=${percentile(warmInference, 0.50)}")
                    appendLine("warm_inference_p95_ms=${percentile(warmInference, 0.95)}")
                    appendLine("pss_delta_kb=${after.pssKb - before.pssKb}")
                    appendLine("cpu_delta_ms=${after.processCpuMs - before.processCpuMs}")
                    appendLine("thermal_before=${before.thermalStatus ?: "unsupported"}")
                    appendLine("thermal_after=${after.thermalStatus ?: "unsupported"}")
                    appendLine("current_before_ua=${before.currentUa ?: "unsupported"}")
                    appendLine("current_after_ua=${after.currentUa ?: "unsupported"}")
                    appendLine(
                        "energy_delta_nwh=${
                            energyDelta(before.energyNwh, after.energyNwh) ?: "unsupported"
                        }"
                    )
                    appendLine("sample_answer=${cold.answers["next_action"]}")
                } catch (error: Throwable) {
                    appendLine("[${profile.name}]")
                    appendLine("status=failed")
                    appendLine("error=${error.javaClass.simpleName}: ${error.message}")
                } finally {
                    engine?.close()
                }
                appendLine()
            }

            appendLine(
                "note=NNAPI is requested; unsupported graph fragments may fall back to CPU. " +
                    "Battery current/energy properties are device estimates, not lab-grade power measurements."
            )
        }
    }

    private fun request() = DecisionRequest(
        state = "应用在五分钟内连续崩溃了三次，同时用户正在前台操作。",
        questions = listOf(
            DecisionQuestion.Choice(
                id = "next_action",
                instruction = "选择下一步最合适的动作",
                options = linkedMapOf(
                    "collect_logs" to "优先收集诊断日志",
                    "restart_only" to "只重启应用",
                    "ignore" to "暂时忽略",
                ),
            ),
            DecisionQuestion.YesNo(
                id = "needs_attention",
                instruction = "现在是否需要进行诊断处理？",
            ),
        ),
    )

    private fun snapshot(): Snapshot {
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power.currentThermalStatus
        } else {
            null
        }
        val current = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeUnless { it == Int.MIN_VALUE }
        val energy = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
            .takeUnless { it == Long.MIN_VALUE }

        return Snapshot(
            elapsedMs = SystemClock.elapsedRealtime(),
            processCpuMs = Process.getElapsedCpuTime(),
            pssKb = Debug.getPss().toLong(),
            thermalStatus = thermal,
            currentUa = current,
            energyNwh = energy,
        )
    }

    private fun energyDelta(before: Long?, after: Long?): Long? {
        if (before == null || after == null) return null
        return after - before
    }

    private fun percentile(values: List<Long>, p: Double): Long {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * p).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }
}
