package com.npu.llmchat

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import com.geniex.sdk.bean.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var chatOutput: TextView
    private lateinit var chatInput: EditText
    private lateinit var sendButton: Button
    private lateinit var loadButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var scrollView: ScrollView

    private var llmWrapper: LlmWrapper? = null
    private val chatHistory = mutableListOf<ChatMessage>()
    private var isGenerating = false
    private var modelLoaded = false

    private val modelName = "Qwen/Qwen3-8B"
    private val modelPrecision = "Q4_0"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initGenieXSdk()
        setupListeners()
    }

    private fun initViews() {
        chatOutput = findViewById(R.id.chatOutput)
        chatInput = findViewById(R.id.chatInput)
        sendButton = findViewById(R.id.sendButton)
        loadButton = findViewById(R.id.loadButton)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        scrollView = findViewById(R.id.scrollView)

        sendButton.isEnabled = false
        loadButton.isEnabled = false
        updateStatus("Initializing SDK...")
    }

    private fun initGenieXSdk() {
        GenieXSdk.getInstance().init(this, object : GenieXSdk.InitCallback {
            override fun onSuccess() {
                runOnUiThread {
                    updateStatus("SDK initialized. Tap 'Load Model' to start.")
                    loadButton.isEnabled = true
                }
            }

            override fun onFailure(reason: String) {
                runOnUiThread {
                    updateStatus("SDK init failed: $reason")
                    Toast.makeText(this@MainActivity, "SDK Error: $reason", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun setupListeners() {
        loadButton.setOnClickListener {
            if (!isGenerating) loadModel()
        }

        sendButton.setOnClickListener {
            val message = chatInput.text.toString().trim()
            if (message.isNotEmpty() && !isGenerating && modelLoaded) {
                sendMessage(message)
            }
        }
    }

    private fun loadModel() {
        isGenerating = true
        loadButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        updateStatus("Checking model...")

        lifecycleScope.launch {
            try {
                // Check if model exists
                val paths = try {
                    ModelManagerWrapper.getPaths(modelName)
                } catch (e: Exception) {
                    null
                }

                if (paths == null || paths.model_path.isNullOrEmpty()) {
                    updateStatus("Downloading model...")
                    downloadModel()
                }

                val finalPaths = ModelManagerWrapper.getPaths(modelName)
                    ?: throw Exception("Model not found after download")
                updateStatus("Loading model with NPU...")

                val config = ModelConfig(
                    nCtx = 4096,
                    nThreads = 4,
                    nThreadsBatch = 4,
                    nBatch = 512,
                    nUBatch = 512,
                    nSeqMax = 1,
                    nGpuLayers = 99
                )

                val createInput = LlmCreateInput(
                    model_path = finalPaths.model_path ?: "",
                    tokenizer_path = finalPaths.tokenizer_path,
                    config = config,
                    runtime_id = "llama_cpp",
                    compute_unit = "npu"
                )

                val result = LlmWrapper.builder()
                    .llmCreateInput(createInput)
                    .dispatcher(Dispatchers.IO)
                    .build()

                result.fold(
                    onSuccess = { wrapper ->
                        llmWrapper = wrapper
                        modelLoaded = true
                        updateStatus("Model loaded! NPU inference ready.")
                        sendButton.isEnabled = true
                        loadButton.text = "Reload Model"
                    },
                    onFailure = { error ->
                        updateStatus("Failed to load: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                updateStatus("Error: ${e.message}")
            } finally {
                progressBar.visibility = View.GONE
                loadButton.isEnabled = true
                isGenerating = false
            }
        }
    }

    private suspend fun downloadModel() {
        val pullInput = ModelPullInput(
            model_name = modelName,
            precision = modelPrecision,
            hub = HubSource.HUGGINGFACE,
            model_type = ModelType.LLM
        )

        ModelManagerWrapper.pullFlow(pullInput).collect { event ->
            when (event) {
                is ModelManagerWrapper.PullEvent.Progress -> {
                    val files = event.files
                    val avg = if (files.isNotEmpty()) {
                        val totalDownloaded = files.sumOf { it.downloaded_bytes }
                        val totalSize = files.sumOf { it.total_bytes }.coerceAtLeast(1)
                        totalDownloaded.toDouble() / totalSize.toDouble()
                    } else 0.0
                    updateStatus("Downloading: ${(avg * 100).toInt()}%")
                }
                is ModelManagerWrapper.PullEvent.Completed -> {
                    updateStatus("Download complete!")
                }
                is ModelManagerWrapper.PullEvent.Error -> {
                    throw Exception("Download failed [${event.code}]: ${event.message}")
                }
            }
        }
    }

    private fun sendMessage(message: String) {
        isGenerating = true
        sendButton.isEnabled = false
        chatInput.text.clear()

        chatHistory.add(ChatMessage(role = "user", content = message))
        appendToChat("You: $message\n")
        appendToChat("AI: ")

        lifecycleScope.launch {
            try {
                updateStatus("Generating (NPU)...")

                val templateResult = llmWrapper?.applyChatTemplate(
                    messages = chatHistory.toTypedArray(),
                    tools = null,
                    enableThinking = false
                )

                val prompt = templateResult?.getOrNull()?.formattedText ?: message

                val sampler = SamplerConfig(
                    temperature = 0.7f,
                    topP = 0.9f,
                    topK = 40,
                    repetitionPenalty = 1.1f
                )

                val config = GenerationConfig(
                    maxTokens = 1024,
                    samplerConfig = sampler
                )

                val responseBuilder = StringBuilder()

                llmWrapper?.generateStreamFlow(prompt, config)
                    ?.flowOn(Dispatchers.IO)
                    ?.collect { result ->
                        when (result) {
                            is LlmStreamResult.Token -> {
                                val token = result.text
                                responseBuilder.append(token)
                                appendToChat(token)
                            }
                            is LlmStreamResult.Completed -> {
                                val profile = result.profile
                                val speed = profile?.decodingSpeed ?: 0.0
                                val tokens = profile?.generatedTokens ?: 0
                                updateStatus("Done! Speed: ${String.format("%.1f", speed)} tok/s | Tokens: $tokens")
                            }
                            is LlmStreamResult.Error -> {
                                updateStatus("Error: ${result.throwable.message}")
                            }
                        }
                    }

                chatHistory.add(ChatMessage(role = "assistant", content = responseBuilder.toString()))
                appendToChat("\n\n")

            } catch (e: Exception) {
                updateStatus("Error: ${e.message}")
            } finally {
                isGenerating = false
                sendButton.isEnabled = modelLoaded
            }
        }
    }

    private fun appendToChat(text: String) {
        runOnUiThread {
            chatOutput.append(text)
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread { statusText.text = status }
    }

    override fun onDestroy() {
        super.onDestroy()
        llmWrapper?.close()
    }
}