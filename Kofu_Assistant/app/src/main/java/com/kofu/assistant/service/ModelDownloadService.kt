package com.kofu.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kofu.assistant.MainActivity
import com.kofu.assistant.repository.LlamaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Foreground Service para descargar modelos de Inteligencia Artificial en segundo plano.
 * Utiliza descargas atómicas con archivos temporales .part, validación binaria de cabecera
 * (Magic Numbers) y soporte de reanudación mediante HTTP Range requests.
 */
class ModelDownloadService : Service() {

    companion object {
        private const val TAG = "ModelDownloadService"
        const val CHANNEL_ID = "kofu_model_download_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_DOWNLOAD = "com.kofu.assistant.ACTION_START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.kofu.assistant.ACTION_CANCEL_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.kofu.assistant.ACTION_PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.kofu.assistant.ACTION_RESUME_DOWNLOAD"

        const val EXTRA_URL = "extra_download_url"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_MODEL_NAME = "extra_model_name"

        private val SUPPORTED_EXTENSIONS = listOf(".gguf", ".litertlm", ".tflite", ".task", ".bin")

        fun startDownload(context: Context, url: String, fileName: String, modelName: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseDownload(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_PAUSE_DOWNLOAD
            }
            context.startService(intent)
        }

        fun resumeDownload(context: Context, url: String, fileName: String, modelName: String) {
            startDownload(context, url, fileName, modelName)
        }

        fun cancelDownload(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD, ACTION_RESUME_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                    ?: LlamaRepository.currentDownloadingUrl.ifBlank { LlamaRepository.selectedModel.downloadUrl }
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME)
                    ?: LlamaRepository.currentDownloadingFileName.ifBlank { LlamaRepository.selectedModel.fileName }
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME)
                    ?: LlamaRepository.currentDownloadingModelName.ifBlank { LlamaRepository.selectedModel.name }

                if (url.isNotBlank()) {
                    startForegroundNotification(modelName, (LlamaRepository.downloadProgress * 100).toInt())
                    startDownloadProcess(url, fileName, modelName)
                }
            }
            ACTION_PAUSE_DOWNLOAD -> pauseDownloadProcess()
            ACTION_CANCEL_DOWNLOAD -> cancelDownloadProcess()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Descargas de Modelos Aura",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progreso de descarga en segundo plano de modelos LLM"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun pendingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun buildNotification(modelName: String, progress: Int, isIndeterminate: Boolean = false): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            pendingFlags()
        )

        val pauseIntent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_PAUSE_DOWNLOAD
        }
        val pausePendingIntent = PendingIntent.getService(
            this,
            2,
            pauseIntent,
            pendingFlags()
        )

        val cancelIntent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            pendingFlags()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Descargando $modelName")
            .setContentText(if (isIndeterminate) "Conectando con Hugging Face..." else "$progress% completado")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setProgress(100, progress, isIndeterminate)
            .addAction(android.R.drawable.ic_media_pause, "Pausar", pausePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelPendingIntent)
            .build()
    }

    private fun startForegroundNotification(modelName: String, initialProgress: Int) {
        val notification = buildNotification(modelName, initialProgress, initialProgress == 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateProgress(modelName: String, progressPercent: Int) {
        val notification = buildNotification(modelName, progressPercent, false)
        notificationManager.notify(NOTIFICATION_ID, notification)

        LlamaRepository.downloadProgress = (progressPercent / 100f).coerceIn(0f, 1f)
        LlamaRepository.downloadStatusText = "Descargando $modelName ($progressPercent%)"
    }

    private fun hasValidModelHeader(file: File, fileName: String): Boolean {
        if (!file.exists() || file.length() < 16L) return false
        val header = ByteArray(8)
        val readCount = FileInputStream(file).use { it.read(header) }
        if (readCount < 8) return false

        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".gguf") -> {
                header[0] == 'G'.code.toByte() &&
                    header[1] == 'G'.code.toByte() &&
                    header[2] == 'U'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
            lower.endsWith(".litertlm") || lower.endsWith(".tflite") -> {
                header[4] == 'T'.code.toByte() &&
                    header[5] == 'F'.code.toByte() &&
                    header[6] == 'L'.code.toByte() &&
                    header[7] == '3'.code.toByte()
            }
            else -> true
        }
    }

    private fun promotePartFile(partFile: File, targetFile: File): Boolean {
        if (targetFile.exists() && !targetFile.delete()) {
            Log.w(TAG, "No se pudo eliminar archivo previo ${targetFile.name}")
        }
        if (partFile.renameTo(targetFile)) {
            return true
        }
        return try {
            partFile.inputStream().use { inp ->
                FileOutputStream(targetFile).use { out ->
                    inp.copyTo(out)
                }
            }
            partFile.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fallo promoviendo archivo temporal ${partFile.name}", e)
            false
        }
    }

    private fun startDownloadProcess(url: String, fileName: String, modelName: String) {
        downloadJob?.cancel()

        if (!url.startsWith("https://", ignoreCase = true)) {
            notifyError("Solo se permiten descargas cifradas (HTTPS)")
            return
        }

        val safeFileName = File(fileName).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (SUPPORTED_EXTENSIONS.none { safeFileName.endsWith(it, ignoreCase = true) }) {
            notifyError("Tipo de archivo de modelo no permitido")
            return
        }

        val externalDir = getExternalFilesDir(null)
        if (externalDir == null) {
            notifyError("Almacenamiento no disponible")
            return
        }

        val targetFile = File(externalDir, safeFileName)
        val partFile = File(externalDir, "$safeFileName.part")
        if (!targetFile.canonicalPath.startsWith(externalDir.canonicalPath) ||
            !partFile.canonicalPath.startsWith(externalDir.canonicalPath)
        ) {
            notifyError("Ruta de archivo no permitida (Path Traversal)")
            return
        }

        val modelOpt = (LlamaRepository.AVAILABLE_MODELS + LlamaRepository.EMBEDDER_MODEL)
            .find { it.fileName == safeFileName }
            ?: LlamaRepository.selectedModel
        val expectedMin = LlamaRepository.getExpectedMinSize(modelOpt)

        // Migrar archivo parcial previo si existiera sin extensión .part
        if (targetFile.exists() && targetFile.length() in 1 until expectedMin && !partFile.exists()) {
            targetFile.renameTo(partFile)
        }

        LlamaRepository.isDownloadingModel = true
        LlamaRepository.isDownloadPaused = false
        LlamaRepository.currentDownloadingUrl = url
        LlamaRepository.currentDownloadingFileName = safeFileName
        LlamaRepository.currentDownloadingModelName = modelName

        val existingLen = if (partFile.exists()) partFile.length() else 0L
        if (existingLen > 0 && expectedMin > 0) {
            val initialPct = ((existingLen * 100) / expectedMin).toInt().coerceIn(0, 99)
            LlamaRepository.downloadProgress = initialPct / 100f
            LlamaRepository.downloadStatusText = "Reanudando $modelName ($initialPct%)..."
        } else {
            LlamaRepository.downloadProgress = 0f
            LlamaRepository.downloadStatusText = "Conectando con Hugging Face..."
        }

        downloadJob = serviceScope.launch {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            var attempt = 0
            val maxAttempts = 3
            var completedSuccessfully = false

            while (attempt < maxAttempts && !completedSuccessfully && isActive) {
                val existingLength = if (partFile.exists()) partFile.length() else 0L

                val requestBuilder = Request.Builder().url(url)
                if (existingLength > 0) {
                    requestBuilder.header("Range", "bytes=$existingLength-")
                }

                try {
                    client.newCall(requestBuilder.build()).execute().use { response ->
                        val code = response.code
                        val isResume = (code == 206)

                        if (code == 416) {
                            if (partFile.exists() &&
                                partFile.length() >= expectedMin &&
                                hasValidModelHeader(partFile, safeFileName) &&
                                promotePartFile(partFile, targetFile)
                            ) {
                                onDownloadSuccess(targetFile, modelName)
                                completedSuccessfully = true
                                return@launch
                            }
                            // Archivo parcial corrupto o inconsistente: reiniciar descarga limpia
                            partFile.delete()
                            attempt++
                            return@use
                        }

                        if (code != 200 && code != 206) {
                            notifyError("Error del servidor: HTTP $code")
                            return@launch
                        }

                        val body = response.body
                        if (body == null) {
                            notifyError("Cuerpo de respuesta vacío")
                            return@launch
                        }

                        val contentLength = body.contentLength()
                        val totalLength = when {
                            contentLength <= 0L -> -1L
                            isResume -> existingLength + contentLength
                            else -> contentLength
                        }

                        val inputStream = body.byteStream()
                        val outputStream = FileOutputStream(partFile, isResume)
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = if (isResume) existingLength else 0L
                        var lastProgressUpdate = 0L
                        var lastReportedPercent = -1

                        outputStream.use { out ->
                            inputStream.use { inp ->
                                while (isActive) {
                                    val read = inp.read(buffer)
                                    if (read == -1) break
                                    out.write(buffer, 0, read)
                                    downloaded += read

                                    val now = System.currentTimeMillis()
                                    val percent = if (totalLength > 0) {
                                        ((downloaded * 100) / totalLength).toInt().coerceIn(0, 100)
                                    } else {
                                        0
                                    }
                                    if ((now - lastProgressUpdate >= 1000L && percent != lastReportedPercent) ||
                                        (totalLength > 0 && downloaded == totalLength)
                                    ) {
                                        withContext(Dispatchers.Main) {
                                            updateProgress(modelName, percent)
                                        }
                                        lastProgressUpdate = now
                                        lastReportedPercent = percent
                                    }
                                }
                            }
                        }

                        if (!isActive) return@launch

                        val sizeComplete = (totalLength <= 0L || downloaded >= totalLength) &&
                            partFile.length() >= expectedMin
                        if (!sizeComplete) {
                            attempt++
                            Log.w(TAG, "Descarga incompleta ($downloaded / $totalLength bytes), reintentando...")
                            delay(1500L * attempt)
                            return@use
                        }

                        if (!hasValidModelHeader(partFile, safeFileName)) {
                            partFile.delete()
                            notifyError("El archivo descargado no tiene una cabecera válida ($safeFileName)")
                            return@launch
                        }

                        if (!promotePartFile(partFile, targetFile)) {
                            notifyError("No se pudo guardar el modelo en almacenamiento")
                            return@launch
                        }

                        onDownloadSuccess(targetFile, modelName)
                        completedSuccessfully = true
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    attempt++
                    Log.w(TAG, "Intento $attempt/$maxAttempts fallido: ${e.message}")
                    if (attempt < maxAttempts && isActive) {
                        withContext(Dispatchers.Main) {
                            LlamaRepository.downloadStatusText = "Reconectando con Hugging Face (intento $attempt/$maxAttempts)..."
                        }
                        delay(2000L * attempt)
                    } else if (isActive) {
                        Log.e(TAG, "Excepción definitiva durante descarga en segundo plano", e)
                        notifyError("Error de red tras $maxAttempts intentos: ${e.localizedMessage ?: "Conexión perdida"}")
                    }
                }
            }
        }
    }

    private suspend fun onDownloadSuccess(targetFile: File, modelName: String) {
        withContext(Dispatchers.Main) {
            LlamaRepository.isDownloadingModel = false
            LlamaRepository.isDownloadPaused = false
            LlamaRepository.downloadProgress = 1.0f
            LlamaRepository.downloadStatusText = "Descarga completada"

            val successNotification = NotificationCompat.Builder(this@ModelDownloadService, CHANNEL_ID)
                .setContentTitle("Descarga completada")
                .setContentText("$modelName listo para usarse")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()

            notificationManager.notify(NOTIFICATION_ID, successNotification)

            LlamaRepository.onDownloadedModelReady(this@ModelDownloadService, targetFile)

            ServiceCompat.stopForeground(this@ModelDownloadService, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun pauseDownloadProcess() {
        downloadJob?.cancel()
        downloadJob = null

        val currentModel = LlamaRepository.currentDownloadingModelName.ifBlank { "Modelo Aura" }
        val currentPercent = (LlamaRepository.downloadProgress * 100).toInt()

        LlamaRepository.isDownloadingModel = false
        LlamaRepository.isDownloadPaused = true
        LlamaRepository.downloadStatusText = "Descarga pausada ($currentPercent%)"

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            pendingFlags()
        )

        val resumeIntent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_RESUME_DOWNLOAD
        }
        val resumePendingIntent = PendingIntent.getService(
            this,
            3,
            resumeIntent,
            pendingFlags()
        )

        val cancelIntent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            pendingFlags()
        )

        val pausedNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Descarga en pausa: $currentModel")
            .setContentText("$currentPercent% descargado. Toca Reanudar para continuar.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setProgress(100, currentPercent, false)
            .addAction(android.R.drawable.ic_media_play, "Reanudar", resumePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, pausedNotification)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun cancelDownloadProcess() {
        downloadJob?.cancel()
        downloadJob = null

        val externalDir = getExternalFilesDir(null)
        val targetName = LlamaRepository.currentDownloadingFileName
        if (externalDir != null && targetName.isNotBlank()) {
            val partFile = File(externalDir, "$targetName.part")
            if (partFile.exists()) partFile.delete()
        }

        LlamaRepository.isDownloadingModel = false
        LlamaRepository.isDownloadPaused = false
        LlamaRepository.downloadProgress = 0f
        LlamaRepository.downloadStatusText = "Descarga cancelada"

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notifyError(errorMsg: String) {
        downloadJob?.cancel()
        downloadJob = null

        val currentModel = LlamaRepository.currentDownloadingModelName.ifBlank { "Modelo Aura" }
        val currentPercent = (LlamaRepository.downloadProgress * 100).toInt()

        LlamaRepository.isDownloadingModel = false
        LlamaRepository.isDownloadPaused = true
        LlamaRepository.downloadStatusText = "Descarga interrumpida: $errorMsg. Toca Reanudar."

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            pendingFlags()
        )

        val resumeIntent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_RESUME_DOWNLOAD
        }
        val resumePendingIntent = PendingIntent.getService(
            this,
            3,
            resumeIntent,
            pendingFlags()
        )

        val cancelIntent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            pendingFlags()
        )

        val errorNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Descarga interrumpida ($currentModel)")
            .setContentText("Sin conexión a internet o error de red. Toca Reanudar.")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .setProgress(100, currentPercent, false)
            .addAction(android.R.drawable.ic_media_play, "Reanudar", resumePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, errorNotification)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
