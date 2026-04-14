package com.example.visao_pcd

import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

class ModoObjeto {
    // Implementação de TensorFlow Lite via Custom Model do ML Kit
    private val localModel = LocalModel.Builder()
        .setAssetFilePath("detector.tflite")
        .build()

    private val customLabeler = ImageLabeling.getClient(
        CustomImageLabelerOptions.Builder(localModel)
            .setConfidenceThreshold(0.3f)
            .setMaxResultCount(5)
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
        val taskCustomLabels = customLabeler.process(image)
        val taskLabels = labeler.process(image)

        Tasks.whenAllComplete(taskCustomLabels, taskLabels).addOnCompleteListener {
            val customLabels = if (taskCustomLabels.isSuccessful) taskCustomLabels.result else emptyList()
            val labels = if (taskLabels.isSuccessful) taskLabels.result else emptyList()

            val agora = System.currentTimeMillis()
            val boxesFinal = mutableListOf<OverlayView.BoxData>()
            val falasFinais = mutableListOf<String>()

            // 1. Identificamos se é um objeto de alta prioridade ou risco (ex: Gilete)
            val temObjetoRisco = customLabels.any { it.text.lowercase().contains("razor") || it.text.lowercase().contains("blade") }

            // 2. Unificamos e processamos as labels
            val allLabels = (customLabels + labels).distinctBy { it.text.lowercase() }

            for (label in allLabels) {
                val rawName = label.text
                
                // Thresholds diferenciados por confiança
                val minConfidence = when {
                    rawName.lowercase().contains("razor") -> 0.35f // Prioridade para gilete
                    customLabels.contains(label) -> 0.40f // Modelo customizado costuma ser mais preciso
                    else -> 0.55f // Modelo genérico ML Kit
                }

                if (label.confidence < minConfidence) continue

                val nomeTraduzido = getTranslation(rawName)
                if (nomeTraduzido.isBlank()) continue
                
                // ImageLabeling não fornece Bounding Boxes reais. 
                // Para manter a UI e o ModoAndando funcionando (baseado em área),
                // simulamos um Rect central proporcional à confiança para objetos detectados via Labeling.
                val simulatedRect = if (label.confidence > 0.7f) {
                    // Objeto dominante -> Rect maior
                    Rect(imageWidth/4, 200, 3*imageWidth/4, 600)
                } else {
                    // Objeto menor/distante
                    Rect(imageWidth/2 - 50, 400, imageWidth/2 + 50, 500)
                }
                
                boxesFinal.add(OverlayView.BoxData(simulatedRect, nomeTraduzido))

                val idFala = nomeTraduzido
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
