package io.layaedge.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.layaedge.runtime.DecisionQuestion
import io.layaedge.runtime.DecisionRequest
import io.layaedge.runtime.LayaEdgeRuntime
import io.layaedge.runtime.PowerMode
import io.layaedge.runtime.PowerPolicy
import io.layaedge.runtime.backend.OnnxBackend
import io.layaedge.runtime.model.ModelBundle
import io.layaedge.runtime.model.ZipModelInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private var runtime: LayaEdgeRuntime? = null
    private var benchmarkReport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 48)
        }

        val title = TextView(this).apply {
            text = "LayaEdge"
            textSize = 28f
        }

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 28, 0, 28)
            setTextIsSelectable(true)
        }

        val importButton = Button(this).apply {
            text = "Import model package"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/zip"
                    },
                    REQUEST_MODEL,
                )
            }
        }

        val testButton = Button(this).apply {
            text = "Run local decision"
            setOnClickListener { runDecision() }
        }

        val benchmarkButton = Button(this).apply {
            text = "Run device benchmark"
            setOnClickListener {
                isEnabled = false
                runBenchmark { isEnabled = true }
            }
        }

        val copyButton = Button(this).apply {
            text = "Copy benchmark report"
            setOnClickListener { copyBenchmarkReport() }
        }

        root.addView(title)
        root.addView(
            status,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        listOf(importButton, testButton, benchmarkButton, copyButton).forEach { button ->
            root.addView(
                button,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val scroll = ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        setContentView(scroll)
        refreshStatus()
    }

    @Deprecated("Legacy activity result API keeps the bootstrap app dependency-light.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        status.text = "Importing and verifying model…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = requireNotNull(contentResolver.openInputStream(uri))
                    input.use {
                        ZipModelInstaller(modelsRoot()).install(it)
                    }
                }
            }.onSuccess {
                runtime?.close()
                runtime = null
                benchmarkReport = ""
                refreshStatus()
            }.onFailure {
                status.text = "Import failed: ${it.message}"
            }
        }
    }

    private fun runDecision() {
        val bundleDir = installedBundleDir() ?: run {
            status.text = "No model installed. Import a LayaEdge model package first."
            return
        }

        status.text = "Running locally…"
        scope.launch {
            runCatching {
                val engine = runtime ?: createRuntime(bundleDir).also { runtime = it }
                engine.decide(sampleRequest())
            }.onSuccess { result ->
                status.text = buildString {
                    appendLine("Backend: ${result.backend}")
                    appendLine("Latency: ${result.latencyMs} ms")
                    appendLine("Input tokens: ${result.inputTokens}")
                    result.answers.forEach { (id, answer) ->
                        appendLine("$id → $answer")
                    }
                }
            }.onFailure {
                status.text = "Inference failed: ${it.message}"
            }
        }
    }

    private fun runBenchmark(onFinished: () -> Unit) {
        val bundleDir = installedBundleDir() ?: run {
            status.text = "No model installed. Import a LayaEdge model package first."
            onFinished()
            return
        }

        runtime?.close()
        runtime = null
        status.text = "Benchmarking CPU and NNAPI paths…"

        scope.launch {
            runCatching {
                DeviceBenchmark(this@MainActivity, bundleDir).run()
            }.onSuccess { report ->
                benchmarkReport = report
                status.text = report
            }.onFailure {
                status.text = "Benchmark failed: ${it.message}"
            }
            onFinished()
        }
    }

    private fun copyBenchmarkReport() {
        if (benchmarkReport.isBlank()) {
            status.text = "Run the device benchmark first."
            return
        }

        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText("LayaEdge benchmark", benchmarkReport)
        )
    }

    private fun createRuntime(bundleDir: java.io.File): LayaEdgeRuntime {
        val bundle = ModelBundle.open(bundleDir, verify = true)
        val policy = PowerPolicy(PowerMode.BALANCED)
        val backend = OnnxBackend(
            bundle = bundle,
            intraOpThreads = policy.intraOpThreads,
        )
        return LayaEdgeRuntime(backend, policy)
    }

    private fun sampleRequest() = DecisionRequest(
        state = "用户说：应用刚刚崩溃了两次，现在是否应该优先收集诊断日志？",
        questions = listOf(
            DecisionQuestion.Choice(
                id = "next_action",
                instruction = "选择下一步最合适的动作",
                options = linkedMapOf(
                    "collect_logs" to "先收集诊断日志",
                    "ignore" to "暂时忽略",
                    "restart_only" to "只重启应用",
                ),
            ),
            DecisionQuestion.YesNo(
                id = "needs_attention",
                instruction = "这件事现在需要处理吗？",
            ),
        ),
    )

    private fun installedBundleDir(): java.io.File? =
        modelsRoot().resolve(MODEL_SLOT).takeIf {
            it.resolve("manifest.json").isFile
        }

    private fun refreshStatus() {
        status.text = if (installedBundleDir() != null) {
            "Model package installed. Decisions stay on-device."
        } else {
            "Runtime ready. Import the downloaded GitHub artifact directly; " +
                "the importer also understands its nested model ZIP."
        }
    }

    private fun modelsRoot() = filesDir.resolve("models")

    override fun onDestroy() {
        runtime?.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MODEL = 1001
        private const val MODEL_SLOT = "laya-multilingual"
    }
}
