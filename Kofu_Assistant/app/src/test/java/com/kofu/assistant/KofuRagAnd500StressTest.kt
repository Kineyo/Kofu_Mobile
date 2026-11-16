package com.kofu.assistant

import com.kofu.assistant.data.ChatCacheManager
import com.kofu.assistant.data.ChatMessageData
import com.kofu.assistant.data.ChatSession
import com.kofu.assistant.data.KofuEditionProfile
import com.kofu.assistant.data.SiliconEdition
import com.kofu.assistant.data.rag.DynamicDomainRouter
import com.kofu.assistant.data.rag.HallucinationGuard
import com.kofu.assistant.data.rag.KofuBenchmark500Suite
import com.kofu.assistant.data.rag.KofuRagDatabase
import com.kofu.assistant.data.rag.RagDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KofuRagAnd500StressTest {

    private fun openShardsStream(): java.io.InputStream {
        val candidates = listOf(
            File("src/main/assets/rag/kofu_knowledge_shards.json"),
            File("app/src/main/assets/rag/kofu_knowledge_shards.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("No se encontró kofu_knowledge_shards.json en ${candidates.map { it.absolutePath }}")
        return file.inputStream()
    }

    @Test
    fun `500 question stress suite routes domains dynamically with low latency and zero overflow`() {
        val db = KofuRagDatabase.createInMemoryForTest(openShardsStream())
        assertTrue("La base RAG debe contener al menos 50 documentos verificados", db.getDocumentCount() >= 50)

        val questions = KofuBenchmark500Suite.generate500Questions()
        assertEquals(500, questions.size)

        var thematicTotal = 0
        var thematicMatches = 0
        var maxRagLatencyMs = 0L
        var totalRagLatencyMs = 0L
        var chitchatTokensTotal = 0

        val session = ChatSession(
            title = "Test 500",
            messages = mutableListOf(
                ChatMessageData(sender = "KOFU", text = "Aviso inicial", isSystemNotice = true)
            )
        )
        val sessions = listOf(session)

        for (q in questions) {
            val rag = db.retrieveDynamicContext(q.prompt)
            totalRagLatencyMs += rag.lookupTimeMs
            if (rag.lookupTimeMs > maxRagLatencyMs) maxRagLatencyMs = rag.lookupTimeMs

            // Presupuesto estricto para proteger velocidad por token
            assertTrue(
                "Contexto RAG excede 140 tokens (${rag.estimatedTokens}) en pregunta #${q.id}",
                rag.estimatedTokens <= 140
            )

            if (rag.domain == RagDomain.CHITCHAT) {
                chitchatTokensTotal += rag.estimatedTokens
            }

            if (q.category != "STRESS_ANTI_HALLUCINATION") {
                thematicTotal++
                if (rag.domain == q.expectedDomain) thematicMatches++
            }

            val grounded = HallucinationGuard.prepareGroundedPrompt(
                userQuery = q.prompt,
                ragResult = rag,
                baseTemperature = 0.7f,
                baseSystemPrompt = "Eres Kofu."
            )

            if (q.isAntiHallucinationTrap) {
                assertTrue(
                    "Temp capping debe aplicarse a trampa anti-alucinación #${q.id}",
                    grounded.effectiveTemperature <= 0.4f
                )
            }

            session.messages.add(ChatMessageData(sender = "USER", text = q.prompt))
            session.messages.add(ChatMessageData(sender = "KOFU", text = rag.formattedContext.ifBlank { "OK" }))
            ChatCacheManager.compactSessionsInPlace(sessions)
        }

        val accuracyPct = (thematicMatches * 100.0) / thematicTotal
        val avgRagMs = totalRagLatencyMs.toDouble() / questions.size

        assertTrue("Precisión de enrutamiento temático ($accuracyPct%) debe ser >= 95%", accuracyPct >= 95.0)
        assertTrue("Latencia media RAG (${avgRagMs}ms) debe ser < 5ms", avgRagMs < 5.0)
        assertEquals("Chitchat no debe inyectar tokens RAG", 0, chitchatTokensTotal)
        assertTrue(
            "La compactación debe limitar la sesión en RAM a ${ChatCacheManager.MAX_IN_MEMORY_MESSAGES_PER_SESSION}",
            session.messages.size <= ChatCacheManager.MAX_IN_MEMORY_MESSAGES_PER_SESSION
        )
    }

    @Test
    fun `hallucination guard trims repetitive degenerative loops`() {
        val looped = (1..8).joinToString("\n") {
            "Esta es una frase repetida exactamente igual en bucle infinito."
        }
        val cleaned = HallucinationGuard.sanitizeAndValidateResponse(looped)
        assertEquals(2, cleaned.lines().size)
    }

    @Test
    fun `silicon editions enforce normalized limits per hardware tier`() {
        val legacy = KofuEditionProfile.fromCode("7NM_LEGACY")
        val mainstream = KofuEditionProfile.fromCode("5NM_MAINSTREAM")
        val frontier = KofuEditionProfile.fromCode("FRONTIER_SUB5NM")

        assertEquals(SiliconEdition.NODE_7NM_LEGACY, legacy)
        assertEquals("kofu-go-1b", legacy.defaultModelId)
        assertEquals(SiliconEdition.NODE_5NM_MAINSTREAM, mainstream)
        assertEquals(SiliconEdition.FRONTIER_SUB5NM, frontier)

        assertTrue(legacy.defaultMaxTokens < mainstream.defaultMaxTokens)
        assertTrue(mainstream.defaultMaxTokens < frontier.defaultMaxTokens)
        assertTrue(legacy.maxRagContextChars < mainstream.maxRagContextChars)
        assertTrue(mainstream.maxRagContextChars < frontier.maxRagContextChars)

        KofuEditionProfile.configureForDeviceRam(4L * 1024L * 1024L * 1024L)
        assertTrue(KofuEditionProfile.isStrict2GbBudgetActive)
        assertEquals(1, KofuEditionProfile.effectiveMaxRagChunks)
        assertEquals(180, KofuEditionProfile.effectiveMaxRagContextChars)
        assertTrue(KofuEditionProfile.effectiveMaxTokens <= 256)
    }

    @Test
    fun `rrf fusion combines bm25 and semantic rankings deterministically`() {
        val d1 = com.kofu.assistant.data.rag.RagDocument("d1", RagDomain.AI_EDGE, "KV Cache", "kv", "Q8_0 KV")
        val d2 = com.kofu.assistant.data.rag.RagDocument("d2", RagDomain.AI_EDGE, "LMK Guard", "lmk", "RssAnon")
        val d3 = com.kofu.assistant.data.rag.RagDocument("d3", RagDomain.AI_EDGE, "Embedder", "rag", "MiniLM")

        val bm25 = listOf(d1 to 2.4, d2 to 1.5)
        val semantic = listOf(d2 to 0.85f, d3 to 0.72f, d1 to 0.10f)

        val fused = KofuRagDatabase.computeRrfFusion(bm25, semantic, limit = 2)
        assertEquals(2, fused.size)
        assertEquals("d2", fused[0].first.id)
        assertTrue(fused[0].second > fused[1].second)
    }

    @Test
    fun `dataset hash is deterministic regardless of document order`() {
        val d1 = com.kofu.assistant.data.rag.RagDocument("a", RagDomain.AI_EDGE, "T1", "k1", "C1")
        val d2 = com.kofu.assistant.data.rag.RagDocument("b", RagDomain.CS_PROGRAMMING, "T2", "k2", "C2")
        assertEquals(
            KofuRagDatabase.computeDatasetHash(listOf(d1, d2)),
            KofuRagDatabase.computeDatasetHash(listOf(d2, d1))
        )
    }

    @Test
    fun `dynamic domain router classifies chitchat with zero overhead`() {
        assertEquals(RagDomain.CHITCHAT, DynamicDomainRouter.classify("Hola"))
        assertEquals(RagDomain.CHITCHAT, DynamicDomainRouter.classify("Hola Kofu"))
        assertEquals(RagDomain.CHITCHAT, DynamicDomainRouter.classify("Hola Aura"))
        assertEquals(RagDomain.CHITCHAT, DynamicDomainRouter.classify("Muchas gracias"))
        assertEquals(RagDomain.CHITCHAT, DynamicDomainRouter.classify("me siento triste"))
        assertEquals(RagDomain.CHITCHAT, DynamicDomainRouter.classify("eso que tiene que"))
        assertEquals(RagDomain.CHITCHAT, DynamicDomainRouter.classify("me siento mal"))
        assertEquals(RagDomain.KOFU_SYSTEM, DynamicDomainRouter.classify("¿Quién eres y cómo te llamas?"))
        assertEquals(RagDomain.GENERAL_FACTS_ES, DynamicDomainRouter.classify("Reglas de acentuación en español agudas y esdrújulas"))
        assertEquals(RagDomain.CS_PROGRAMMING, DynamicDomainRouter.classify("Explica punteros y std::unique_ptr en C++20"))
        assertEquals(RagDomain.AI_EDGE, DynamicDomainRouter.classify("Cómo funciona el fallback NPU GPU CPU en Snapdragon llama.cpp"))
    }
}
