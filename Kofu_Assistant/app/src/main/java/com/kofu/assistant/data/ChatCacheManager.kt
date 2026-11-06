package com.kofu.assistant.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ChatMessageData(
    val id: String = UUID.randomUUID().toString(),
    val sender: String, // "USER" o "KOFU"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
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

    fun getCacheFile(context: Context): File {
        return File(context.filesDir, CACHE_FILE_NAME)
    }

    @Synchronized
    fun loadSessions(context: Context): MutableList<ChatSession> {
        val file = getCacheFile(context)
        if (!file.exists() || file.length() == 0L) {
            val defaultSession = ChatSession(
                title = "Conversación inicial",
                messages = mutableListOf(
                    ChatMessageData(
                        sender = "KOFU",
                        text = "Inferencia local activa con llama.cpp ARM64. Modelo listo para consultas."
                    )
                )
            )
            val list = mutableListOf(defaultSession)
            saveSessions(context, list)
            return list
        }

        return try {
            val content = file.readText(Charsets.UTF_8)
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
                            timestamp = mObj.optLong("timestamp", System.currentTimeMillis())
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
                            text = "Inferencia local activa con llama.cpp ARM64. Modelo listo para consultas."
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

    @Synchronized
    fun saveSessions(context: Context, sessions: List<ChatSession>) {
        try {
            val file = getCacheFile(context)
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
                    msgArray.put(mObj)
                }
                sObj.put("messages", msgArray)
                sessionsArray.put(sObj)
            }

            root.put("sessions", sessionsArray)
            file.writeText(root.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando caché de chats", e)
        }
    }
}
