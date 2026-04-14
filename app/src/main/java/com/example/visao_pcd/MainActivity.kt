package com.example.visao_pcd

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.visao_pcd.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Locale
import android.widget.Toast
import java.io.File

class MainActivity : ModoActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var locationOverlay: MyLocationNewOverlay
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechRecognizerIntent: Intent
    private lateinit var gestureDetector: GestureDetector

    // --- CÂMERA ---
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var currentAppMode: AppMode = AppMode.NENHUM
    private val CAMERA_PERMISSION_CODE = 100
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    // --- CONFIGURAÇÃO ---
    private val GOOGLE_MAPS_KEY = "AIzaSyCOp2I7jfprHoqYT-D4ZEs9lwbcEoajOOI"
    private val GEMINI_API_KEY = "AIzaSyDpy1dqXmLhXqcy6FlPXTQ7mEbAMagdg3M"
    private var camera: androidx.camera.core.Camera? = null

    private data class GtfsStop(
        val id: String, 
        val name: String, 
        val lat: Double, 
        val lon: Double,
        val source: String = "IMMU"
    )

    private val gtfsCache = mutableListOf<GtfsStop>()
    private val customStops = mutableListOf<GtfsStop>()
    private var isAwaitingOnibusOption = false
    private var isTtsReady = false
    private var lastSpokenText = ""
    private var lastSpokenTime = 0L
    private var lastGeminiAnalysisTime = 0L
    private var lastColorAnalysisTime = 0L
    private var isListeningVoiceCommand = false
    private lateinit var ollamaService: OllamaService
    private var isAnalyzingAmbiente = false
    private var isAwaitingHighQualityCapture = false
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configuração OSMDroid
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manter a tela ligada para evitar hibernação durante o uso
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        db = AppDatabase.getDatabase(this)
        ollamaService = OllamaService()

        // Inicializar OpenStreetMap (Estilo Satélite Híbrido similar à imagem)
        val satelliteHybrid = object : XYTileSource(
            "USGS_Satellite_Hybrid",
            0, 18, 256, ".png",
            arrayOf("https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}")
        ) {
            override fun getTileURLString(pTileIndex: Long): String {
                return baseUrl
                    .replace("{x}", MapTileIndex.getX(pTileIndex).toString())
                    .replace("{y}", MapTileIndex.getY(pTileIndex).toString())
                    .replace("{z}", MapTileIndex.getZoom(pTileIndex).toString())
            }
        }
        binding.mapOsm.setTileSource(satelliteHybrid)
        binding.mapOsm.setMultiTouchControls(true)
        binding.mapOsm.setBuiltInZoomControls(false)
        binding.mapOsm.controller.setZoom(19.0)

        // Overlay de Localização (Ponto azul igual Cittamobi)
        locationOverlay = object : MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapOsm) {
            override fun drawMyLocation(canvas: android.graphics.Canvas, projection: org.osmdroid.views.Projection, lastFix: android.location.Location) {
                // Força o bearing para 0 (Norte) para que o ícone fique sempre em pé
                val locationCopy = android.location.Location(lastFix)
                locationCopy.bearing = 0f
                super.drawMyLocation(canvas, projection, locationCopy)
            }
        }
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation()
        locationOverlay.isDrawAccuracyEnabled = false // Remove o círculo de precisão para limpar o visual
        
        // Customizar o ícone do usuário com efeito de destaque (Círculo azul + Borda branca + Sombra)
        val userIcon = ContextCompat.getDrawable(this, R.drawable.ic_user_walking)
        if (userIcon != null) {
            val size = (50 * resources.displayMetrics.density).toInt()
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

            // 1. Sombra externa para dar profundidade
            paint.color = Color.parseColor("#44000000")
            canvas.drawCircle(size / 2f, size / 2f, size / 2.2f, paint)

            // 2. Borda branca brilhante
            paint.color = Color.WHITE
            canvas.drawCircle(size / 2f, size / 2f, size / 2.6f, paint)

            // 3. Fundo azul vibrante (Destaque GPS)
            paint.color = Color.parseColor("#007AFF") 
            canvas.drawCircle(size / 2f, size / 2f, size / 3.0f, paint)

            // 4. Desenhar o ícone do ator centralizado
            val iconSize = (size * 0.4).toInt()
            val margin = (size - iconSize) / 2
            userIcon.setBounds(margin, margin, margin + iconSize, margin + iconSize)
            // Aplica cor branca ao ícone para contrastar com o fundo azul
            userIcon.setTint(Color.WHITE)
            userIcon.draw(canvas)
            
            locationOverlay.setPersonIcon(bitmap)
            // Define o mesmo ícone para a direção
            locationOverlay.setDirectionIcon(bitmap)
            
            // Desativar a rotação do ícone com base na orientação (Bússola/GPS)
            locationOverlay.setPersonHotspot(size / 2f, size / 2f)
            
            // Isso garante que o ícone não gire no mapa
            binding.mapOsm.setMapOrientation(0f)
        }
        
        binding.mapOsm.overlays.add(locationOverlay)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        if (!checkAllPermissions()) {
            requestAllPermissions()
        }

        inicializarModos(GEMINI_API_KEY, GOOGLE_MAPS_KEY)
        
        setupTTS()
        setupSpeechRecognizer()
        setupGestures()
        setupMenuClickListeners()
        setupResultListeners()
        
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun checkAndSpeakWelcome() {
        val prefs = getSharedPreferences("VisaoPcdPrefs", Context.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("isFirstRun", true)
        
        if (isFirstRun) {
            falar("Bem-vindo ao Visão PCD. $instrucoesFull", force = true)
            prefs.edit().putBoolean("isFirstRun", false).apply()
        } else {
            falar("Visão PCD iniciado. Dê dois toques na parte de baixo e diga instrução para ouvir as opções.", force = true)
        }
    }

    private val instrucoesFull = "Para utilizar o comando de voz, dê dois toques rápidos na parte de baixo da tela. " +
                               "Os modos Gratuito são: Objetos, Texto, Dinheiro, Cores, Microondas. " +
                               "Os modos Pagos são: Ambiente, Andando, Ônibus, Pessoa e Roupa. " +
                               "Toque 2 vezes na tela e diga o nome do modo para ativar ou para voltar diga menu."

    private fun setupMenuClickListeners() {
        binding.menuBtnAndando.setOnClickListener { setMode(AppMode.ANDANDO) }
        binding.menuBtnObjetos.setOnClickListener { setMode(AppMode.OBJETOS) }
        binding.menuBtnTexto.setOnClickListener { setMode(AppMode.TEXTO) }
        binding.menuBtnDinheiro.setOnClickListener { setMode(AppMode.DINHEIRO) }
        binding.menuBtnPessoa.setOnClickListener { setMode(AppMode.PESSOA) }
        binding.menuBtnRoupa.setOnClickListener { setMode(AppMode.ROUPA) }
        binding.menuBtnAmbiente.setOnClickListener { setMode(AppMode.AMBIENTE) }
        binding.menuBtnOnibus.setOnClickListener { setMode(AppMode.ONIBUS) }
        binding.menuBtnCor.setOnClickListener { setMode(AppMode.COR) }
        binding.menuBtnMicroondas.setOnClickListener { setMode(AppMode.MICROONDAS) }
        
        binding.toolbar.setNavigationOnClickListener {
            setMode(AppMode.NENHUM)
            binding.resultLayout.visibility = View.GONE
        }
    }

    private fun setupResultListeners() {
        binding.btnCloseResult.setOnClickListener {
            binding.resultLayout.visibility = View.GONE
            // Interrompe a leitura do TTS se o usuário fechar a tela
            tts?.stop()
            if (currentAppMode != AppMode.NENHUM && currentAppMode != AppMode.ONIBUS) {
                startCamera()
            }
        }
    }

    private fun setupTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                
                // Configuração para voz humanizada masculina
                val voices = tts?.voices
                val maleVoice = voices?.find { 
                    it.name.lowercase().contains("pt-br-x-abd-network") || 
                    it.name.lowercase().contains("pt-br-x-afs-network") ||
                    (it.name.lowercase().contains("male") && it.locale.language == "pt")
                }
                if (maleVoice != null) {
                    tts?.voice = maleVoice
                }
                
                tts?.setPitch(0.9f) // Tom levemente mais grave para soar mais natural/masculino
                tts?.setSpeechRate(1.0f)

                isTtsReady = true
                Handler(Looper.getMainLooper()).postDelayed({
                    checkAndSpeakWelcome()
                }, 500)
            }
        }
    }

    private fun falar(texto: String, force: Boolean = false, queue: Boolean = false, priority: Boolean = false) {
        if (!isTtsReady || texto.isBlank()) return
        val agora = System.currentTimeMillis()
        
        // No Lookout, a exploração é constante. No modo objetos, permitimos mais fluidez.
        val interval = if (currentAppMode == AppMode.OBJETOS) 1500 else 3000
        
        // Se for prioridade ou forçado, interrompe o que estiver falando (a menos que seja a mesma frase)
        if (priority || force) {
            if (texto != lastSpokenText || agora - lastSpokenTime > 500) {
                tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "ID_PRIORITY_$agora")
                lastSpokenText = texto
                lastSpokenTime = agora
            }
            return
        }

        // Lógica normal de repetição e intervalo
        if (texto != lastSpokenText || agora - lastSpokenTime > interval) {
            val mode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
            tts?.speak(texto, mode, null, "ID_$agora")
            lastSpokenText = texto
            lastSpokenTime = agora
        }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (currentAppMode == AppMode.MICROONDAS) {
                    val botao = binding.overlayView.detectBotaoNoToque(e.x, e.y)
                    if (botao != null) {
                        falar("Você está com o dedo no botão $botao", force = true)
                    }
                }
                return super.onSingleTapUp(e)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val screenHeight = resources.displayMetrics.heightPixels
                val isBottomHalf = e.y > (screenHeight * 0.3) 
                
                if (isBottomHalf) {
                    isListeningVoiceCommand = true
                    // Para qualquer áudio imediatamente
                    tts?.stop()
                    // Cancela reconhecimento anterior se houver
                    speechRecognizer?.cancel()
                    
                    falar("Ouvindo...", force = true)
                    startListening()
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e("VoiceCommand", "Reconhecimento de voz não disponível")
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale o nome do modo")
        }

        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { Log.d("VoiceCommand", "Pronto para ouvir") }
            override fun onBeginningOfSpeech() { Log.d("VoiceCommand", "Começou a falar") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d("VoiceCommand", "Fim da fala") }
            override fun onError(error: Int) {
                isListeningVoiceCommand = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Ocupado, tente novamente."
                    SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Silêncio detectado."
                    SpeechRecognizer.ERROR_CLIENT -> "Erro de conexão. Reiniciando microfone."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sem permissão de áudio."
                    else -> "Erro no microfone: $error"
                }
                Log.e("VoiceCommand", "Erro: $errorMsg ($error)")
                
                // Se for erro de cliente ou ocupado, limpamos o recognizer para a próxima tentativa
                if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    runOnUiThread { setupSpeechRecognizer() }
                }

                if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    falar(errorMsg)
                }
            }
            override fun onResults(results: Bundle?) {
                isListeningVoiceCommand = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    processVoiceCommand(matches[0])
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread {
                try {
                    speechRecognizer?.cancel()
                    speechRecognizer?.startListening(speechRecognizerIntent)
                } catch (e: Exception) {
                    Log.e("VoiceCommand", "Erro ao iniciar SpeechRecognizer", e)
                    // Tentar recriar se falhar
                    setupSpeechRecognizer()
                    speechRecognizer?.startListening(speechRecognizerIntent)
                }
            }
        } else {
            falar("Permissão de microfone necessária.")
            requestAllPermissions()
        }
    }

    private fun processVoiceCommand(command: String) {
        val lower = command.lowercase()
        Log.d("VoiceCommand", "Comando recebido: $lower")
        
        when {
            lower.contains("instruç") || lower.contains("instruc") || lower.contains("ajuda") -> {
                falar(instrucoesFull, force = true)
            }
            lower.contains("ônibus") || lower.contains("onibus") -> setMode(AppMode.ONIBUS)
            lower.contains("microondas") || lower.contains("micro-ondas") -> setMode(AppMode.MICROONDAS)
            lower.contains("objetos") || lower.contains("objeto") -> setMode(AppMode.OBJETOS)
            lower.contains("texto") -> setMode(AppMode.TEXTO)
            lower.contains("dinheiro") -> setMode(AppMode.DINHEIRO)
            lower.contains("cor") || lower.contains("cores") -> setMode(AppMode.COR)
            lower.contains("ambiente") -> setMode(AppMode.AMBIENTE)
            lower.contains("andando") -> setMode(AppMode.ANDANDO)
            lower.contains("pessoa") -> setMode(AppMode.PESSOA)
            lower.contains("roupa") -> setMode(AppMode.ROUPA)
            lower.contains("histórico") || lower.contains("historico") || lower.contains("consultas") || lower.contains("ler histórico") -> lerHistorico()
            
            lower.contains("salvar ponto") || lower.contains("marcar") -> modoOnibus.salvarParadaAtual { falar(it) }
            
            lower.contains("menu") || lower.contains("voltar") || lower.contains("parar") -> {
                setMode(AppMode.NENHUM)
                binding.resultLayout.visibility = View.GONE
                falar("Menu principal")
            }
            else -> falar("Comando não reconhecido. Diga instrução para ajuda.")
        }
    }

    private fun setMode(mode: AppMode) {
        this.currentAppMode = mode
        if (mode != AppMode.NENHUM) {
            val nomeModo = when(mode) {
                AppMode.ANDANDO -> "Andando"
                AppMode.OBJETOS -> "Objetos"
                AppMode.TEXTO -> "Texto"
                AppMode.DINHEIRO -> "Dinheiro"
                AppMode.PESSOA -> "Pessoa"
                AppMode.ROUPA -> "Roupa"
                AppMode.AMBIENTE -> "Ambiente"
                AppMode.ONIBUS -> "Ônibus"
                AppMode.COR -> "Cores"
                AppMode.MICROONDAS -> "Micro-ondas"
                else -> mode.name
            }
            falar("Modo $nomeModo ativado.", priority = true)
        }
        
        runOnUiThread {
            binding.overlayView.updateMode(mode)
            
            if (mode == AppMode.NENHUM) {
                binding.mainMenu.visibility = View.VISIBLE
                binding.appBar.visibility = View.GONE
                binding.mapOsm.visibility = View.GONE
                binding.viewFinder.visibility = View.GONE
                binding.overlayView.visibility = View.GONE
                cameraProvider?.unbindAll()
                isAwaitingOnibusOption = false
                return@runOnUiThread
            }

            binding.mainMenu.visibility = View.GONE
            binding.appBar.visibility = View.VISIBLE
            
            if (mode == AppMode.ONIBUS) {
                binding.mapOsm.visibility = View.VISIBLE
                binding.viewFinder.visibility = View.GONE
                binding.overlayView.visibility = View.GONE
                binding.navPanel.visibility = View.GONE 
                binding.appBar.setBackgroundColor(Color.TRANSPARENT)
                
                cameraProvider?.unbindAll()
                locationOverlay.enableFollowLocation()

                isAwaitingOnibusOption = true
                falar("Modo ônibus ativado. Diga 1 para buscar paradas.", force = true)
            } else {
                binding.mapOsm.visibility = View.GONE
                binding.viewFinder.visibility = View.VISIBLE
                binding.overlayView.visibility = View.VISIBLE
                binding.navPanel.visibility = View.GONE
                isAwaitingOnibusOption = false
                
                if (allPermissionsGranted()) {
                    startCamera()
                } else {
                    ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_CODE)
                }
            }
        }
    }

    private fun checkAllPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        ActivityCompat.requestPermissions(this, permissions, 1001)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                falar("Permissão de câmera é necessária para este modo.")
            }
        }
    }

    private fun startCamera() {
        if (currentAppMode == AppMode.NENHUM || currentAppMode == AppMode.ONIBUS) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (currentAppMode == AppMode.NENHUM || currentAppMode == AppMode.ONIBUS) return@addListener
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy(
                                android.util.Size(1280, 720),
                                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageAnalysis(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("MainActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun salvarConsulta(modo: String, resultado: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val consulta = Consulta(modo = modo, resultado = resultado)
            db.consultaDao().salvar(consulta)
            Log.d("Historico", "Consulta salva: $resultado")
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageAnalysis(imageProxy: ImageProxy) {
        if (isListeningVoiceCommand) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val now = System.currentTimeMillis()
            
            runOnUiThread {
                binding.overlayView.setImageSourceInfo(imageProxy.width, imageProxy.height)
                binding.overlayView.setRotation(imageProxy.imageInfo.rotationDegrees)
            }

            when (currentAppMode) {
                AppMode.OBJETOS -> {
                    modoObjeto.processar(image, imageProxy.width) { resultado ->
                        runOnUiThread {
                            binding.overlayView.updateBoundingBoxes(resultado.boxes)
                        }
                        
                        // Fala os nomes individuais dos objetos (Rápido)
                        resultado.falas.forEach { fala ->
                            falar(fala, queue = true)
                        }

                        imageProxy.close()
                    }
                }
                AppMode.TEXTO -> {
                    // Se estivermos aguardando a captura de alta qualidade (após o flash)
                    if (isAwaitingHighQualityCapture) {
                        isAwaitingHighQualityCapture = false
                        val rawBitmap = imageProxy.toBitmap()
                        camera?.cameraControl?.enableTorch(false)

                        runOnUiThread {
                            // Garante que a foto exibida esteja na vertical
                            val finalBitmap = if (rawBitmap.width > rawBitmap.height) {
                                val matrix = android.graphics.Matrix()
                                matrix.postRotate(90f)
                                android.graphics.Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                            } else {
                                rawBitmap
                            }
                            binding.ivCapturedImage.setImageBitmap(finalBitmap)
                            binding.ivCapturedImage.visibility = View.VISIBLE
                            binding.tvExtractedText.text = "Lendo texto da imagem..."
                            binding.resultLayout.visibility = View.VISIBLE
                            binding.tvLiveTextFeedback.visibility = View.GONE
                        }

                        modoTexto.processarAltaQualidade(rawBitmap) { textoFinal ->
                            runOnUiThread {
                                binding.tvExtractedText.text = textoFinal
                                falar("Texto detectado: $textoFinal", force = true)
                                salvarConsulta("Texto", textoFinal)
                            }
                        }
                        imageProxy.close()
                        return
                    }

                    // Verificamos se já existe um resultado sendo exibido para não processar novos frames
                    if (binding.resultLayout.visibility == View.VISIBLE) {
                        imageProxy.close()
                        return
                    }

                    modoTexto.processar(image, object : ModoTexto.Callback {
                        override fun onEscaneando() {
                            runOnUiThread { binding.tvLiveTextFeedback.text = "Escaneando..." }
                            imageProxy.close()
                        }

                        override fun onFocando() {
                            // Falar apenas se não tiver falado nos últimos 3 segundos
                            falar("Texto detectado. Mantenha firme.")
                            runOnUiThread { binding.tvLiveTextFeedback.text = "Focando..." }
                            imageProxy.close()
                        }

                        override fun onTextoDetectado(texto: String, boxes: List<OverlayView.BoxData>) {
                            runOnUiThread {
                                binding.overlayView.updateBoundingBoxes(boxes)
                                if (texto.isNotBlank()) binding.tvLiveTextFeedback.visibility = View.VISIBLE
                                else binding.tvLiveTextFeedback.visibility = View.GONE
                            }
                            if (texto.isBlank()) imageProxy.close()
                        }

                        override fun onCapturarAltaQualidade() {
                            if (binding.resultLayout.visibility == View.VISIBLE) {
                                imageProxy.close()
                                return
                            }

                            camera?.cameraControl?.enableTorch(true)
                            
                            runOnUiThread {
                                binding.tvLiveTextFeedback.text = "Focando e iluminando..."
                                binding.tvLiveTextFeedback.visibility = View.VISIBLE
                            }

                            // Fechamos este frame e aguardamos o próximo (que virá com flash ligado)
                            imageProxy.close()

                            // Usamos um pequeno delay apenas para o hardware reagir
                            Handler(Looper.getMainLooper()).postDelayed({
                                // A próxima análise de frame entrará no fluxo normal, 
                                // mas como o torch está ligado, o próximo processarAltaQualidade
                                // pegará a imagem clara.
                                // Para forçar isso, vamos sinalizar que a próxima imagem é a "definitiva"
                                isAwaitingHighQualityCapture = true
                            }, 500)
                        }

                        override fun onError(e: Exception) {
                            imageProxy.close()
                        }
                    })
                }
                AppMode.DINHEIRO -> {
                    val bitmap = imageProxy.toBitmap()
                    modoDinheiro.processar(image, bitmap) { valor, deveFalar ->
                        if (deveFalar && valor.isNotEmpty()) {
                            falar(valor, force = true)
                            salvarConsulta("Dinheiro", valor)
                        }
                        runOnUiThread {
                            binding.overlayView.updateDetectedMoney(valor)
                        }
                        imageProxy.close() // Fecha apenas após processar
                    }
                }
                AppMode.COR -> {
                    if (now - lastColorAnalysisTime > 1000) {
                        lastColorAnalysisTime = now
                        val bitmap = imageProxy.toBitmap()
                        modoCor.processar(bitmap) { colorName, _ ->
                            falar(colorName, force = true)
                            salvarConsulta("Cor", colorName)
                            runOnUiThread {
                                binding.overlayView.updateDetectedColor(colorName)
                            }
                        }
                    }
                    imageProxy.close()
                }
                /*
                AppMode.PESSOA, AppMode.ROUPA, AppMode.AMBIENTE -> {
                    if (now - lastGeminiAnalysisTime > 10000) { 
                        lastGeminiAnalysisTime = now
                        val bitmap = imageProxy.toBitmap()
                        analisarComGemini(bitmap, currentAppMode)
                    }
                    imageProxy.close()
                }
                */
                AppMode.PESSOA -> {
                    if (now - lastGeminiAnalysisTime > 10000) {
                        lastGeminiAnalysisTime = now
                        val bitmap = imageProxy.toBitmap()
                        lifecycleScope.launch {
                            val descricao = modoPessoa.processar(bitmap)
                            runOnUiThread {
                                binding.ivCapturedImage.setImageBitmap(bitmap)
                                binding.tvExtractedText.text = descricao
                                binding.resultLayout.visibility = View.VISIBLE
                                falar(descricao, force = true)
                                salvarConsulta("Pessoa", descricao)
                            }
                        }
                    }
                    imageProxy.close()
                }
                AppMode.ROUPA -> {
                    if (now - lastGeminiAnalysisTime > 10000) {
                        lastGeminiAnalysisTime = now
                        val bitmap = imageProxy.toBitmap()
                        lifecycleScope.launch {
                            val descricao = modoRoupa.processar(bitmap)
                            runOnUiThread {
                                binding.ivCapturedImage.setImageBitmap(bitmap)
                                binding.tvExtractedText.text = descricao
                                binding.resultLayout.visibility = View.VISIBLE
                                falar(descricao, force = true)
                                salvarConsulta("Roupa", descricao)
                            }
                        }
                    }
                    imageProxy.close()
                }
                AppMode.AMBIENTE -> {
                    if (!isAnalyzingAmbiente) {
                        isAnalyzingAmbiente = true
                        val bitmap = imageProxy.toBitmap()
                        falar("Analisando ambiente com inteligência local...", force = true)
                        
                        val prompt = "Descreva esta cena para um cego de forma curta e objetiva. Fale sobre móveis e obstáculos."
                        ollamaService.gerarDescricao(prompt) { resposta ->
                            runOnUiThread {
                                binding.ivCapturedImage.setImageBitmap(bitmap)
                                binding.tvExtractedText.text = resposta
                                binding.resultLayout.visibility = View.VISIBLE
                                falar(resposta, force = true)
                                salvarConsulta("Ambiente", resposta)
                                isAnalyzingAmbiente = false
                                imageProxy.close()
                            }
                        }
                    } else {
                        imageProxy.close()
                    }
                }
                AppMode.ANDANDO -> {
                    val width = imageProxy.width
                    val height = imageProxy.height
                    modoAndando.processar(image, width, height) { hasObstacle ->
                        if (hasObstacle) {
                            falar("Cuidado, obstáculo à frente", priority = true)
                        }
                    }
                    imageProxy.close()
                }
                AppMode.MICROONDAS -> {
                    if (now - lastGeminiAnalysisTime > 15000) {
                        lastGeminiAnalysisTime = now
                        val bitmap = imageProxy.toBitmap()
                        lifecycleScope.launch {
                            modoMicroondas.analisarPainel(bitmap) { botoes ->
                                runOnUiThread {
                                    binding.overlayView.updateBotoesMicroondas(botoes)
                                    if (botoes.isEmpty()) {
                                        falar("Não identifiquei botões. Tente aproximar ou afastar a câmera.")
                                    } else {
                                        falar("Painel identificado. Deslize o dedo na tela para encontrar os botões.")
                                    }
                                }
                            }
                            imageProxy.close()
                        }
                    } else {
                        imageProxy.close()
                    }
                }
                /*
                AppMode.ANDANDO -> {
                    modoObjeto.processar(image, imageProxy.width) { resultado ->
                        val hasObstacle = resultado.boxes.any { 
                            val areaPercent = (it.rect.width() * it.rect.height()).toFloat() / (imageProxy.width * imageProxy.height)
                            areaPercent > 0.35
                        }
                        if (hasObstacle) {
                            falar("Cuidado, obstáculo à frente", priority = true)
                        }
                        
                        runOnUiThread {
                            binding.overlayView.updateBoundingBoxes(resultado.boxes)
                        }
                    }
                    imageProxy.close()
                }
                */
                else -> {
                    imageProxy.close()
                }
            }
        } else {
            imageProxy.close()
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, this.width, this.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        var bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        // Se a rotação for 0, mas a largura for maior que a altura, provavelmente precisamos rotacionar 90 graus
        // para garantir que a imagem capturada no modo retrato fique vertical.
        var rotation = imageInfo.rotationDegrees
        if (rotation == 0 && bitmap.width > bitmap.height) {
            rotation = 90
        }

        val matrix = android.graphics.Matrix()
        matrix.postRotate(rotation.toFloat())

        var rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        
        // Garante que a imagem sempre fique em pé (Portrait)
        if (rotated.width > rotated.height) {
            val portraitMatrix = android.graphics.Matrix()
            portraitMatrix.postRotate(90f)
            rotated = Bitmap.createBitmap(rotated, 0, 0, rotated.width, rotated.height, portraitMatrix, true)
        }
        return rotated
    }

    private fun lerHistorico() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ultimas = db.consultaDao().listarUltimas()
            val texto = if (ultimas.isEmpty()) {
                "Você ainda não possui consultas no histórico."
            } else {
                "Suas últimas consultas são: " + ultimas.joinToString(". ") { it.resultado }
            }
            withContext(Dispatchers.Main) {
                falar(texto, force = true)
            }
        }
    }

    override fun onResume() { super.onResume(); binding.mapOsm.onResume() }
    override fun onPause() { super.onPause(); binding.mapOsm.onPause() }
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts?.stop()
        tts?.shutdown()
    }
}
