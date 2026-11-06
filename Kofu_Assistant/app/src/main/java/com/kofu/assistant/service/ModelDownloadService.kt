package com.kofu.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kofu.assistant.MainActivity
import com.kofu.assistant.repository.LlamaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Foreground Service para descargar modelos de Inteligencia Artificial en segundo plano.
 * Proporciona una notificación persistente con barra de progreso, soporte para reanudación
 * (HTTP Range requests) y permite que la descarga continúe incluso si el usuario sale de la aplicación.
 */
class ModelDownloadService : Service() {

    companion object {
        private const val TAG = "ModelDownloadService"
        const val CHANNEL_ID = "kofu_model_download_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_DOWNLOAD = "com.kofu.assistant.ACTION_START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.kofu.assistant.ACTION_CANCEL_DOWNLOAD"

        const val EXTRA_URL = "extra_download_url"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_MODEL_NAME = "extra_model_name"

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
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: ""
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "model.gguf"
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "Modelo Kofu"

                if (url.isNotBlank()) {
                    startForegroundNotification(modelName, 0)
                    startDownloadProcess(url, fileName, modelName)
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                cancelDownloadProcess()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Descargas de Modelos Kofu",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progreso de descarga en segundo plano de modelos LLM"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, ModelDownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Descargando $modelName")
            .setContentText(if (isIndeterminate) "Conectando con Hugging Face..." else "$progress% completado")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setProgress(100, progress, isIndeterminate)
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

        LlamaRepository.downloadProgress = progressPercent / 100f
        LlamaRepository.downloadStatusText = "Descargando $modelName ($progressPercent%)"
    }

    private fun startDownloadProcess(url: String, fileName: String, modelName: String) {
        downloadJob?.cancel()

        LlamaRepository.isDownloadingModel = true
        LlamaRepository.downloadProgress = 0f
        LlamaRepository.downloadStatusText = "Conectando con Hugging Face..."

        val externalDir = getExternalFilesDir(null)
        if (externalDir == null) {
            notifyError("Almacenamiento no disponible")
            return
        }

        val targetFile = File(externalDir, fileName)

        downloadJob = serviceScope.launch {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            var existingLength = 0L
            if (targetFile.exists()) {
                existingLength = targetFile.length()
            }

            val requestBuilder = Request.Builder().url(url)
            if (existingLength > 0) {
                requestBuilder.header("Range", "bytes=$existingLength-")
            }

            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    val code = response.code
                    val isResume = (code == 206)

                    if (code != 200 && code != 206) {
                        if (code == 416) { // Range ya completado
                            onDownloadSuccess(targetFile, modelName)
                            return@launch
                        }
                        notifyError("Error del servidor: HTTP $code")
                        return@launch
                    }

                    val body = response.body
                    if (body == null) {
                        notifyError("Cuerpo de respuesta vacío")
                        return@launch
                    }

                    val contentLength = body.contentLength()
                    val totalLength = if (isResume) existingLength + contentLength else contentLength

                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(targetFile, isResume)
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    var downloaded = if (isResume) existingLength else 0L
                    var lastProgressUpdate = System.currentTimeMillis()

                    outputStream.use { out ->
                        inputStream.use { inp ->
                            while (inp.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                if (now - lastProgressUpdate > 300 || downloaded == totalLength) {
                                    val percent = if (totalLength > 0) ((downloaded * 100) / totalLength).toInt() else 0
                                    withContext(Dispatchers.Main) {
                                        updateProgress(modelName, percent)
                                    }
                                    lastProgressUpdate = now
                                }
                            }
                        }
                    }

                    if (totalLength <= 0 || downloaded >= totalLength) {
                        onDownloadSuccess(targetFile, modelName)
                    } else {
                        notifyError("Descarga incompleta")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Excepción durante descarga en segundo plano", e)
                notifyError("Error de red: ${e.localizedMessage ?: "Conexión perdida"}")
            }
        }
    }

    private suspend fun onDownloadSuccess(targetFile: File, modelName: String) {
        withContext(Dispatchers.Main) {
            LlamaRepository.isDownloadingModel = false
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

            // Cargar automáticamente en el motor nativo
            LlamaRepository.onDownloadedModelReady(this@ModelDownloadService, targetFile)

            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun cancelDownloadProcess() {
        downloadJob?.cancel()
        downloadJob = null

        LlamaRepository.isDownloadingModel = false
        LlamaRepository.downloadStatusText = "Descarga cancelada"

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notifyError(errorMsg: String) {
        LlamaRepository.isDownloadingModel = false
        LlamaRepository.downloadStatusText = errorMsg

        val errorNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Descarga interrumpida")
            .setContentText(errorMsg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, errorNotification)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
