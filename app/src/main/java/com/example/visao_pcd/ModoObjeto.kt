package com.example.visao_pcd

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

class ModoObjeto {
    // Implementação de TensorFlow Lite via Custom Model do ML Kit
    private val localModel = LocalModel.Builder()
        .setAssetFilePath("detector.tflite")
        .build()

    private val objectDetector = ObjectDetection.getClient(
        CustomObjectDetectorOptions.Builder(localModel)
            .setDetectorMode(CustomObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .setClassificationConfidenceThreshold(0.3f)
            .setMaxPerObjectLabelCount(3)
            .build()
    )

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.3f) // Reduzido para 0.3f para capturar chaves e objetos pequenos
            .build()
    )

    private val translatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.PORTUGUESE)
        .build()
    private val translator = Translation.getClient(translatorOptions)
    private var modelDownloaded = false

    init {
        translator.downloadModelIfNeeded()
            .addOnSuccessListener { modelDownloaded = true }
            .addOnFailureListener { Log.e("ModoObjeto", "Falha ao baixar tradutor", it) }
    }

    // Cache persistente durante a sessão
    private val translationCache = mutableMapOf<String, String>().apply {
        put("Home", "") // Ignora ambiente
        put("House", "") // Ignora ambiente
        put("Selfie", "") // Ignora selfies
    }
    private val objetosVistosRecentemente = mutableMapOf<String, Long>()
    
    // Intervalos reduzidos para ser mais responsivo (estilo Lookout)
    private val INTERVALO_REPETICAO = 3000L // Reduzido de 5s para 3s para buscas ativas
    private val INTERVALO_NOVO_OBJETO = 300L // Reduzido de 800ms para 300ms para resposta imediata
    private var ultimoTempoAnuncioGlobal = 0L

    data class ResultadoObjeto(
        val boxes: List<OverlayView.BoxData>,
        val falas: List<String>
    )

    fun processar(image: InputImage, imageWidth: Int, callback: (ResultadoObjeto) -> Unit) {
        val taskDetection = objectDetector.process(image)
        val taskLabels = labeler.process(image)

        Tasks.whenAllComplete(taskDetection, taskLabels).addOnCompleteListener {
            val detectedObjects = if (taskDetection.isSuccessful) taskDetection.result else emptyList()
            val labels = if (taskLabels.isSuccessful) taskLabels.result else emptyList()

            val agora = System.currentTimeMillis()
            val boxesFinal = mutableListOf<OverlayView.BoxData>()
            val falasFinais = mutableListOf<String>()

            // Verifica se há componentes de computador na cena para evitar erro de "Instrumento Musical"
            val temComputador = labels.any { 
                val t = it.text.lowercase()
                t.contains("laptop") || t.contains("computer") || t.contains("notebook")
            }

            for (obj in detectedObjects) {
                val bestLabel = obj.labels.maxByOrNull { it.confidence }
                
                // --- REGRAS DE DESAMBIGUAÇÃO AVANÇADA ---
                var rawName = bestLabel?.text ?: ""
                val labelsTexto = labels.map { it.text.lowercase() }
                
                // 1. Controle Remoto vs Celular (Controle é longo, celular tem tela)
                val temRemote = labelsTexto.any { it.contains("remote") || it.contains("control") }
                val temPhone = labelsTexto.any { it.contains("phone") || it.contains("mobile") || it.contains("cellular") }
                
                if (temRemote && temPhone) {
                    rawName = "Remote Control" // Prioriza controle se ambos aparecerem
                } else if (temRemote) {
                    rawName = "Remote Control"
                }

                // 2. Vidro de Perfume / Cosméticos
                if (labelsTexto.any { it.contains("perfume") || it.contains("cosmetic") || it.contains("fragrance") }) {
                    rawName = "Perfume"
                }

                // 3. Joystick / Game Controller
                if (labelsTexto.any { it.contains("joystick") || it.contains("gamepad") || it.contains("game controller") }) {
                    rawName = "Joystick de Videogame"
                }

                // 4. Ar Condicionado
                if (labelsTexto.any { it.contains("air conditioner") || it.contains("hvac") }) {
                    rawName = "Air Conditioner"
                }

                // 5. Roteador / Modem
                if (labelsTexto.any { it.contains("router") || it.contains("modem") || it.contains("access point") }) {
                    rawName = "Router"
                }

                // 6. Geladeira vs Cadeira (ML Kit às vezes confunde pela verticalidade)
                val temGeladeiraGlobal = labelsTexto.any { it.contains("refrigerator") || it.contains("fridge") }
                if ((rawName.lowercase().contains("chair") || rawName.lowercase().contains("furniture")) && temGeladeiraGlobal) {
                    rawName = "Refrigerator"
                }

                // 7. Chaves (Prioridade por ser um item crítico pequeno)
                val temChaveGlobal = labelsTexto.any { it.contains("key") || it.contains("chain") }
                if (temChaveGlobal && (rawName.isBlank() || rawName.lowercase() in listOf("item", "object", "tool", "fashion good", "metal", "steel", "brass"))) {
                    rawName = "Key"
                }

                // 8. Monitor vs Televisão (Desambiguação por contexto)
                val temTecladoOuMouse = labelsTexto.any { it.contains("keyboard") || it.contains("mouse") }
                val temControleRemoto = labelsTexto.any { it.contains("remote") || it.contains("control") }
                val isVisualUnit = rawName.lowercase().let { 
                    it.contains("screen") || it.contains("monitor") || it.contains("television") || it.contains("tv") || it.contains("display") 
                }
                
                if (isVisualUnit) {
                    rawName = when {
                        temTecladoOuMouse -> "Monitor"
                        temControleRemoto -> "Television"
                        else -> "Television" // Em ambiente doméstico, TV é mais provável para objetos grandes
                    }
                }

                // 9. Mouse e Teclado (Prioridade máxima sobre genéricos)
                val labelsTextoLower = labelsTexto.map { it.lowercase() }
                if (rawName.isBlank() || rawName.lowercase() in listOf("item", "object", "packaged goods", "tool", "musical instrument", "furniture")) {
                    when {
                        labelsTextoLower.any { it.contains("keyboard") } -> rawName = "Keyboard"
                        labelsTextoLower.any { it.contains("mouse") } -> rawName = "Mouse"
                        temComputador -> rawName = "Laptop"
                    }
                }

                // Evita que o teclado seja chamado de notebook por causa de labels globais de "computer"
                if (rawName.lowercase().contains("laptop") && labelsTextoLower.any { it.contains("keyboard") }) {
                    rawName = "Keyboard"
                }

                if (bestLabel != null && bestLabel.confidence < 0.45f && rawName.isBlank()) continue

                // Se o detector for genérico ou vazio, tentamos usar o rotulador global
                val isGeneric = rawName.isBlank() || 
                               rawName.lowercase() in listOf("item", "object", "thing", "home appliance", "furniture", "fashion good", "food")
                
                if (isGeneric && detectedObjects.size <= 3) { 
                    val bestGlobalLabel = labels.filter { 
                        it.text.lowercase() !in listOf("item", "object", "furniture", "home appliance", "musical instrument", "tool")
                    }.maxByOrNull { it.confidence }
                    
                    if (bestGlobalLabel != null && bestGlobalLabel.confidence > 0.4f) {
                        rawName = bestGlobalLabel.text
                    }
                }

                if (rawName.isBlank() || rawName.lowercase() in listOf("item", "object")) continue


                // --- TRADUÇÃO INSTANTÂNEA ---
                val nomeTraduzido = getTranslation(rawName)
                
                // Se a tradução for vazia, ignoramos o objeto
                if (nomeTraduzido.isBlank()) continue
                
                // Adiciona na interface gráfica (Label) em PORTUGUÊS
                boxesFinal.add(OverlayView.BoxData(obj.boundingBox, nomeTraduzido))

                // Lógica de Voz
                val centerX = obj.boundingBox.centerX()
                val posicao = when {
                    centerX < imageWidth * 0.3 -> "à esquerda"
                    centerX > imageWidth * 0.7 -> "à direita"
                    else -> "à frente"
                }

                val idFala = "$nomeTraduzido-$posicao"
                if (agora - ultimoTempoAnuncioGlobal > INTERVALO_NOVO_OBJETO) {
                    if (agora - (objetosVistosRecentemente[idFala] ?: 0L) > INTERVALO_REPETICAO) {
                        falasFinais.add(nomeTraduzido)
                        objetosVistosRecentemente[idFala] = agora
                        ultimoTempoAnuncioGlobal = agora
                    }
                }
            }

            callback(ResultadoObjeto(boxesFinal, falasFinais))
        }
    }

    // Função para gerar um resumo inteligente da cena usando o Ollama
    fun descreverCenaComOllama(boxes: List<OverlayView.BoxData>, ollama: OllamaService, callback: (String) -> Unit) {
        if (boxes.isEmpty()) return
        
        val objetosUnicos = boxes.mapNotNull { it.label }.distinct().joinToString(", ")
        if (objetosUnicos.isBlank()) return

        val prompt = "Estou vendo os seguintes objetos em Português: $objetosUnicos. " +
                     "Descreva de forma natural e muito curta o que parece ser este ambiente."
        
        ollama.gerarDescricao(prompt, callback)
    }

    private fun getTranslation(rawName: String): String {
        if (rawName.isBlank()) return ""

        // 1. Verificamos o Cache
        translationCache[rawName]?.let { if (it.isBlank()) return "" else return it }

        // 2. Se não estiver no cache, tentamos o tradutor manual rápido
        val manual = manualTranslate(rawName)
        
        // Se a tradução manual retornou algo diferente do original (ignorando case), é porque traduziu
        if (!manual.equals(rawName, ignoreCase = true)) {
            return manual
        }

        // Termos técnicos aceitos que soam igual em PT ou são empréstimos comuns
        val termosAceitos = listOf("mouse", "notebook", "laptop", "tablet", "joystick", "smartphone")
        if (rawName.lowercase() in termosAceitos) {
            val capitalized = rawName.lowercase().replaceFirstChar { it.uppercase() }
            return if (capitalized == "Laptop") "Notebook" else capitalized
        }

        // 3. Disparamos a tradução do Google em background para as próximas vezes
        if (modelDownloaded) {
            translator.translate(rawName).addOnSuccessListener { traduzido ->
                val fixed = fixTranslation(traduzido)
                if (fixed.isNotBlank()) {
                    translationCache[rawName] = fixed
                } else {
                    translationCache[rawName] = "" // Marca como ignorado se tradução falhar
                }
            }
        }

        // Se não houver tradução manual e não estiver no cache, retorna vazio
        // para NÃO falar em inglês enquanto a tradução do Google não chega.
        return ""
    }

    private fun fixTranslation(texto: String): String {
        val t = texto.lowercase()
        return when {
            t.contains("computador portátil") || t.contains("computador portatil") -> "Notebook"
            t.contains("mouse de computador") -> "Mouse"
            t.contains("telefone celular") -> "Celular"
            t.contains("unidade de exibição") -> "Monitor ou TV"
            t.contains("calçado") -> "Sapato"
            t.contains("instrumento musical") -> "" // Ignora se vier do tradutor sem contexto
            else -> texto
        }
    }

    private fun manualTranslate(label: String): String {
        val l = label.lowercase()
        
        // Bloqueio explícito de termos indesejados, genéricos e partes do corpo
        if (l.contains("home") || l.contains("house") || l.contains("selfie") || 
            l.contains("toy") || l.contains("room") || l.contains("indoor") || 
            l.contains("furniture") || l.contains("good") || l.contains("well") || 
            l.contains("nice") || l.contains("hand") || l.contains("finger") || 
            l.contains("nail") || l.contains("arm") || l.contains("leg")) return ""

        return when {
            l.contains("metal") || l.contains("steel") || l.contains("iron") -> "" // Bloqueia anúncios genéricos de material

            // Eletrônicos
            l.contains("laptop") || l.contains("notebook") -> "Notebook"
            l.contains("mouse") -> "Mouse"
            l.contains("phone") || l.contains("cellular") || l.contains("mobile") -> "Celular"
            l.contains("keyboard") -> "Teclado"
            l.contains("monitor") || l.contains("screen") || l.contains("display") -> "Monitor"
            l.contains("television") || l.contains("tv") -> "Televisão"
            l.contains("remote") || l.contains("control") -> "Controle remoto"
            l.contains("camera") -> "Câmera"
            l.contains("headphones") -> "Fone de ouvido"
            l.contains("router") || l.contains("modem") -> "Roteador"
            l.contains("air conditioner") -> "Ar Condicionado"
            
            // Mobiliário (Threshold maior para mesa para evitar confusão com pequenos objetos)
            l.contains("chair") -> "Cadeira"
            l.contains("table") || l.contains("desk") -> {
                // Vidros de perfume e pequenos objetos costumam estar SOBRE a mesa, 
                // e o ML Kit às vezes confunde o suporte com a mesa.
                // Se o rótulo for puramente 'table', ignoramos em favor de labels mais específicos do detector global
                "" 
            }
            l.contains("door") -> "Porta"
            l.contains("window") -> "Janela"
            l.contains("bed") -> "Cama"
            l.contains("couch") || l.contains("sofa") -> "Sofá"
            l.contains("cabinet") || l.contains("cupboard") -> "Armário"
            l.contains("shelf") -> "Prateleira"
            
            // Itens comuns de casa
            l.contains("bottle") || l.contains("perfume") || l.contains("cosmetics") -> "Frasco ou Garrafa"
            l.contains("speaker") || l.contains("audio equipment") -> "Caixa de som"
            l.contains("cup") || l.contains("glass") || l.contains("mug") -> "Copo"
            l.contains("plate") -> "Prato"
            l.contains("spoon") -> "Colher"
            l.contains("fork") -> "Garfo"
            l.contains("knife") -> "Faca"
            l.contains("bowl") -> "Tigela"
            l.contains("backpack") || l.contains("bag") || l.contains("handbag") -> "Mochila ou bolsa"
            l.contains("wallet") -> "Carteira"
            l.contains("key") || l.contains("chain") -> "Chave"
            l.contains("umbrella") -> "Guarda-chuva"
            l.contains("clock") || l.contains("watch") -> "Relógio"
            l.contains("mirror") -> "Espelho"
            l.contains("toothbrush") -> "Escova de dentes"
            l.contains("toothpaste") -> "Pasta de dentes"
            l.contains("soap") -> "Sabonete"
            l.contains("towel") -> "Toalha"
            l.contains("comb") -> "Pente ou escova"
            l.contains("scissors") -> "Tesoura"
            
            // Cozinha
            l.contains("microwave") -> "Micro-ondas"
            l.contains("refrigerator") || l.contains("fridge") -> "Geladeira"
            l.contains("oven") || l.contains("stove") -> "Fogão ou Forno"
            l.contains("blender") -> "Liquidificador"
            l.contains("kettle") -> "Chaleira"
            l.contains("toaster") -> "Torradeira"
            l.contains("coffee maker") || l.contains("coffeemaker") -> "Cafeteira"
            l.contains("sink") -> "Pia"
            l.contains("pot") || l.contains("pan") -> "Panela"
            
            // Limpeza
            l.contains("broom") -> "Vassoura"
            l.contains("bucket") -> "Balde"
            l.contains("vacuum") -> "Aspirador de pó"
            l.contains("trash can") || l.contains("garbage") || l.contains("waste container") -> "Lixeira"
            
            // Higiene e Objetos de Risco (Gilete/Barbeador)
            l.contains("razor") || l.contains("shaver") || l.contains("blade") -> "Aparelho de barbear"
            l.contains("safety razor") -> "Gilete"
            
            // Escritório e Diversos
            l.contains("book") -> "Livro"
            l.contains("pen") || l.contains("pencil") -> "Caneta"
            l.contains("paper") -> "Papel"
            l.contains("box") -> "Caixa"
            l.contains("tool") -> "Ferramenta"
            
            // Seres e Vestuário
            l.contains("person") || l.contains("man") || l.contains("woman") || l.contains("boy") || l.contains("girl") -> "Pessoa"
            l.contains("cat") -> "Gato"
            l.contains("dog") -> "Cachorro"
            l.contains("shoe") || l.contains("footwear") || l.contains("sneaker") -> "Sapato"
            l.contains("glasses") || l.contains("sunglasses") -> "Óculos"
            l.contains("hat") || l.contains("cap") -> {
                // Se detectar 'hat' mas houver indícios de eletrônicos, pode ser uma caixa de som redonda
                if (l.contains("audio") || l.contains("electronic")) "Caixa de som" else "Chapéu"
            }

            // Genéricos do ML Kit Object Detection
            l.contains("home appliance") -> "Eletrodoméstico"
            l.contains("fashion good") -> "Acessório ou vestuário"
            l.contains("food") -> "Alimento"

            else -> label
        }
    }
}
