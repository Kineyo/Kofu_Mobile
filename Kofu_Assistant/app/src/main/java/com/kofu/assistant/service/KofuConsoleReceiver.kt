package com.kofu.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kofu.assistant.data.ChatCacheManager
import com.kofu.assistant.data.ChatMessageData
import com.kofu.assistant.data.rag.KofuRagDatabase
import com.kofu.assistant.repository.KofuNativeEngine
import com.kofu.assistant.repository.LlamaRepository
import com.kofu.assistant.repository.TurnMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Puente de consola ADB para interactuar con Kofu Assistant sin depender de servidor remoto.
 * Permite cargar modelos locales, enviar prompts, consultar telemetría y ejecutar la batería
 * de estrés de 500 preguntas monitoreando logcat bajo la etiqueta "KofuConsole".
 */
class KofuConsoleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KofuConsole"
        const val ACTION_CONSOLE_PROMPT = "com.kofu.assistant.ACTION_CONSOLE_PROMPT"
        const val ACTION_CONSOLE_LOAD_MODEL = "com.kofu.assistant.ACTION_CONSOLE_LOAD_MODEL"
        const val ACTION_CONSOLE_STRESS_500 = "com.kofu.assistant.ACTION_CONSOLE_STRESS_500"
        const val ACTION_CONSOLE_STATUS = "com.kofu.assistant.ACTION_CONSOLE_STATUS"

        private val consoleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val consoleQueueMutex = Mutex()
        private val consoleHistory = mutableListOf<TurnMessage>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        val action = intent.action ?: return
        val pendingResult = goAsync()

        when (action) {
            ACTION_CONSOLE_STATUS -> {
                consoleScope.launch {
                    try {
                        LlamaRepository.loadPreferences(appCtx)
                        val ragDocs = KofuRagDatabase.getInstance(appCtx).getDocumentCount()
                        val stats = if (KofuNativeEngine.isNativeReady()) {
                            KofuNativeEngine.nativeGetStatsJson()
                        } else {
                            "{}"
                        }
                        Log.i(
                            TAG,
                            "STATUS | modelLoaded=${LlamaRepository.isModelLoaded} | model=${LlamaRepository.currentModelName} | mode=${LlamaRepository.backendMode} | tier=${KofuNativeEngine.nativeGetActiveHardwareTier()} | ragDocs=$ragDocs | stats=$stats"
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_CONSOLE_LOAD_MODEL -> {
                val modelPath = intent.getStringExtra("path")?.trim() ?: ""
                pendingResult.finish() // Evitar timeout de BroadcastReceiver en cargas de archivos grandes
                consoleScope.launch {
                    if (modelPath.isBlank()) {
                        Log.e(TAG, "LOAD_MODEL: falta parámetro --es path")
                        return@launch
                    }
                    LlamaRepository.loadPreferences(appCtx)
                    synchronized(consoleHistory) { consoleHistory.clear() }
                    val ok = LlamaRepository.loadModelFromPathSync(appCtx, modelPath)
                    Log.i(
                        TAG,
                        "LOAD_MODEL | ok=$ok | path=$modelPath | tier=${KofuNativeEngine.nativeGetActiveHardwareTier()} | stats=${KofuNativeEngine.nativeGetStatsJson()}"
                    )
                }
            }

            ACTION_CONSOLE_PROMPT -> {
                val prompt = intent.getStringExtra("prompt")?.trim() ?: ""
                val maxTok = intent.getIntExtra("max_tokens", 128)
                val keepHistory = intent.getBooleanExtra("keep_history", false)
                pendingResult.finish()
                consoleScope.launch {
                    if (prompt.isBlank()) {
                        Log.e(TAG, "CONSOLE_PROMPT: falta parámetro --es prompt")
                        return@launch
                    }
                    consoleQueueMutex.withLock {
                        Log.i(TAG, "PROMPT_START | max_tokens=$maxTok | keep_history=$keepHistory | prompt=\"$prompt\"")
                        LlamaRepository.loadPreferences(appCtx)
                        KofuRagDatabase.getInstance(appCtx)

                        val turnsToSend = synchronized(consoleHistory) {
                            if (!keepHistory) consoleHistory.clear()
                            consoleHistory.add(TurnMessage("user", prompt))
                            consoleHistory.toList()
                        }

                        val sb = StringBuilder()
                        val t0 = System.currentTimeMillis()
                        LlamaRepository.generateChatStream(
                            history = turnsToSend,
                            maxTokensOverride = maxTok
                        ).collect { chunk ->
                            sb.append(chunk)
                        }
                        val elapsed = System.currentTimeMillis() - t0
                        val response = sb.toString().trim()
                        synchronized(consoleHistory) {
                            consoleHistory.add(TurnMessage("model", response))
                        }

                        // Persistir conversación de consola en caché
                        val sessions = ChatCacheManager.loadSessions(appCtx)
                        val target = sessions.first()
                        target.messages.add(ChatMessageData(sender = "USER", text = prompt))
                        target.messages.add(ChatMessageData(sender = "KOFU", text = response))
                        target.updatedAt = System.currentTimeMillis()
                        ChatCacheManager.flushSync(appCtx, sessions)

                        val m = LlamaRepository.debugMetrics
                        Log.i(
                            TAG,
                            "PROMPT_RESULT | elapsed=${elapsed}ms | ttft=${m.latencyFirstTokenMs}ms | tier=${m.hardwareTier} | rag=${m.activeRagDomain}(${m.ragLookupMs}ms) | tok=${m.tokensGenerated} | tps=${"%.2f".format(m.tokensPerSec)} | availRAM=${m.availableRamMb}MB | rss=${m.memoryRssMb}MB | anon=${m.anonRssMb}MB | stats=${LlamaRepository.lastBenchmarkStats} | prompt=\"$prompt\" | response=\"$response\""
                        )
                    }
                }
            }

            ACTION_CONSOLE_STRESS_500 -> {
                val count = intent.getIntExtra("count", 500)
                val maxTok = intent.getIntExtra("max_tokens", 16)
                val fullLlm = intent.getBooleanExtra("full_llm", false)
                pendingResult.finish()
                consoleScope.launch {
                    consoleQueueMutex.withLock {
                        LlamaRepository.loadPreferences(appCtx)
                        val reportFile: File = LlamaRepository.runAutomatedStressBenchmark(
                            context = appCtx,
                            questionCount = count,
                            maxTokensPerQuestion = maxTok,
                            fullLlmAllQuestions = fullLlm
                        )
                        Log.i(TAG, "STRESS_500_DONE | report=${reportFile.absolutePath}")
                    }
                }
            }

            else -> pendingResult.finish()
        }
    }
}
