---
license: gemma
base_model: google/gemma-2b-it
language:
- es
- en
library_name: transformers
tags:
- gemma
- kofu
- edge
- android
- gguf
- litert
- cplusplus
- on-device
- conversational
- llama.cpp
pipeline_tag: text-generation
inference: false
model_creator: shebxyd
model_type: gemma
model_name: Kofu Gemma 2B Edge
---

<div align="center">
  <img src="banner.jpg" alt="Kofu Gemma 2B Edge Banner" width="100%" style="border-radius: 12px; margin-bottom: 20px;">

  # Kofu Gemma 2B Edge (IA Local • Sin Internet)

  [![License: Gemma](https://img.shields.io/badge/License-Gemma_Terms-blue.svg)](https://ai.google.dev/gemma/terms)
  [![Base Model: Gemma 2B](https://img.shields.io/badge/Base_Model-google%2Fgemma--2b--it-purple.svg)](https://huggingface.co/google/gemma-2b-it)
  [![Target: Android ARM64](https://img.shields.io/badge/Target-Android_ARM64-3DDC84.svg?logo=android&logoColor=white)](https://github.com/Kineyo/Kofu_Mobile)
  [![Engine: C++ Native NDK](https://img.shields.io/badge/Engine-C%2B%2B_Native_NDK-00599C.svg?logo=c%2B%2B&logoColor=white)](https://github.com/Kineyo/Kofu_Mobile)
</div>

---

## 📌 Descripción del Modelo

**`kofu-gemma-2b-edge`** es un modelo conversacional e instruccional ligero diseñado y optimizado específicamente para su ejecución local **100% on-device** (sin conexión a Internet) en teléfonos móviles y dispositivos embebidos con arquitectura **ARM64**.

Forma parte del ecosistema oficial de [**Kofu Mobile Assistant**](https://github.com/Kineyo/Kofu_Mobile), donde interactúa directamente con un backend nativo en **C++20** con mapeo de memoria zero-copy (`mmap`).

### 🎯 Especialización y Capacidades
El modelo fue entrenado a partir de `google/gemma-2b-it` integrando más de **5,000 ejemplos de alta calidad** enfocados en:
1. **Lógica de Programación y Algoritmos:** C++, Java, Python, Web y resolución paso a paso.
2. **Ciberseguridad & Redes:** Análisis de vulnerabilidades, buenas prácticas defensivas y principios éticos.
3. **Razonamiento Multidisciplinario en Español:** Matemáticas, ciencias, historia universal y ética.
4. **Respuestas Concisas y Amables:** Ideal para interfaces móviles con baja latencia y consumo ultra-reducido de batería.

---

## 📦 Archivos Disponibles en este Repositorio

| Archivo | Formato | Tamaño | Descripción y Uso Recomendado |
| :--- | :---: | :---: | :--- |
| `kofu-gemma-2b-edge-q4_k_m.gguf` | **GGUF** (Q4_K_M) | **~1.4 GB** | **(Recomendado para Móvil)** Ejecución directa en teléfonos (RAM < 2.5 GB). Formato predeterminado de Kofu Mobile. |
| `model.safetensors` | **Safetensors** (BF16) | **~5.0 GB** | Pesos completos fusionados (Merged Base + LoRA) para servidores, fine-tuning adicional o conversión. |
| `tokenizer.json` / `tokenizer_config.json` | Tokenizer | ~17 MB | Tokenizer nativo de Gemma con vocabulario completo de 256k tokens. |
| `config.json` | Config | < 1 KB | Configuración de arquitectura Gemma CausalLM. |

---

## ⚡ Formato del Prompt (Gemma Chat Format)

El modelo utiliza el formato oficial de turnos de Gemma:

```text
<start_of_turn>user
Hola Kofu, ¿puedes explicarme cómo funciona un apuntador en C++?<end_of_turn>
<start_of_turn>model
¡Hola! Con gusto te explico: un apuntador en C++ es una variable que almacena la dirección de memoria de otra variable...<end_of_turn>
```

---

## 🚀 Cómo Usar este Modelo

### 1. En la Aplicación Kofu Mobile (Android)
El modelo está preconfigurado en la aplicación oficial [Kofu Mobile](https://github.com/Kineyo/Kofu_Mobile).
1. Abre la app en tu teléfono Android.
2. Al iniciar o tocar el botón **"+"**, el motor nativo en C++ descargará e instalará automáticamente `kofu-gemma-2b-edge-q4_k_m.gguf` directamente desde este repositorio.
3. ¡Listo! Puedes conversar con Kofu de forma 100% privada y offline.

### 2. Con `llama.cpp` (Terminal / Local)
```bash
# Ejecutar inferencia interactiva en CPU o GPU:
./llama-cli -m kofu-gemma-2b-edge-q4_k_m.gguf \
    -p "<start_of_turn>user\nExplica qué es un puntero en C++<end_of_turn>\n<start_of_turn>model\n" \
    -n 256 --color -c 2048
```

### 3. Con Python (`transformers`)
```python
import torch
from transformers import AutoTokenizer, AutoModelForCausalLM

model_id = "shebxyd/kofu-gemma-2b-edge"

tokenizer = AutoTokenizer.from_pretrained(model_id)
model = AutoModelForCausalLM.from_pretrained(
    model_id,
    torch_dtype=torch.bfloat16,
    device_map="auto"
)

prompt = "<start_of_turn>user\n¿Cuál es la diferencia entre stack y heap?<end_of_turn>\n<start_of_turn>model\n"
inputs = tokenizer(prompt, return_tensors="pt").to(model.device)

outputs = model.generate(**inputs, max_new_tokens=200, temperature=0.7)
print(tokenizer.decode(outputs[0], skip_special_tokens=False))
```

---

## 📊 Detalles del Entrenamiento

- **Base Model:** `google/gemma-2b-it`
- **Método:** LoRA (Low-Rank Adaptation)
  - Rank ($r$): `16`
  - Alpha ($\alpha$): `32`
  - Target Modules: `q_proj`, `k_proj`, `v_proj`, `o_proj`
  - Dropout: `0.05`
- **Hardware de Entrenamiento:** GPU NVIDIA Tesla T4 (Google Colab)
- **Batch Size Efectivo:** `16` (batch per device: 1, gradient accumulation: 16)
- **Optimizador:** `AdamW` (learning rate: `2e-4`, linear schedule)
- **Evolución del Entrenamiento:** Loss inicial `3.11` $\rightarrow$ Loss final `1.40`
- **Datasets:**
  - `plncmm/spanish-alpaca` (Conocimiento general, matemáticas, historia, valores)
  - `Yahir/alpaca-logica-programacion` (Lógica y programación)
  - `sahil2801/CodeAlpaca-20k` (Desarrollo C++, Java, HTML, Web)
  - `murdok1982/gemma4-programacion-seguridad` (Ciberseguridad y análisis de código)

---

## 📜 Licencia

Este modelo se distribuye bajo los términos de la **[Licencia Gemma de Google (Gemma Terms of Use)](https://ai.google.dev/gemma/terms)** de acuerdo con las condiciones de uso de los modelos base de Google DeepMind.
