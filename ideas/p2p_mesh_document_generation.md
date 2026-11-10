# 📱 Kofu Mesh: Generación Distribuida P2P de Documentos en Teléfonos Heterogéneos

---

## 🧭 1. Resumen Ejecutivo y Visión General

**Kofu Mesh** es una extensión arquitectónica para Kofu Mobile que transforma teléfonos móviles cercanos en una **red de cómputo en malla (P2P Mesh) completamente fuera de línea (Off-Grid)**. Permite redactar documentos extensos (informes técnicos, protocolos de emergencia, manuales, guías y libros blancos) repartiendo el trabajo de redacción entre múltiples dispositivos mediante conexiones locales (Bluetooth Classic RFCOMM / BLE y Wi-Fi Direct), **sin tocar internet, sin servidores en la nube y con privacidad criptográfica total**.

### ⚖️ El Dilema Técnico Fundamental: ¿Por qué NO dividir tensores?

Existen dos formas teóricas de distribuir IA en múltiples dispositivos:

```
[ENFOQUE 1: Paralelismo de Tensores / Capas (Petals / ggml-rpc)]
  Nodo A (Capa 1-8) === [4 KB por token / 15 tok/s] ===> Nodo B (Capa 9-16)
  ❌ INVIABLE POR BLUETOOTH:
     - Bluetooth ofrece ~60 a 140 KB/s con latencias de 40-80 ms por paquete.
     - Generar 1 palabra requiere decenas de intercambios sincrónicos de red.
     - Resultado: velocidad < 0.1 tokens/segundo y colapso de la batería.

[ENFOQUE 2: Descomposición de Tareas en DAG (Map-Reduce de Contenido)]
  Nodo Coordinador: Divide el documento en Secciones 1, 2, 3...
  Nodo Coordinador === [Envía Prompt de 2 KB] ===> Nodo B (Inferencia local)
  Nodo B === [Genera 500 tokens en 30s] ===> Envía texto (4 KB) de vuelta
  ✅ ULTRA-EFICIENTE Y VIABLE:
     - El 99.8% del tiempo es cómputo autónomo local en cada teléfono.
     - La transmisión por red dura milisegundos (< 0.2% del tiempo total).
     - Con 3 nodos trabajando en paralelo, un documento de 10 páginas se genera
       en un tercio del tiempo.
```

---

## 🧩 2. Manejo de Nodos Heterogéneos y Teléfonos de Gama Baja

En un entorno real, no todos los dispositivos son buques insignia con 12 GB de RAM. Kofu Mesh clasifica dinámicamente cada dispositivo y le asigna un rol según su hardware real.

### 📊 Perfiles de Hardware y Asignación de Roles

```
+-------------------+--------------------+--------------------+--------------------+
| Categoría         | Perfil de Hardware | Rol Principal      | Tareas Asignadas   |
+-------------------+--------------------+--------------------+--------------------+
| Gama Entrada      | 4 GB RAM           | REPETIDOR (RELAY)  | - Enrutamiento     |
| (CPUs antiguas)   | CPU 4/8 núcleos    | & NODO AUXILIAR    | - Chunking texto   |
|                   | < 1.5 GB libre     |                    | - Compresión zstd  |
|                   |                    |                    | - Hash / Cifrado   |
+-------------------+--------------------+--------------------+--------------------+
| Gama Media        | 6 - 8 GB RAM       | TRABAJADOR 1B-2B   | - Inferencia Gemma |
| (SoC estándar)    | 2.5 - 4 GB libres  | (Worker)           |   1.0 / 2.0 2B     |
|                   |                    |                    | - Relay de apoyo   |
+-------------------+--------------------+--------------------+--------------------+
| Gama Alta / Pro   | 12 - 16 GB RAM     | ORQUESTADOR (DAG)  | - Inferencia Pro   |
| (NPU/GPU potente) | > 5 GB libres      | & TRABAJADOR TOP   | - Plan Maestro     |
|                   |                    |                    | - Ensamblado final |
+-------------------+--------------------+--------------------+--------------------+
```

### 📡 Protocolo de Subasta Inversa (Reverse Auction / Call For Bids)

Cuando un usuario presiona **"Generar Documento en Malla"**:

```
[Teléfono Solicitante]                 [Nodos Cercanos B, C, D]
         |                                       |
         |---- 1. CALL FOR BIDS (CFB) ---------->| (Broadcast BLE/UDP, 48 bytes)
         |     [ID Tarea, Longitud estimada]     |
         |                                       |
         |<--- 2. BID PROPOSAL (Oferta) ---------| (Unicast con Telemetría)
         |     - Nodo B (4GB): Score 30 (Relay)  |
         |     - Nodo C (8GB): Score 85 (Worker) |
         |     - Nodo D (12GB): Score 95 (Worker)|
         |                                       |
         |---- 3. ADJUDICACIÓN DE TAREAS ------->|
         |     - Sección 1 -> Nodo C             |
         |     - Sección 2 -> Nodo D             |
         |     - Ruta a Nodo D vía Nodo B (Relay)|
```

#### Ecuación del Puntaje de Idoneidad (Fitness Score):
$$\text{Score} = (w_1 \cdot \text{RAM}_{\text{libre}}) + (w_2 \cdot \text{Batería}_{\%}) + (w_3 \cdot \text{Velocidad}_{\text{tps}}) + \text{Bonus}_{\text{mmap}} - \text{Penalización}_{\text{Térmica}}$$

* **Filtro de Seguridad 4 GB:** Si la RAM libre es inferior a 2.2 GB, el nodo **rechaza automáticamente ofertas de inferencia pesada** para evitar que Android invoque el Low Memory Killer (OOM Killer).
* **Conversión Automática a Repetidor:** Si un dispositivo no califica como trabajador de inferencia, se registra en la tabla como **Nodo Repetidor Puro**, dedicando su radio y CPU mínima a transportar paquetes.
* **Protección de Batería:** Si la batería es inferior al 20% y el dispositivo no está enchufado, se inhabilita para inferencia pesada y sólo participa como cliente o relay pasivo.

### 🔁 Rol del Nodo Repetidor (Multi-Hop Relay)

El mayor límite de Bluetooth es su radio de ~10 a 15 metros. Los teléfonos de 4 GB o con CPUs antiguas resuelven este problema actuando como repetidores:

```
[Teléfono A (Cliente)] <--- 10m BT ---> [Teléfono B (Repetidor 4GB)] <--- 12m BT ---> [Teléfono C (Worker 12GB)]
  Solicita Documento                     Reenvía paquetes sin descifrar                Ejecuta Gemma 2B
```

* **Enrutamiento Reactivo Ligero:** Inspirado en AODV simplificado en espacio de usuario.
* **Cabecera de Paquete (16 bytes):**
  `[SrcID: 64b | DstID: 64b | PacketSeq: 32b | TTL: 8b | HopCount: 8b | PayloadLen: 16b]`
* **Prevención de Bucles y Deduplicación:** Cada repetidor mantiene una tabla circular en RAM (`std::unordered_map`) con las últimas tuplas `(SrcID, PacketSeq)`. Si ya vio el paquete o si `TTL == 0`, lo descarta inmediatamente.
* **Cifrado E2E Inviolable:** La carga útil del documento viaja cifrada de extremo a extremo con ChaCha20-Poly1305. El nodo repetidor **solo lee la cabecera de 16 bytes para saber a quién enviarlo; jamás tiene acceso al texto ni a las conversaciones**.

---

## ⚙️ 3. Arquitectura de Software: C++/C/Rust vs Kotlin

Para lograr **latencia ultrabaja**, **cero tirones por Garbage Collection (GC)** y **máxima duración de batería**, se aplica una estricta separación de responsabilidades:

```
=============================================================================
CAPA VISUAL / ESTÉTICA: JETPACK COMPOSE (Kotlin)
  - Radar de Nodos Mesh en pantalla (círculos concéntricos y estado de nodos)
  - Previsualización en tiempo real del documento ensamblándose
  - Interruptor: "Contribuir con mi teléfono a la malla [Sí/No]"
================================== JNI BRIDGE ===============================
  * Solo 4 métodos primitivos JNI:
      nativeStartMesh(), nativeStopMesh(), nativeDispatchDoc(), nativeGetMeshState()
  * Retorno de eventos hacia Kotlin mediante Kotlin Flow reactivo sin pausas GC
=============================================================================
MOTOR NATIVO DE MALLA Y CÓMPUTO: C++20 / C / RUST (`libkofu_engine.so`)
  +-------------------------------------------------------------------------+
  | RED Y ENRUTAMIENTO NATIVO (POSIX Sockets / epoll loop / RFCOMM)         |
  |  - Manejo directo de File Descriptors sin capas Java intermedias        |
  |  - Tabla de rutas de repetidores y latidos Keep-Alive                   |
  +-------------------------------------------------------------------------+
  | SERIALIZACIÓN ZERO-COPY (Google FlatBuffers)                            |
  |  - Lectura binaria directa en memoria sin deserializar objetos en Heap  |
  +-------------------------------------------------------------------------+
  | CRIPTOGRAFÍA AUTENTICADA (Libsodium / Monocypher)                       |
  |  - Intercambio de claves Curve25519 efímeras por sesión                 |
  |  - Cifrado simétrico autenticado ChaCha20-Poly1305                      |
  +-------------------------------------------------------------------------+
  | PLANIFICADOR DAG Y SUBASTAS (Task Auctioneer)                           |
  |  - Orquestación de secciones, checkpoints y tolerancia a fallos         |
  +-------------------------------------------------------------------------+
  | INFERENCIA ON-DEVICE                                                    |
  |  - llama.cpp nativo en C++20 con aceleración ARM64 NEON FP16            |
  |  - Mapeo en memoria mmap zero-copy del modelo GGUF                      |
  +-------------------------------------------------------------------------+
=============================================================================
```

---

## 📑 4. Flujo de Generación de un Documento Paso a Paso

```
1. DEFINICIÓN DEL DOCUMENTO:
   El usuario pide en Teléfono A:
   "Generar Plan de Respuesta a Desastres de 4 Capítulos".

2. GENERACIÓN DEL ESQUEMA (Outline DAG):
   El planificador local en C++ genera la estructura:
   - Cap. 1: Evaluación Inicial de Daños
   - Cap. 2: Asignación de Recursos Médicos y Albergues
   - Cap. 3: Comunicaciones de Emergencia y Rutas
   - Cap. 4: Protocolo de Recuperación Post-Evento

3. SUBASTA Y DESPACHO P2P:
   - Teléfono A sondea la malla.
   - Teléfono B (4GB) reporta que será Repetidor.
   - Teléfono C (8GB) gana Cap. 1 y Cap. 3.
   - Teléfono D (12GB) gana Cap. 2 y Cap. 4.
   - Los prompts viajan por Bluetooth; los paquetes hacia D rebotan por B.

4. INFERENCIA SIMULTÁNEA:
   - Teléfono C y Teléfono D procesan en paralelo en sus CPUs ARM64.
   - Mientras Teléfono A generaría 4 capítulos en 120 segundos,
     la red en paralelo los completa en apenas 35 segundos.

5. ENSAMBLADO Y RESOLUCIÓN DE FALLOS:
   - Los capítulos completados regresan cifrados al Teléfono A.
   - Si el Teléfono C se apaga o sale del rango, el Teléfono A detecta timeout
     (4 segundos sin latido) y reasigna automáticamente el Cap. 3 al Teléfono D.

6. PASADA DE COHERENCIA Y EXPORTACIÓN:
   - Teléfono A realiza una breve pasada de unificación de estilo (10 seg).
   - El documento queda listo para exportar a .md, .txt o .pdf en el dispositivo.
```

---

## 🛡️ 5. Manejo de Coherencia: El Problema del "Documento Frankenstein"

Cuando varios modelos independientes redactan secciones simultáneas, existe el riesgo de contradicciones o cambios de tono. Kofu Mesh lo mitiga con 3 mecanismos nativos:

1. **Marco de Contexto Global (Global Context Frame):** Cada prompt enviado a un nodo incluye:
   * El título general y la sinopsis del documento.
   * El público objetivo y tono (ej. *"Formal, técnico, tercera persona"*).
   * La lista de secciones que otros nodos están escribiendo para evitar solapamientos.
2. **Semilla Fijada y Temperatura Controlada:** Los parámetros de generación (`temp=0.4`, `repeat_penalty=1.15`) se unifican para garantizar estilo homogéneo.
3. **Puntada Final de Transición (Stitching Pass):** El nodo solicitante ejecuta una pasada ligera local de solo 100 tokens para pulir transiciones entre los capítulos antes de cerrar el archivo.

---

## 📑 6. Motor Ofimático Nativo y Centro de Plantillas OpenXML (.dotx / .potx)

Para evitar la generación exclusiva de texto plano (`.txt`), Kofu integra un subsistema de formateo ofimático profesional basado en las **plantillas oficiales del repositorio padre** (`Kineyo/Proyecto_Kofu/tree/plantillas`), prescindiendo de herramientas externas pesadas (como OfficeCLI en C#/.NET o Apache POI).

### 🏛️ Reutilización de Plantillas Oficiales del Ecosistema Kofu

La rama `plantillas` del repositorio padre contiene 11 paquetes temáticos con más de 66 plantillas maestras en formatos OpenXML (`.dotx` para Word y `.potx` para PowerPoint):

```
+-------------------------------------------------------------------------+
|                  PAQUETES OFICIALES DE PLANTILLAS KOFU                  |
+-------------------+--------------------+--------------------------------+
| Paquete           | Formatos           | Usos Principales               |
+-------------------+--------------------+--------------------------------+
| 🎓 APA 7ma Ed.    | .dotx / .potx      | Tesis, Ensayos, Artículos      |
| 🏛️ Institucional   | .dotx / .potx      | Oficios, Memorándums, Actas    |
| ✨ Minimalista     | .dotx / .potx      | Cartas, Propuestas, Portafolio |
| 💼 Corporativo    | .dotx / .potx      | Business Plans, Minutas, NDAs  |
| ⚡ Startups & AI   | .dotx / .potx      | Arquitectura RFC, Pitch Decks  |
| ⚖️ Jurídico/Legal  | .dotx / .potx      | Contratos, Poderes, Convenios  |
| 📊 Gestión Ágil   | .dotx / .potx      | Retrospectivas, Project Charter|
| 📈 Marketing      | .dotx / .potx      | Briefs, Planes de Lanzamiento  |
| 📚 Educación      | .dotx / .potx      | Syllabi, Rúbricas Didácticas   |
| 🎨 Editorial      | .dotx / .potx      | Guiones, Dossiers de Prensa    |
| 👥 Rec. Humanos   | .dotx / .potx      | Onboarding, Perfiles de Puesto |
+-------------------+--------------------+--------------------------------+
```

### 📦 Centro de Plantillas en la Interfaz (UI Jetpack Compose)

En la interfaz de Kofu Mobile se añade una sección o diálogo modal **"📦 Centro de Plantillas"**:

```
[UI Kofu: Centro de Plantillas]
  │
  ├─ 1. Consulta catálogo JSON remoto:
  │     https://raw.githubusercontent.com/Kineyo/Proyecto_Kofu/plantillas/packages/templates_catalog.json
  │
  ├─ 2. Usuario selecciona categoría (ej. "🎓 APA 7ma Edición")
  │     y presiona "Descargar e Instalar".
  │
  └─ 3. Descarga y Descompresión al Vuelo (En Segundo Plano):
        ┌─────────────────────────────────────────────────────────────┐
        │ URL: raw.githubusercontent.com/.../Plantillas%20APA%207.zip │
        │                          │ (Stream HTTP con OkHttp)         │
        │                          ▼                                  │
        │          java.util.zip.ZipInputStream                       │
        │  (Descomprime directamente en memoria sin guardar el .zip)  │
        │                          │                                  │
        │                          ▼                                  │
        │   Guarda archivos limpios directamente en:                  │
        │   - /data/user/0/.../templates/word/*.dotx                  │
        │   - /data/user/0/.../templates/powerpoint/*.potx            │
        │                          │                                  │
        │     ✅ Registro automático en catálogo local                │
        │     🗑️ Cero archivos .zip residuales en el almacenamiento    │
        └─────────────────────────────────────────────────────────────┘
```

### ⚡ Transformación e Inyección Nativa (.dotx a .docx) sin Runtimes Externos

Para transformar una plantilla `.dotx` descargada en un archivo `.docx` editable con el contenido generado por Kofu, se utilizan **exclusivamente librerías nativas de Android / Java (`java.util.zip`)**:

```
[Plantilla .dotx (30 KB)]
         │
         ▼  (Lectura con ZipInputStream)
1. Modificación de Tipo de Contenido:
   En "[Content_Types].xml", se reemplaza:
   "template.main+xml" ──> "document.main+xml"
         │
         ▼
2. Inyección de Estructura de Texto:
   En "word/document.xml", se inyectan los párrafos, títulos, listas
   y tablas generados por Kofu, mapeándolos a los estilos nativos
   preexistentes en la plantilla ("Heading 1", "Heading 2", "Normal", "Table").
         │
         ▼  (Escritura con ZipOutputStream)
[Archivo Final .docx Listo para Compartir / Abrir]
```

* **Ventajas de la Técnica Nativa:**
  * **Cero Megabytes Extra:** No requiere .NET, OfficeCLI, Mono ni Apache POI.
  * **Velocidad Extrema:** La transformación y reempaquetado toma menos de **30 milisegundos** en cualquier CPU móvil.
  * **Fidelidad Visual Profesional:** Al abrir el archivo en Word o Google Docs, conserva la tipografía, márgenes, colores corporativos y sangrías oficiales de la plantilla.
  * **Exportación Inmediata:** Integración con `FileProvider` y `Intent.ACTION_SEND` de Android para abrir el documento generado en Microsoft 365, Google Docs o enviarlo por correo/WhatsApp.

---

## 💼 7. Estrategia Comercial y Adquisición de Beta Testers (Licencia MIT)

El proyecto Kofu Mobile cuenta con licencia **MIT**, lo que representa una fortaleza para la adopción masiva y no impide generar ingresos recurrentes ni construir un negocio sostenible.

### 🎯 El Posicionamiento de Marca (USP)
> **"Kofu Mesh: Inteligencia Colectiva sin Señal. Cero Servidores. Cero Rastreo. Cero Suscripciones."**

### 👥 Nichos Clave de Beta Testers

```
+-----------------------------------+-----------------------------------+
| Nicho Objetivo                    | Canales de Adquisición            |
+-----------------------------------+-----------------------------------+
| 🌲 Resiliencia Digital / Preppers | - Comunidades Meshtastic y LoRa   |
|    Usuarios off-grid que exigen   | - Subreddit r/preppers            |
|    funcionalidad sin internet.    | - Foros de supervivencia y radio  |
+-----------------------------------+-----------------------------------+
| 🛡️ Privacidad y Legal / DDHH     | - Usuarios GrapheneOS / CalyxOS   |
|    Abogados, periodistas y ONGs   | - Mastodon / Nostr                |
|    con datos ultraconfidenciales. | - Subreddit r/privacy             |
+-----------------------------------+-----------------------------------+
| 🚒 Brigadas de Emergencia y SAR   | - Grupos de rescate en montaña    |
|    Rescatistas, bomberos y        | - Operadores de minería / campo   |
|    médicos en zonas de desastre.  | - Geólogos e ingenieros rurales   |
+-----------------------------------+-----------------------------------+
| 💻 Hackers FOSS y Entusiastas AI  | - Hacker News (Show HN)           |
|    Curiosos de exprimir hardware  | - Subreddit r/LocalLLaMA          |
|    móvil sin gastar en APIs.      | - Comunidad F-Droid / GitHub      |
+-----------------------------------+-----------------------------------+
```

### 🎬 Campaña de Lanzamiento Viral: El "Air-Gap Video Demo"
1. **Video de 45 segundos sin cortes:**
   * Plano cenital de una mesa de madera con 3 teléfonos colocados en fila (un teléfono viejo de 4GB, uno intermedio y uno potente).
   * Se muestra claramente cómo se activa el **Modo Avión** en los tres.
   * En el teléfono humilde se escribe: *"Generar plan de emergencia por huracán de 3 páginas"*.
   * En pantalla aparece el radar mesh reconociendo a los compañeros cercanos; el teléfono potente asume el cómputo y el texto fluye en tiempo real en la pantalla del teléfono humilde.
   * Cierre de impacto: *"Sin internet. Sin cuentas. Sin suscripciones. Código abierto MIT."*
2. **Lanzamiento en Redes:**
   * Publicación técnica en **Hacker News (Show HN)** y **r/LocalLLaMA**.
   * Repositorio con APK precompilado disponible en **GitHub Releases** y canal **IzzyOnDroid**.

---

## 💰 8. Modelos de Monetización Éticos bajo Licencia MIT

```
                             MODELOS DE MONETIZACIÓN KOFU
                                          │
       ┌──────────────────┬───────────────┴───────────────┬──────────────────┐
       ▼                  ▼                               ▼                  ▼
 [KOFU STUDIO PRO] [GOOGLE PLAY SUPPORTER]       [KOFU TACTICAL B2B]   [KOFU PLUG BOX]
  Plugins PDF/DOCX  Venta a $4.99 USD             Soluciones mineras    Mini-servidor SBC
  y canvas visual   (F-Droid 100% gratis)         y brigadas de rescate con Orange Pi 5
```

### 1. Modelo "Supporter Edition" (Google Play vs F-Droid)
* Siguiendo el exitoso modelo de proyectos como **OsmAnd** o **Acode**:
  * **En F-Droid y GitHub Releases:** 100% gratuita y completa.
  * **En Google Play Store:** Versión de pago accesible ($4.99 USD) llamada *"Kofu Assistant - Supporter Edition"*.
  * La mayoría de usuarios no técnicos pagan con gusto en la Play Store por la comodidad de instalaciones automáticas y para apoyar el desarrollo.

### 2. Kofu Studio Pro (Open Core / Plugins de Productividad)
* El núcleo conversacional y la malla P2P permanecen libres bajo MIT.
* Módulos complementarios de pago orientados a profesionales:
  * Exportador avanzado a **PDF tipográfico, LaTeX académico y DOCX corporativo**.
  * Canvas visual de mapas mentales generados a partir de los documentos creados.
  * Agente RAG local sobre carpetas completas de libros o normativas técnicas.

### 3. Kofu Tactical / Enterprise (B2B y B2G)
* Venta de soluciones corporativas empaquetadas para sectores que operan sin señal:
  * Faenas mineras subterráneas, plataformas petrolíferas y buques mercantes.
  * Servicios de fine-tuning y cuantización de modelos con manuales específicos de la empresa (procedimientos de seguridad ISO, catálogo de piezas, guías médicas).
  * Soporte técnico con SLA.

### 4. Hardware Bundles ("Kofu Local Node Box")
* Venta de microcomputadores monoplaca económicos (ej. Orange Pi 5B con 16GB RAM y NPU de 6 TOPS) preconfigurados con el daemon de Kofu Mesh.
* Se instala en un campamento de campo o furgón de rescate; emite una red local Wi-Fi segura y asume al instante toda la carga pesada de los teléfonos del equipo. Margen comercial: 40-50% sobre el coste del hardware.

### 5. Subvenciones y Fondos de Soberanía Digital
* Postulación a fondos públicos para software de código abierto y privacidad:
  * **NLnet Foundation / NGI Zero (Unión Europea):** Subvenciones de €5,000 a €50,000 para soberanía tecnológica.
  * **Sovereign Tech Fund (Alemania):** Financiamiento a proyectos que mejoran la resiliencia digital abierta.

---

## 🗺️ 9. Hoja de Ruta de Implementación (Roadmap)

```
[FASE 1: Motor Ofimático y Gestor de Plantillas Nativo]
- Diálogo modal "Centro de Plantillas" en Compose conectado a raw.githubusercontent.
- Descarga en streaming y descompresión al vuelo con ZipInputStream nativo.
- Conversor OpenXML (.dotx a .docx) mediante sustitución de cabecera y estructura.

[FASE 2: Cimientos de Red Nativa en C++]
- Implementación de sockets POSIX no bloqueantes y enlazador RFCOMM/BLE.
- Esquema de mensajes binarios con Google FlatBuffers.
- Cifrado autenticado E2E con Monocypher (ChaCha20-Poly1305).

[FASE 3: Subasta y Descomposición de Tareas]
- Módulo de telemetría de hardware (RAM libre, térmica, batería).
- Lógica de scoring de subasta inversa en C++20.
- Algoritmo de fragmentación y orquestación de documentos en DAG.

[FASE 4: UI en Compose y Servicio de Fondo]
- Visualizador de radar de nodos cercanos en Jetpack Compose.
- Foreground Service persistente para mantener la malla viva con pantalla apagada.
- Banco de pruebas con 3 dispositivos reales (gama baja + gama alta).

[FASE 5: Lanzamiento Público y Beta Testing]
- Publicación del demo "Air-Gap" en video.
- Lanzamiento de release pública en GitHub e IzzyOnDroid.
- Convocatoria en Hacker News y r/LocalLLaMA.
```
