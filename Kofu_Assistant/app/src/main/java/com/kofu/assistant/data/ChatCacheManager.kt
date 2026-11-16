package com.kofu.assistant.data

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ChatMessageData(
    val id: String = UUID.randomUUID().toString(),
    val sender: String, // "USER" o "KOFU"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSystemNotice: Boolean = false
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var isPinned: Boolean = false,
    var isArchived: Boolean = false,
    val messages: MutableList<ChatMessageData> = mutableListOf()
)

/**
 * Gestor de persistencia en disco (JSON Cache) para las conversaciones de Kofu Assistant.
 * Almacena el historial en el almacenamiento interno privado de la aplicación (context.filesDir).
 */
object ChatCacheManager {
    private const val TAG = "ChatCacheManager"
    private const val CACHE_FILE_NAME = "kofu_chats_cache.json"
    val MAX_IN_MEMORY_MESSAGES_PER_SESSION: Int
        get() = KofuEditionProfile.current.maxInMemoryMessages

    private val cacheScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeMutex = Mutex()

    fun getCacheFile(context: Context): File {
        return File(context.filesDir, CACHE_FILE_NAME)
    }

    @Synchronized
    fun loadSessions(context: Context): MutableList<ChatSession> {
        val file = getCacheFile(context)
        val atomicFile = AtomicFile(file)
        if (!file.exists() || file.length() == 0L) {
            val defaultSession = ChatSession(
                title = "Conversación inicial",
                messages = mutableListOf(
                    ChatMessageData(
                        sender = "KOFU",
                        text = "Inferencia local activa con llama.cpp ARM64. Modelo listo para consultas.",
                        isSystemNotice = true
                    )
                )
            )
            val list = mutableListOf(defaultSession)
            saveSessions(context, list)
            return list
        }

        return try {
            val content = String(atomicFile.readFully(), Charsets.UTF_8)
            val root = JSONObject(content)
            val sessionsArray = root.optJSONArray("sessions") ?: JSONArray()
            val list = mutableListOf<ChatSession>()

            for (i in 0 until sessionsArray.length()) {
                val sObj = sessionsArray.getJSONObject(i)
                val id = sObj.optString("id", UUID.randomUUID().toString())
                val title = sObj.optString("title", "Conversación")
                val createdAt = sObj.optLong("createdAt", System.currentTimeMillis())
                val updatedAt = sObj.optLong("updatedAt", createdAt)
                val isPinned = sObj.optBoolean("isPinned", false)
                val isArchived = sObj.optBoolean("isArchived", false)

                val msgArray = sObj.optJSONArray("messages") ?: JSONArray()
                val messages = mutableListOf<ChatMessageData>()

                for (j in 0 until msgArray.length()) {
                    val mObj = msgArray.getJSONObject(j)
                    messages.add(
                        ChatMessageData(
                            id = mObj.optString("id", UUID.randomUUID().toString()),
                            sender = mObj.optString("sender", "USER"),
                            text = mObj.optString("text", ""),
                            timestamp = mObj.optLong("timestamp", System.currentTimeMillis()),
                            isSystemNotice = mObj.optBoolean("isSystemNotice", false)
                        )
                    )
                }

                list.add(
                    ChatSession(
                        id = id,
                        title = title,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        isPinned = isPinned,
                        isArchived = isArchived,
                        messages = messages
                    )
                )
            }

            if (list.isEmpty()) {
                val defaultSession = ChatSession(
                    title = "Conversación inicial",
                    messages = mutableListOf(
                        ChatMessageData(
                            sender = "KOFU",
                            text = "Inferencia local activa con llama.cpp ARM64. Modelo listo para consultas.",
                            isSystemNotice = true
                        )
                    )
                )
                list.add(defaultSession)
                saveSessions(context, list)
            }

            list
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando caché de chats", e)
            mutableListOf()
        }
    }

    /**
     * Compacta mensajes antiguos cuando una sesión supera MAX_IN_MEMORY_MESSAGES_PER_SESSION
     * conservando el aviso inicial y los turnos más recientes para evitar crecimiento ilimitado de RAM.
     */
    fun compactSessionsInPlace(sessions: List<ChatSession>) {
        for (session in sessions) {
            if (session.messages.size > MAX_IN_MEMORY_MESSAGES_PER_SESSION) {
                val first = session.messages.firstOrNull()
                val tailCount = MAX_IN_MEMORY_MESSAGES_PER_SESSION - if (first?.isSystemNotice == true) 1 else 0
                val recent = session.messages.takeLast(tailCount)
                session.messages.clear()
                if (first?.isSystemNotice == true) {
                    session.messages.add(first)
                }
                session.messages.addAll(recent)
            }
        }
    }

    /**
     * Toma una instantánea inmutable en el hilo llamador y persiste el caché en Dispatchers.IO
     * sin bloquear el hilo principal de la UI.
     */
    fun saveSessionsAsync(context: Context, sessions: List<ChatSession>) {
        val appContext = context.applicationContext
        compactSessionsInPlace(sessions)
        val snapshot = sessions.map { session ->
            session.copy(messages = session.messages.toMutableList())
        }
        cacheScope.launch {
            writeMutex.withLock {
                saveSessions(appContext, snapshot)
            }
        }
    }

    /**
     * Persiste inmediatamente las sesiones de forma síncrona (útil en onStop o pruebas por consola).
     */
    fun flushSync(context: Context, sessions: List<ChatSession>) {
        compactSessionsInPlace(sessions)
        saveSessions(context.applicationContext, sessions)
    }

    @Synchronized
    fun saveSessions(context: Context, sessions: List<ChatSession>) {
        val file = getCacheFile(context)
        val atomicFile = AtomicFile(file)
        var fos: FileOutputStream? = null
        try {
            val root = JSONObject()
            val sessionsArray = JSONArray()

            for (session in sessions) {
                val sObj = JSONObject()
                sObj.put("id", session.id)
                sObj.put("title", session.title)
                sObj.put("createdAt", session.createdAt)
                sObj.put("updatedAt", session.updatedAt)
                sObj.put("isPinned", session.isPinned)
                sObj.put("isArchived", session.isArchived)

                val msgArray = JSONArray()
                for (m in session.messages) {
                    val mObj = JSONObject()
                    mObj.put("id", m.id)
                    mObj.put("sender", m.sender)
                    mObj.put("text", m.text)
                    mObj.put("timestamp", m.timestamp)
                    if (m.isSystemNotice) {
                        mObj.put("isSystemNotice", true)
                    }
                    msgArray.put(mObj)
                }
                sObj.put("messages", msgArray)
                sessionsArray.put(sObj)
            }

            root.put("sessions", sessionsArray)
            val bytes = root.toString().toByteArray(Charsets.UTF_8)
            fos = atomicFile.startWrite()
            fos.write(bytes)
            atomicFile.finishWrite(fos)
        } catch (e: Exception) {
            if (fos != null) {
                atomicFile.failWrite(fos)
            }
            Log.e(TAG, "Error guardando caché de chats con AtomicFile", e)
        }
    }
}
