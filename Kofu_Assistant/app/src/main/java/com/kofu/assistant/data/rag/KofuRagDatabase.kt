package com.kofu.assistant.data.rag

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.kofu.assistant.data.KofuEditionProfile
import com.kofu.assistant.repository.KofuNativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.ln

enum class RagDomain(val id: String, val displayName: String) {
    CHITCHAT("CHITCHAT", "Conversación Directa"),
    CS_PROGRAMMING("CS_PROGRAMMING", "Ingeniería de Software & Sistemas"),
    MATH_SCIENCE("MATH_SCIENCE", "Matemáticas & Ciencias Exactas"),
    AI_EDGE("AI_EDGE", "IA Edge & Arquitectura Snapdragon"),
    GENERAL_FACTS_ES("GENERAL_FACTS_ES", "Español, Humanidades & Conocimiento General"),
    KOFU_SYSTEM("KOFU_SYSTEM", "Identidad & Autonomía Aura")
}

data class RagDocument(
    val id: String,
    val domain: RagDomain,
    val title: String,
    val keywords: String,
    val content: String
)

data class RagRetrievalResult(
    val domain: RagDomain,
    val matchedChunks: List<RagDocument>,
    val formattedContext: String,
    val lookupTimeMs: Long,
    val estimatedTokens: Int,
    val topScore: Double = 0.0
)

/**
 * Clasificador léxico de dominio O(1) para asignar dinámicamente el shard de base de datos
 * apropiado o evitar inyectar contexto RAG en saludos/charla casual (maximizando tokens/s).
 */
object DynamicDomainRouter {
    private val STOPWORDS = setOf(
        "de", "la", "que", "el", "en", "y", "a", "los", "del", "se", "las", "por", "un", "para",
        "con", "no", "una", "su", "al", "lo", "como", "mas", "pero", "sus", "le", "ya", "o",
        "este", "si", "porque", "esta", "entre", "cuando", "muy", "sin", "sobre", "tambien",
        "me", "hasta", "hay", "donde", "quien", "desde", "todo", "nos", "durante", "todos",
        "uno", "les", "ni", "contra", "otros", "ese", "eso", "ante", "ellos", "e", "esto",
        "mi", "antes", "algunos", "unos", "yo", "otro", "otras", "otra", "el", "cual", "es",
        "son", "fue", "ser", "tiene", "como", "cual", "cuales", "cuanto", "cuantos", "explica",
        "dime", "que", "significa", "puedes", "porfavor", "favor", "hola"
    )

    private val CHITCHAT_EXACT = setOf(
        "hola", "buenos dias", "buenas tardes", "buenas noches", "hey", "hi", "hello",
        "que tal", "como estas", "gracias", "muchas gracias", "ok", "vale", "perfecto",
        "adios", "hasta luego", "nos vemos", "genial", "excelente", "buen dia"
    )

    private val DOMAIN_TRIGGERS: Map<RagDomain, Set<String>> = mapOf(
        RagDomain.KOFU_SYSTEM to setOf(
            "aura", "kofu", "quien eres", "como te llamas", "tu nombre", "nombre", "identidad",
            "modo avion", "privacidad", "offline", "autonomo",
            "internet", "clima hoy", "noticias hoy", "tiempo real", "bolsa hoy", "nube"
        ),
        RagDomain.AI_EDGE to setOf(
            "llama", "llamacpp", "ggml", "gguf", "litert", "npu", "hexagon", "adreno",
            "kryo", "snapdragon", "armv8", "arm64", "cuantizacion", "q4", "q8", "kv",
            "cache", "prefill", "ttft", "tokens", "token", "rag", "fts4", "bm25", "alucinacion",
            "alucinaciones", "temperatura", "top_p", "top_k", "repeat_penalty", "gemma", "transformer",
            "atencion", "flashattention", "prompt", "prompts", "injection", "oom", "lmk", "ontrimmemory",
            "context", "shifting", "iswa", "kleidiai", "repack", "vulkan", "opencl",
            "sampler", "greedy", "htp", "swa", "full", "turno", "bienvenida", "2b", "4b", "q4_k_m"
        ),
        RagDomain.CS_PROGRAMMING to setOf(
            "c++", "cpp", "puntero", "punteros", "referencia", "referencias", "nullptr",
            "unique_ptr", "shared_ptr", "unique", "shared", "ptr", "raii", "mutex", "scoped",
            "atomic", "deadlock", "kotlin", "coroutine", "corrutina", "coroutines", "corrutinas",
            "dispatcher", "dispatchers", "flow", "stateflow", "callbackflow", "awaitclose",
            "android", "ndk", "jni", "fdsan", "sigabrt", "descriptor", "compose", "recomposicion",
            "recomposiciones", "lazycolumn", "derivedstateof", "python", "gil", "recursiva",
            "lru", "algoritmo", "algoritmos", "complejidad", "big", "quicksort", "mergesort",
            "binaria", "hash", "grafo", "arbol", "bfs", "dfs", "sqlite", "sql", "linux",
            "posix", "mmap", "meminfo", "statm", "tcp", "udp", "http", "tls", "git",
            "rebase", "merge", "revert", "solid", "seguridad", "traversal", "canonicalpath",
            "hilo", "hilos", "proceso", "procesos", "compilar", "cmake", "16kb", "coffman",
            "sched_setaffinity", "setaffinity"
        ),
        RagDomain.MATH_SCIENCE to setOf(
            "matematica", "matematicas", "ecuacion", "cuadratica", "derivada", "integral",
            "calculo", "algebra", "matriz", "vector", "determinante", "logaritmo", "pitagoras",
            "bayes", "probabilidad", "varianza", "desviacion", "normal", "sigma", "primo",
            "sucesion", "fibonacci", "euler", "pi", "aureo", "razon", "aurea", "phi",
            "geometria", "area", "volumen", "esfera", "circulo", "cilindro", "cono", "triangulo",
            "fisica", "newton", "fuerza", "inercia", "cinetica", "termodinamica", "entropia",
            "entalpia", "absoluto", "ohm", "voltaje", "resistencia", "maxwell", "electromagnetismo",
            "electricidad", "magnetismo", "luz", "planck", "avogadro", "gravedad", "quimica",
            "atomo", "atomico", "proton", "protones", "ph", "acido", "enlace", "covalente",
            "ionico", "biologia", "adn", "arn", "mitocondria", "atp", "fotosintesis", "astronomia",
            "sol", "tierra", "planeta", "planetas", "unidad", "astronomica", "celsius", "fahrenheit"
        ),
        RagDomain.GENERAL_FACTS_ES to setOf(
            "geografia", "continente", "continentes", "oceano", "oceanos", "everest",
            "amazonas", "nilo", "africa", "caudal", "capital", "capitales", "mexico", "espana",
            "colombia", "argentina", "peru", "chile", "brasil", "japon", "francia", "historia",
            "imprenta", "gutenberg", "revolucion", "guerra", "mundial", "apolo", "luna",
            "independencia", "tenochtitlan", "colon", "america", "salud", "temperatura", "corporal", "presion",
            "arterial", "frecuencia", "cardiaca", "spo2", "primeros", "auxilios", "reposo",
            "literatura", "quijote", "cervantes", "garcia", "marquez", "soledad", "rae",
            "tilde", "agudas", "graves", "llanas", "esdrujulas", "acentuacion", "ortografia",
            "gramatica", "sujeto", "predicado", "verbo", "sustantivo", "adjetivo", "adverbio",
            "metafora", "simil", "hiperbole", "espanol", "economia", "pib", "inflacion", "derechos", "humanos", "onu",
            "nutricion", "proteina", "proteinas", "carbohidrato", "carbohidratos", "lipido",
            "lipidos", "kilocalorias", "gramo", "milla", "kilometro", "kilometros", "kilogramo",
            "libra", "libras", "pulgada", "centimetros", "litro", "litros", "agua", "web"
        )
    )

    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9_+.#\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun tokenize(text: String): List<String> {
        val norm = normalize(text)
        if (norm.isBlank()) return emptyList()
        val out = ArrayList<String>(16)
        for (raw in norm.split(' ')) {
            if (raw.length < 2 || raw in STOPWORDS) continue
            out.add(raw)
            // Descomponer identificadores compuestos (ej. llama_sampler_init_greedy, top_p)
            if ('_' in raw || '.' in raw) {
                for (part in raw.split('_', '.')) {
                    if (part.length >= 2 && part !in STOPWORDS && part != raw) {
                        out.add(part)
                    }
                }
            }
        }
        return out
    }

    fun classify(rawQuery: String): RagDomain {
        val norm = normalize(rawQuery)
        if (norm.isBlank()) return RagDomain.CHITCHAT
        val chitchatWithoutBrand = norm.removeSuffix(" aura").removeSuffix(" kofu").trim()
        if (chitchatWithoutBrand in CHITCHAT_EXACT ||
            (chitchatWithoutBrand.length <= 18 && CHITCHAT_EXACT.any {
                chitchatWithoutBrand == it || chitchatWithoutBrand.startsWith("$it ")
            })
        ) {
            val nonBrandTokens = tokenize(chitchatWithoutBrand).filter { it != "kofu" && it != "aura" }
            if (nonBrandTokens.size <= 2 && DOMAIN_TRIGGERS.values.flatten().none { it in nonBrandTokens }) {
                return RagDomain.CHITCHAT
            }
        }

        val tokens = tokenize(rawQuery)
        if (tokens.isEmpty()) return RagDomain.CHITCHAT

        var bestDomain = RagDomain.GENERAL_FACTS_ES
        var bestScore = 0

        for ((domain, triggers) in DOMAIN_TRIGGERS) {
            var score = 0
            for (t in tokens) {
                if (t in triggers) score += 2
            }
            for (trig in triggers) {
                if (' ' in trig && norm.contains(trig)) score += 3
            }
            if (score > bestScore) {
                bestScore = score
                bestDomain = domain
            }
        }

        return if (bestScore > 0) bestDomain else RagDomain.GENERAL_FACTS_ES
    }
}

/**
 * Motor RAG 100% offline respaldado por SQLite FTS4 + índice invertido BM25 en memoria.
 * Permite asignación dinámica de shards por dominio y enriquecimiento en caliente sin red.
 */
class KofuRagDatabase private constructor(
    context: Context?
) {
    private val dbHelper: RagSqliteHelper? = context?.let { RagSqliteHelper(it.applicationContext) }
    private val documents = mutableListOf<RagDocument>()
    private val invertedIndex = mutableMapOf<String, MutableMap<Int, Int>>() // term -> (docIdx -> tf)
    private val docLengths = mutableListOf<Int>()
    private var avgDocLength: Double = 1.0
    private var corpusVectorsDirect: ByteBuffer? = null
    private var corpusDocIndices: IntArray = IntArray(0)
    private var embedDim: Int = 0
    private val lock = Any()

    companion object {
        private const val TAG = "KofuRAG"
        private const val ASSET_PATH = "rag/kofu_knowledge_shards.json"
        private const val MAX_CONTEXT_CHARS = 480
        private const val META_DATASET_HASH = "dataset_hash"
        private const val RRF_K = 60.0
        private const val MIN_SEMANTIC_COSINE = 0.25f

        @Volatile
        private var instance: KofuRagDatabase? = null

        fun getInstance(context: Context): KofuRagDatabase {
            return instance ?: synchronized(this) {
                instance ?: KofuRagDatabase(context.applicationContext).also {
                    it.initializeFromAssetsAndDisk(context.applicationContext)
                    instance = it
                }
            }
        }

        fun getInstanceOrNull(): KofuRagDatabase? = instance

        fun createInMemoryForTest(jsonStream: InputStream): KofuRagDatabase {
            return KofuRagDatabase(null).apply {
                val jsonText = jsonStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val parsed = parseShardsJson(jsonText)
                rebuildInMemoryIndexLocked(parsed)
            }
        }

        fun computeDocContentHash(doc: RagDocument): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("${doc.title}|${doc.keywords}|${doc.content}".toByteArray(Charsets.UTF_8))
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun computeDatasetHash(docs: List<RagDocument>): String {
            val md = MessageDigest.getInstance("SHA-256")
            docs.sortedBy { it.id }.forEach { doc ->
                md.update("${doc.id}|${doc.domain.id}|${doc.title}|${doc.keywords}|${doc.content}\n".toByteArray(Charsets.UTF_8))
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun computeRrfFusion(
            bm25Ranked: List<Pair<RagDocument, Double>>,
            semanticRanked: List<Pair<RagDocument, Float>>,
            limit: Int,
            k: Double = RRF_K
        ): List<Pair<RagDocument, Double>> {
            val rrfScores = HashMap<String, Double>()
            val docsById = HashMap<String, RagDocument>()

            bm25Ranked.take(8).forEachIndexed { rank, (doc, score) ->
                if (score > 0.0) {
                    docsById[doc.id] = doc
                    rrfScores[doc.id] = (rrfScores[doc.id] ?: 0.0) + (1.0 / (k + rank + 1))
                }
            }
            semanticRanked.filter { it.second >= MIN_SEMANTIC_COSINE }.take(8).forEachIndexed { rank, (doc, _) ->
                docsById[doc.id] = doc
                rrfScores[doc.id] = (rrfScores[doc.id] ?: 0.0) + (1.0 / (k + rank + 1))
            }

            return rrfScores.entries
                .sortedByDescending { it.value }
                .take(limit)
                .mapNotNull { entry -> docsById[entry.key]?.let { it to entry.value } }
        }
    }

    fun getDocumentCount(): Int = synchronized(lock) { documents.size }

    fun getDomainCounts(): Map<RagDomain, Int> = synchronized(lock) {
        documents.groupingBy { it.domain }.eachCount()
    }

    private fun initializeFromAssetsAndDisk(appContext: Context) {
        val loaded = mutableListOf<RagDocument>()
        try {
            appContext.assets.open(ASSET_PATH).use { stream ->
                val jsonText = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                loaded.addAll(parseShardsJson(jsonText))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo shards RAG base desde assets", e)
        }

        // Enriquecimiento local adicional opcional desde almacenamiento externo privado (/files/rag/*.json)
        val externalRagDir = appContext.getExternalFilesDir("rag")
        if (externalRagDir != null && externalRagDir.exists() && externalRagDir.isDirectory) {
            externalRagDir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }
                ?.forEach { extraFile ->
                    runCatching {
                        val jsonText = extraFile.readText(Charsets.UTF_8)
                        loaded.addAll(parseShardsJson(jsonText))
                        Log.i(TAG, "Shard RAG externo cargado: ${extraFile.name}")
                    }.onFailure { err ->
                        Log.w(TAG, "No se pudo cargar shard externo ${extraFile.name}: ${err.message}")
                    }
                }
        }

        val datasetHash = computeDatasetHash(loaded)
        synchronized(lock) {
            val cachedHash = dbHelper?.getMeta(META_DATASET_HASH)
            if (cachedHash != datasetHash) {
                dbHelper?.replaceDocuments(loaded, datasetHash)
            } else {
                Log.i(TAG, "Hash RAG sin cambios ($datasetHash); se omite reescritura FTS4")
            }
            rebuildInMemoryIndexLocked(loaded)
        }
        Log.i(TAG, "Base RAG SQLite FTS4 + BM25 lista con ${loaded.size} fragmentos verificados")
    }

    fun onLowMemoryTrim() {
        synchronized(lock) {
            corpusVectorsDirect = null
            corpusDocIndices = IntArray(0)
            embedDim = 0
        }
        Log.i(TAG, "Caché vectorial DirectBuffer liberada por presión de memoria; respaldo BM25-only activo")
    }

    /**
     * Sincroniza embeddings int8 en SQLite y reconstruye el DirectByteBuffer contiguo
     * fuera del heap ART (zero-copy JNI) cuando el micro-embedder GGUF nativo está cargado.
     */
    fun syncSemanticEmbeddings() {
        if (!KofuNativeEngine.isNativeReady() || !KofuNativeEngine.nativeIsEmbedModelLoaded()) return
        val activeDim = KofuNativeEngine.nativeGetEmbedDim()
        if (activeDim <= 0) return

        val docsSnapshot = synchronized(lock) { documents.toList() }
        if (docsSnapshot.isEmpty()) return

        val helper = dbHelper ?: return
        val existing = helper.loadAllEmbeddings(activeDim)
        val toPersist = mutableListOf<EmbeddingRow>()
        val alignedVectors = ArrayList<Pair<Int, ByteArray>>(docsSnapshot.size)

        docsSnapshot.forEachIndexed { idx, doc ->
            val hash = computeDocContentHash(doc)
            val cached = existing[doc.id]
            if (cached != null && cached.contentHash == hash && cached.vector.size == activeDim) {
                alignedVectors.add(idx to cached.vector)
            } else {
                val vec = KofuNativeEngine.nativeEmbedTextInt8("${doc.title}. ${doc.content}")
                if (vec != null && vec.size == activeDim) {
                    toPersist.add(EmbeddingRow(doc.id, activeDim, vec, hash))
                    alignedVectors.add(idx to vec)
                }
            }
        }

        if (toPersist.isNotEmpty()) {
            helper.upsertEmbeddings(toPersist)
        }

        val totalBytes = alignedVectors.size * activeDim
        val directBuf = if (totalBytes > 0) ByteBuffer.allocateDirect(totalBytes) else null
        val docIndices = IntArray(alignedVectors.size)
        if (directBuf != null) {
            alignedVectors.forEachIndexed { pos, (docIdx, vec) ->
                directBuf.put(vec)
                docIndices[pos] = docIdx
            }
            directBuf.rewind()
        }

        synchronized(lock) {
            corpusVectorsDirect = directBuf
            corpusDocIndices = docIndices
            embedDim = activeDim
        }
        Log.i(TAG, "Embeddings semánticos int8 (DirectBuffer) listos: ${alignedVectors.size}/${docsSnapshot.size} docs (dim=$activeDim, ${totalBytes / 1024} KB)")
    }

    /**
     * Permite inyectar o actualizar dinámicamente documentos en tiempo de ejecución sin reiniciar.
     */
    fun upsertDynamicDocuments(newDocs: List<RagDocument>) {
        if (newDocs.isEmpty()) return
        synchronized(lock) {
            val mapById = documents.associateBy { it.id }.toMutableMap()
            newDocs.forEach { mapById[it.id] = it }
            val merged = mapById.values.toList()
            dbHelper?.replaceDocuments(merged, computeDatasetHash(merged))
            rebuildInMemoryIndexLocked(merged)
        }
        syncSemanticEmbeddings()
    }

    fun retrieveDynamicContext(
        rawQuery: String,
        maxChunks: Int = KofuEditionProfile.effectiveMaxRagChunks,
        maxContextChars: Int = KofuEditionProfile.effectiveMaxRagContextChars
    ): RagRetrievalResult {
        val startNs = System.nanoTime()
        val domain = DynamicDomainRouter.classify(rawQuery)

        if (domain == RagDomain.CHITCHAT) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            return RagRetrievalResult(
                domain = RagDomain.CHITCHAT,
                matchedChunks = emptyList(),
                formattedContext = "",
                lookupTimeMs = elapsedMs,
                estimatedTokens = 0,
                topScore = 0.0
            )
        }

        val queryTokens = DynamicDomainRouter.tokenize(rawQuery)
        if (queryTokens.isEmpty()) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            return RagRetrievalResult(
                domain = domain,
                matchedChunks = emptyList(),
                formattedContext = "",
                lookupTimeMs = elapsedMs,
                estimatedTokens = 0,
                topScore = 0.0
            )
        }

        val useHybrid = KofuNativeEngine.isNativeReady() &&
            KofuNativeEngine.nativeIsEmbedModelLoaded() &&
            synchronized(lock) { corpusVectorsDirect != null && corpusDocIndices.isNotEmpty() && embedDim > 0 }

        val bm25Limit = if (useHybrid) maxOf(maxChunks, 8) else maxChunks
        val bm25Ranked = synchronized(lock) {
            val domainResults = scoreCandidatesLocked(queryTokens, domainFilter = domain, limit = bm25Limit)
            if (domainResults.isNotEmpty() && domainResults.first().second >= 1.2) {
                domainResults
            } else {
                // Fallback transversal entre todos los shards si no hubo coincidencia fuerte en el primario
                scoreCandidatesLocked(queryTokens, domainFilter = null, limit = bm25Limit)
            }
        }

        val selected: List<RagDocument>
        val topScore: Double

        if (useHybrid) {
            val qVec = KofuNativeEngine.nativeEmbedTextInt8(rawQuery)
            val semanticRanked = if (qVec != null) {
                val (directBuf, docIndices, dim, docsSnapshot) = synchronized(lock) {
                    Quad(corpusVectorsDirect, corpusDocIndices, embedDim, documents.toList())
                }
                val numDocs = docIndices.size
                val cosScores = if (directBuf != null && numDocs > 0 && qVec.size == dim) {
                    KofuNativeEngine.nativeScoreInt8CosineDirect(qVec, directBuf, numDocs, dim)
                } else null
                if (cosScores != null) {
                    cosScores.indices
                        .map { pos -> docsSnapshot[docIndices[pos]] to cosScores[pos] }
                        .sortedByDescending { it.second }
                        .take(8)
                } else emptyList()
            } else emptyList()

            if (semanticRanked.isNotEmpty()) {
                val fused = computeRrfFusion(bm25Ranked, semanticRanked, limit = maxChunks)
                selected = fused.map { it.first }
                topScore = fused.firstOrNull()?.second ?: 0.0
            } else {
                selected = bm25Ranked.filter { it.second >= 1.0 }.take(maxChunks).map { it.first }
                topScore = bm25Ranked.firstOrNull()?.second ?: 0.0
            }
        } else {
            selected = bm25Ranked.filter { it.second >= 1.0 }.map { it.first }
            topScore = bm25Ranked.firstOrNull()?.second ?: 0.0
        }

        val formatted = buildCompactContext(selected, maxContextChars)
        val estTokens = if (formatted.isBlank()) 0 else (formatted.length / 4).coerceAtLeast(1)
        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)

        return RagRetrievalResult(
            domain = domain,
            matchedChunks = selected,
            formattedContext = formatted,
            lookupTimeMs = elapsedMs,
            estimatedTokens = estTokens,
            topScore = topScore
        )
    }

    private fun buildCompactContext(docs: List<RagDocument>, maxChars: Int = MAX_CONTEXT_CHARS): String {
        if (docs.isEmpty()) return ""
        val sb = StringBuilder()
        for (doc in docs) {
            val entry = "- ${doc.title}: ${doc.content}"
            if (sb.length + entry.length + 1 <= maxChars) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(entry)
            } else if (sb.isEmpty()) {
                sb.append(entry.take((maxChars - 3).coerceAtLeast(16))).append("...")
                break
            } else {
                break
            }
        }
        return sb.toString()
    }

    private fun scoreCandidatesLocked(
        queryTokens: List<String>,
        domainFilter: RagDomain?,
        limit: Int
    ): List<Pair<RagDocument, Double>> {
        if (documents.isEmpty()) return emptyList()
        val totalDocs = documents.size.toDouble()
        val k1 = 1.5
        val b = 0.75

        val candidateScores = HashMap<Int, Double>()
        val uniqueTokens = queryTokens.distinct()

        for (term in uniqueTokens) {
            val postings = invertedIndex[term] ?: continue
            val df = postings.size.toDouble()
            val idf = ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5))

            for ((docIdx, tf) in postings) {
                val doc = documents[docIdx]
                if (domainFilter != null && doc.domain != domainFilter) continue

                val dl = docLengths[docIdx].toDouble().coerceAtLeast(1.0)
                val tfNorm = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * (dl / avgDocLength)))
                candidateScores[docIdx] = (candidateScores[docIdx] ?: 0.0) + (idf * tfNorm)
            }
        }

        // Boost ligero por coincidencia directa en título/keywords y bigramas
        val bigrams = queryTokens.zipWithNext { a, bTok -> "$a $bTok" }
        val scored = candidateScores.entries.map { (idx, baseScore) ->
            val doc = documents[idx]
            val normTitle = DynamicDomainRouter.normalize(doc.title)
            val normKw = DynamicDomainRouter.normalize(doc.keywords)
            var boost = 0.0
            for (tok in uniqueTokens) {
                if (normTitle.contains(tok)) boost += 0.6
                if (normKw.contains(tok)) boost += 0.35
            }
            for (bg in bigrams) {
                if (normTitle.contains(bg) || normKw.contains(bg)) boost += 0.8
            }
            doc to (baseScore + boost)
        }

        return scored.sortedByDescending { it.second }.take(limit)
    }

    private fun rebuildInMemoryIndexLocked(docs: List<RagDocument>) {
        documents.clear()
        documents.addAll(docs)
        invertedIndex.clear()
        docLengths.clear()

        var totalLen = 0L
        docs.forEachIndexed { idx, doc ->
            val combined = "${doc.title} ${doc.keywords} ${doc.content}"
            val tokens = DynamicDomainRouter.tokenize(combined)
            docLengths.add(tokens.size)
            totalLen += tokens.size

            for (tok in tokens) {
                val postings = invertedIndex.getOrPut(tok) { HashMap() }
                postings[idx] = (postings[idx] ?: 0) + 1
            }
        }

        avgDocLength = if (docs.isNotEmpty()) (totalLen.toDouble() / docs.size).coerceAtLeast(1.0) else 1.0
    }

    private fun parseShardsJson(jsonText: String): List<RagDocument> {
        val result = mutableListOf<RagDocument>()
        val root = JSONObject(jsonText)
        val shardsObj = root.optJSONObject("shards") ?: return result

        val keys = shardsObj.keys()
        while (keys.hasNext()) {
            val domainKey = keys.next()
            val domain = runCatching { RagDomain.valueOf(domainKey) }.getOrNull() ?: RagDomain.GENERAL_FACTS_ES
            val arr: JSONArray = shardsObj.optJSONArray(domainKey) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id", "${domain.id}_$i")
                val title = item.optString("title", "").trim()
                val keywords = item.optString("keywords", "").trim()
                val content = item.optString("content", "").trim()
                if (title.isNotEmpty() && content.isNotEmpty()) {
                    result.add(RagDocument(id, domain, title, keywords, content))
                }
            }
        }
        return result
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private data class EmbeddingRow(
        val docId: String,
        val dim: Int,
        val vector: ByteArray,
        val contentHash: String
    )

    private class RagSqliteHelper(context: Context) :
        SQLiteOpenHelper(context, "kofu_rag_offline.db", null, 2) {

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            // Limitar caché de páginas SQLite a ~1 MB para proteger presupuesto <= 2 GB
            db.rawQuery("PRAGMA cache_size = -1024", null).use { }
            db.rawQuery("PRAGMA temp_store = FILE", null).use { }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS rag_documents_fts USING fts4(
                    doc_id,
                    domain,
                    title,
                    keywords,
                    content,
                    tokenize=unicode61
                );
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS rag_meta (
                    key TEXT PRIMARY KEY,
                    value TEXT
                );
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS rag_embeddings (
                    doc_id TEXT PRIMARY KEY,
                    dim INTEGER NOT NULL,
                    vector BLOB NOT NULL,
                    content_hash TEXT NOT NULL
                );
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS rag_embeddings")
            db.execSQL("DROP TABLE IF EXISTS rag_documents_fts")
            db.execSQL("DROP TABLE IF EXISTS rag_meta")
            onCreate(db)
        }

        fun getMeta(key: String): String? {
            return readableDatabase.rawQuery(
                "SELECT value FROM rag_meta WHERE key = ? LIMIT 1",
                arrayOf(key)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }

        fun loadAllEmbeddings(expectedDim: Int): Map<String, EmbeddingRow> {
            val out = HashMap<String, EmbeddingRow>()
            readableDatabase.rawQuery(
                "SELECT doc_id, dim, vector, content_hash FROM rag_embeddings WHERE dim = ?",
                arrayOf(expectedDim.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val dim = cursor.getInt(1)
                    val blob = cursor.getBlob(2)
                    val hash = cursor.getString(3)
                    out[docId] = EmbeddingRow(docId, dim, blob, hash)
                }
            }
            return out
        }

        fun upsertEmbeddings(rows: List<EmbeddingRow>) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (row in rows) {
                    val values = ContentValues().apply {
                        put("doc_id", row.docId)
                        put("dim", row.dim)
                        put("vector", row.vector)
                        put("content_hash", row.contentHash)
                    }
                    db.insertWithOnConflict("rag_embeddings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        fun replaceDocuments(docs: List<RagDocument>, datasetHash: String) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM rag_documents_fts")
                for (doc in docs) {
                    val values = ContentValues().apply {
                        put("doc_id", doc.id)
                        put("domain", doc.domain.id)
                        put("title", doc.title)
                        put("keywords", doc.keywords)
                        put("content", doc.content)
                    }
                    db.insert("rag_documents_fts", null, values)
                }
                val countValues = ContentValues().apply {
                    put("key", "doc_count")
                    put("value", docs.size.toString())
                }
                db.insertWithOnConflict("rag_meta", null, countValues, SQLiteDatabase.CONFLICT_REPLACE)
                val hashValues = ContentValues().apply {
                    put("key", META_DATASET_HASH)
                    put("value", datasetHash)
                }
                db.insertWithOnConflict("rag_meta", null, hashValues, SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
