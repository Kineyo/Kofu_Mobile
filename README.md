# Proyecto Kofu [Mobile] 🤖

<p align="center">
  <img src="assets/banner.jpeg" alt="Proyecto Kofu Mobile Banner" width="85%" style="border-radius: 14px; box-shadow: 0 10px 30px rgba(0,0,0,0.15);">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/Kotlin-1.9.22-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Jetpack%20Compose-BOM_2024-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/Inference-LiteRT%20%7C%20GGUF-FF6F00?style=for-the-badge&logo=google&logoColor=white" alt="LiteRT">
  <img src="https://img.shields.io/badge/Local%20LLM-Llama%203.2%201B-0081FB?style=for-the-badge&logo=meta&logoColor=white" alt="Llama 3.2">
  <img src="https://img.shields.io/badge/Privacy-100%25%20On--Device-10B981?style=for-the-badge&logo=shield&logoColor=white" alt="Privacy">
  <img src="https://img.shields.io/badge/Architecture-ARM64--v8a-0091BD?style=for-the-badge&logo=arm&logoColor=white" alt="ARM64">
  <img src="https://img.shields.io/badge/License-MIT-00f5d4?style=for-the-badge&logoColor=black" alt="MIT License">
</p>

---

## ⚡ ¿Qué es Kofu Mobile?

**Kofu Mobile** es un asistente virtual nativo para Android diseñado bajo una filosofía de **privacidad absoluta y soberanía digital**. Ejecuta modelos de lenguaje ligeros (SLMs como *Llama 3.2 1B* y *Gemma 2 2B*) directamente en el hardware de tu dispositivo, garantizando que tus conversaciones nunca salgan de tu teléfono ni dependan de servidores de terceros.

Todo el procesamiento ocurre localmente en la memoria RAM y GPU mediante tecnologías de aceleración nativa (*LiteRT / JNI*), sin emuladores pesados (sin PRoot/Termux) y con una interfaz moderna y reactiva construida en **Jetpack Compose**.

---

## ✨ Características Principales

* 🔒 **100% Privado y Offline:** Todo ocurre en tu dispositivo; cero telemetría externa y sin envío de datos a la nube.
* ⚡ **Inferencia Móvil Acelerada:** Diseñado para aprovechar la aceleración por hardware (GPU/NPU) mediante **LiteRT** y modelos compactos optimizados (cuantizaciones INT4 / GGUF).
* 📥 **Gestor y Descargador Integrado:** Descarga automática de modelos balanceados desde Hugging Face con indicador visual de progreso en tiempo real.
* 🛡️ **Validación Estricta de Modelos (Magic Numbers):** Al importar modelos externos vía SAF (botón `+`), Kofu verifica la firma binaria del archivo para certificar su integridad antes de cargarlo en RAM.
* 🧘 **Módulo de Contención Emocional:** Detección heurística de estrés que modula el tono del asistente activando pautas empáticas y técnicas de calma de forma transparente.
* 🧹 **Sanitización de Datos Privados:** Filtro automático que detecta y redacta información sensible (emails, tokens, teléfonos, identificadores personales) en caso de consultas asistidas.
* 🌓 **Tema Dinámico Adaptable:** Interfaz minimalista con soporte completo para transiciones fluidas entre Modo Claro y Modo Oscuro.

---

## 📱 Requisitos del Sistema

| Componente | Requisito Mínimo | Recomendado |
| :--- | :--- | :--- |
| **Sistema Operativo** | Android 8.0 (Oreo, API 26) | Android 12+ (API 31+) |
| **Arquitectura CPU** | ARM64-v8a (64 bits) | ARM64 con GPU Adreno / Mali moderna |
| **Memoria RAM** | 6 GB física | 8 GB física (+ RAM Plus / Virtual RAM) |
| **Almacenamiento** | 2 GB libres | 4 GB libres (para múltiples modelos) |
| **Conexión a Red** | Solo requerida para la descarga inicial del modelo | 100% Offline tras la descarga |

---

## 🚀 Instalación y Uso Rápido

1. **Instala el APK:** Descarga el instalador `.apk` de la versión correspondiente e instálalo en tu dispositivo.
2. **Inicia Kofu:** Al abrir la aplicación, comenzará automáticamente la preparación del modelo base ligero optimizado.
3. **Carga Personalizada (Opcional):** Si prefieres un modelo específico (`.litertlm` o `.gguf`), pulsa el botón **`+`** en la barra superior y selecciónalo desde tu almacenamiento local.
4. **Comienza a interactuar:** Chatea con total privacidad y fluidez.

---

## 🛠️ Compilación para Desarrolladores

Si deseas compilar la aplicación desde el código fuente:

1. Clona este repositorio:
   ```bash
   git clone https://github.com/Kineyo/Kofu_Mobile.git
   cd Kofu_Mobile/Kofu_Assistant
   ```
2. Abre el proyecto en **Android Studio** (Koala o superior recomendado).
3. Sincroniza las dependencias con Gradle (`JDK 17`).
4. Compila y despliega en tu dispositivo físico o emulador con soporte ARM64:
   ```bash
   ./gradlew assembleDebug
   ```

---

## 📄 Licencia

Este proyecto está distribuido bajo la licencia de código abierto **MIT**. Consulta el archivo `LICENSE` para más detalles.
