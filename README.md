# Aura [Rama `kofu-go`] 🧠

<p align="center">
  <img src="assets/images/aura_logo.png" alt="Aura Logo" width="180" style="border-radius: 28px; box-shadow: 0 10px 30px rgba(0,0,0,0.15);">
</p>

<p align="center">
  <a href="https://huggingface.co/shebxyd/aura-1b-edge"><img src="https://img.shields.io/badge/🤗%20Aura%201B%20(Gemma%203)-Q4__K__M%20(~769%20MiB)-yellow?style=for-the-badge" alt="Aura 1B Edge Q4_K_M"></a>
  <img src="https://img.shields.io/badge/Branch-kofu--go%20%5B%3C%3D2GB%20Guard%5D-brightgreen?style=for-the-badge" alt="kofu-go Branch">
  <img src="https://img.shields.io/badge/Backend-C%2B%2B20%20NDK%20r26-00599C?style=for-the-badge&logo=cplusplus&logoColor=white" alt="C++20 NDK">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/Android-5.0%2B%20(API%2021%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 5.0+">
  <img src="https://img.shields.io/badge/Inference-GGUF%20%7C%20llama.cpp-FF6F00?style=for-the-badge&logo=cplusplus&logoColor=white" alt="Inference">
  <img src="https://img.shields.io/badge/Privacy-100%25%20On--Device-10B981?style=for-the-badge&logo=shield&logoColor=white" alt="Privacy">
  <img src="https://img.shields.io/badge/Architecture-ARM64--v8a-0091BD?style=for-the-badge&logo=arm&logoColor=white" alt="ARM64">
  <img src="https://img.shields.io/badge/License-MIT-00f5d4?style=for-the-badge&logoColor=black" alt="MIT License">
</p>

---

## ⚡ ¿Qué es Aura (`kofu-go`)?

**Aura** (desarrollada en la rama Git **`kofu-go`** del proyecto base) es la edición educativa y optimizada diseñada para ejecutarse **100% offline en dispositivos Android de gama de entrada y gama media (3–6 GB de RAM)** manteniendo el presupuesto combinado de **modelo 4-bit + caché KV + bases de datos RAG + runtime Android** estrictamente por debajo de **2 GB** (`LMK Guard` con techo de `VmRSS <= 1850 MB`) para evitar terminaciones por el **Android Low Memory Killer (`lmkd`)**.

A diferencia de clientes conectados a la nube o soluciones con entornos Linux complejos (Termux/PRoot), Aura ejecuta **Google Gemma 3 1B IT (`Q4_K_M`)** directamente sobre `llama.cpp` en C++20/ARM64 con prioridad **GPU → NPU → CPU** junto con un motor RAG híbrido ligero (`SQLite FTS4 + BM25 + embeddings int8`) con identidad de **Aura** y base de conocimientos escolares (programación, matemáticas, ortografía y gramática del español, ciencias naturales e historia), incluso en **Modo Avión**.

---

## 🏛️ Arquitectura Optimizada <= 2 GB: C++20 + Kotlin Compose

```text
┌──────────────────────────────────────────────────────────────────────┐
│               CAPA VISUAL Y REACTIVA (Kotlin / Compose)              │
│  - Jetpack Compose UI con throttling ~80 ms y cacheo de bloques ```  │
│  - Animación de aparición suave: entrada de burbuja + fade de tokens │
│  - Detección automática de RAM total (MemTotal <= 6 GB -> <=2GB)     │
│  - Descarga principal (Foreground Service) + micro-embedder detrás   │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ JNI (Zero-Copy DirectByteBuffer)
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│               MOTOR NATIVO C++20 (libkofu_engine.so)                 │
│  - Zero-Copy mmap real (use_extra_bufts=false bajo <= 2 GB)          │
│  - KV Cache cuantizada Q8_0/Q8_0 (swa_full=true para Gemma ISWA)     │
│  - Topología adaptativa para SoCs 2+6 / 4+4 y prefijo KV multi-turno │
│  - LMK Guard cada 8 tokens (VmRSS <= 1850 MB, RssAnon, MemAvailable) │
│  - Micro-embedder GGUF + producto punto int8 acelerado por NEON      │
└──────────────────────────────────────────────────────────────────────┘
```

### Pilares Implementados en `kofu-go`

1. **Zero-Copy `mmap` Real y Caché KV Cuantizada (`Q8_0`):**
   - En dispositivos bajo presupuesto `<= 2 GB` (`NODE_7NM_LEGACY` o `MemTotal <= 6144 MB` dentro de esta rama), [`kofu_engine.cpp`](Kofu_Assistant/app/src/main/cpp/src/kofu_engine.cpp) desactiva el weight repacking en memoria anónima (`mparams.use_extra_bufts = false`) para que los pesos `Q4_K_M` (~806 MB) permanezcan en páginas `mmap` limpias y evictables sin inflar `RssAnon`.
   - La caché KV se configura en **`Q8_0/Q8_0`** (con respaldo automático `Q8_0/F16`) y `n_ctx` adaptativo (`1536 / 1024 / 512`), preservando `swa_full = true` en arquitecturas Gemma/ISWA para mantener *Context Shifting* y reutilización de prefijo KV.
2. **Cascada GPU → NPU → CPU y Gobernador Debug 70–80 % RAM:**
   - El motor nativo prioriza descarga parcial/total a **GPU** (OpenCL/Vulkan si el SoC es compatible) y cae automáticamente a **NPU/CPU** con doble pool de hilos (`batch_thr` / `decode_thr`) y precalentamiento del prefijo del sistema en caché KV.
   - En compilaciones **`debug`** (`BuildConfig.DEBUG`), el gobernador de RAM monitorea `VmRSS` respecto al límite: al superar el **70 %** introduce frenado suave (`25 ms`), al superar el **80 %** frenado fuerte (`75 ms`) y en el techo **100 %** detiene la generación antes de un *kill* del sistema. En **`release`** solo actúa el guarda crítico final.
   - Verificación *preflight* antes del `prefill` y vigilancia cada **8 tokens** en generación sobre `MemAvailable`, `RssAnon` y `VmRSS` (techo `1850 MB`).
   - En [`MainActivity.kt`](Kofu_Assistant/app/src/main/java/com/kofu/assistant/MainActivity.kt), `onTrimMemory()` libera el micro-embedder y la caché vectorial en niveles moderados/bajos sin interrumpir la respuesta en curso, reservando la cancelación para niveles críticos.
3. **RAG Híbrido Ligero + Micro-Embedder Secundario:**
   - [`KofuRagDatabase.kt`](Kofu_Assistant/app/src/main/java/com/kofu/assistant/data/rag/KofuRagDatabase.kt) combina **SQLite FTS4 + BM25** (omitiendo la reescritura al arrancar cuando coincide el hash en `rag_meta`, con caché SQLite acotada a ~1 MB) y vectores `int8` en **`DirectByteBuffer`** fuera del heap de ART, evaluados desde C++ NEON mediante `nativeScoreInt8CosineDirect` y fusionados con **RRF** (*Reciprocal Rank Fusion*).
   - [`LlamaRepository.kt`](Kofu_Assistant/app/src/main/java/com/kofu/assistant/repository/LlamaRepository.kt) descarga el micro-embedder `all-MiniLM-L6-v2-Q8_0.gguf` (~24 MB) automáticamente en segundo plano **detrás** de que el modelo principal esté listo, operando con respaldo transparente `BM25-only` si no hay red o si se libera por memoria.
   - [`HallucinationGuard.kt`](Kofu_Assistant/app/src/main/java/com/kofu/assistant/data/rag/HallucinationGuard.kt) acota temperatura (`<= 0.35f` con RAG) y anexa `[CONTEXTO VERIFICADO]` al final del turno para maximizar la reutilización del prefijo KV.
4. **UI Fluida con Animación de Aparición:**
   - [`ChatScreen.kt`](Kofu_Assistant/app/src/main/java/com/kofu/assistant/ui/ChatScreen.kt) aplica throttling de UI (~80 ms), cachea bloques de código ```` ``` ```` ya cerrados para evitar re-evaluar `Regex` en cada token, anima la entrada de nuevas burbujas en GPU (`graphicsLayer`) y usa `StreamingAppearanceText` para conservar sólidas las palabras previas mientras desvanece suavemente (`110 ms`) solo el fragmento recién llegado.

---

## 🤖 Catálogo de Modelos 4-bit en Aura (`kofu-go`)

| ID | Modelo | Cuantización | Tamaño | Uso en `kofu-go` |
| :--- | :--- | :--- | :--- | :--- |
| **`kofu-go-1b`** *(Predeterminado)* | [`shebxyd/aura-1b-edge`](https://huggingface.co/shebxyd/aura-1b-edge) *(base Google Gemma 3 1B IT)* | `Q4_K_M` (4-bit) | **~769 MiB (`806 MB`)** | **Modelo principal Edge** entrenado para Aura (identidad + escolar) bajo umbral <= 2 GB |
| **`kofu-embed-minilm`** *(Secundario)* | [`second-state/All-MiniLM-L6-v2-Embedding-GGUF`](https://huggingface.co/second-state/All-MiniLM-L6-v2-Embedding-GGUF) | `Q8_0` | **~24 MB** | Micro-embedder descargado detrás del modelo principal para RAG semántico `int8` |
| **`kofu-v2`** | [`shebxyd/kofu-v2-edge`](https://huggingface.co/shebxyd/kofu-v2-edge) | `Q4_K_M` (4-bit) | ~1.6 GB | Opcional para dispositivos de 6–8 GB+ RAM |
| **`kofu-2b`** | [`shebxyd/kofu-2b-edge`](https://huggingface.co/shebxyd/kofu-2b-edge) | `Q4_K_M` (4-bit) | ~1.4 GB | Alternativa Gemma 1 2B |
| **`kofu-gemma4`** | [`shebxyd/kofu-gemma4-edge`](https://huggingface.co/shebxyd/kofu-gemma4-edge) | `Q4_K_M` (4-bit) | ~5.3 GB | Modo Pro Flagship (12 GB+ RAM) |

Además, puedes importar cualquier archivo `.gguf`, `.litertlm`, `.tflite`, `.task` o `.bin` local mediante el botón de selector de modelos o vía consola ADB (`KofuConsoleReceiver`).

---

## 📱 Ediciones (`productFlavors`) y Presupuesto de Recursos

| Edición (`productFlavor`) | Rango Android | Nodo e ISA ARMv8 | Configuración en rama `kofu-go` |
| :--- | :--- | :--- | :--- |
| **`node7nm`** *(Objetivo principal Aura / `kofu-go`)* | Android 5.0+ (API 21+) | `armv8-a` universal (sin DotProd/i8mm) | `Aura 1B (Gemma 3 Q4_K_M)`, `n_ctx <= 1536` (`Q8_0`), `n_batch <= 256`, `1` chunk RAG (`<=280 chars`), `VmRSS <= 1850 MB` |
| **`node5nm`** *(Mainstream)* | Android 8.0+ (API 26+) | `armv8.2-a+dotprod+fp16` | `Gemma 3 1B Q4_K_M` por defecto en `kofu-go`, presupuesto `<= 2 GB` activo en `MemTotal <= 6 GB`, hasta `640 tok` |
| **`frontierSub5nm`** *(Frontera)* | Android 14+ (API 34+) | `armv8.6-a+dotprod+fp16+i8mm` | `Kofu 4.0 Pro`, `n_ctx` hasta `4096`, `n_batch=1024`, hasta `6` hilos, máx `2048 tok` |

En esta rama, [`release.yml`](.github/workflows/release.yml) compila manualmente (`workflow_dispatch`) los APKs `node7nm` (`release` con R8/shrinkResources y `debug`) tras ejecutar `testNode7nmDebugUnitTest`.

---

## 🗂️ Estructura del Proyecto

```text
Kofu_Mobile/
├── .github/workflows/release.yml            # CI manual (workflow_dispatch) para Aura (rama kofu-go) node7nm
├── docs/designs/kofu-go.md                  # Especificación técnica de Kofu Go
├── Kofu_Assistant/app/
│   ├── build.gradle.kts                     # Flavors node7nm / node5nm / frontierSub5nm, NDK r26, R8 release
│   ├── proguard-rules.pro                   # Reglas -keep para JNI, callbacks y JSON
│   └── src/
│       ├── main/
│       │   ├── assets/rag/kofu_knowledge_shards.json
│       │   ├── cpp/                         # Motor nativo C++20 + llama.cpp embebido
│       │   │   ├── CMakeLists.txt
│       │   │   ├── include/{kofu_engine.hpp,model_loader.hpp,prompt_formatter.hpp,kofu_api21_compat.h}
│       │   │   └── src/{kofu_engine.cpp,kofu_jni.cpp,model_loader.cpp,prompt_formatter.cpp}
│       │   └── java/com/kofu/assistant/
│       │       ├── MainActivity.kt          # Detección de RAM total + onTrimMemory escalonado
│       │       ├── data/
│       │       │   ├── KofuEditionProfile.kt
│       │       │   ├── ChatCacheManager.kt  # Persistencia atómica (kofu_chats_cache.json)
│       │       │   ├── AppUpdateManager.kt
│       │       │   └── rag/{KofuRagDatabase.kt,HallucinationGuard.kt,KofuBenchmark500Suite.kt}
│       │       ├── repository/{KofuNativeEngine.kt,LlamaRepository.kt,OllamaClient.kt}
│       │       ├── service/{ModelDownloadService.kt,KofuConsoleReceiver.kt}
│       │       └── ui/{ChatScreen.kt,components/*,theme/KofuTheme.kt}
│       └── test/java/com/kofu/assistant/
│           ├── KofuLogicTest.kt
│           └── KofuRagAnd500StressTest.kt
├── LICENSE
└── README.md
```

---

## 📱 Requisitos del Dispositivo (`kofu-go`)

| Requisito | Mínimo (Gama Entrada) | Recomendado (Gama Media) |
| :--- | :--- | :--- |
| **Sistema Operativo** | Android 5.0 (API 21, sabor `node7nm`) | Android 8.0+ (API 26+) |
| **Arquitectura de CPU** | ARM64 (`arm64-v8a`) | ARM64 con extensiones DotProd / FP16 |
| **Memoria RAM Física** | **3–4 GB** *(respetando techo <= 2 GB)* | **4–6 GB o más** |
| **Almacenamiento Libre** | 1.5 GB libres (`~806 MB` modelo + `~24 MB` embedder) | 2.5 GB libres |
| **Conexión de Red** | Solo para descarga inicial de pesos | Completamente innecesaria para operar |

---

## 🛠️ Compilación, Pruebas y Benchmark ADB

### Requisitos Previos
* **Android SDK:** API Level 34
* **Android NDK:** `26.1.10909125`
* **CMake:** `3.22.1`
* **JDK:** Java 17

### Comandos Principales

1. **Ejecutar pruebas unitarias y suite de estrés RAG de 500 preguntas:**
   ```bash
   cd Kofu_Assistant
   ./gradlew testNode7nmDebugUnitTest testNode5nmDebugUnitTest
   ```
2. **Compilar APK de Aura (`node7nm`, rama `kofu-go`):**
   ```bash
   ./gradlew assembleNode7nmDebug
   # O versión release optimizada con R8:
   ./gradlew assembleNode7nmRelease
   ```
3. **Ubicación del APK generado:**
   ```text
   Kofu_Assistant/app/build/outputs/apk/node7nm/debug/app-node7nm-debug.apk
   Kofu_Assistant/app/build/outputs/apk/node7nm/release/app-node7nm-release.apk
   ```
4. **Benchmark en dispositivo vía ADB (`KofuConsoleReceiver`):**
   ```bash
   adb shell 'am broadcast -n com.kofu.assistant/.service.KofuConsoleReceiver -a com.kofu.assistant.ACTION_CONSOLE_STATUS'
   adb shell 'am broadcast -n com.kofu.assistant/.service.KofuConsoleReceiver -a com.kofu.assistant.ACTION_CONSOLE_STRESS_500 --ei count 500 --ez full_llm true'
   ```

---

## 📄 Licencia

Este proyecto está distribuido bajo la licencia **MIT**. Consulta el archivo [`LICENSE`](LICENSE) para más detalles.
Los pesos de Google Gemma se rigen por los [Términos de Uso de Google Gemma](https://ai.google.dev/gemma/terms).
