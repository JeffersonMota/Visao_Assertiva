package com.example.visao_pcd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModoObjeto(private val context: Context) {
    data class ResultadoObjeto(val boxes: List<OverlayView.BoxData>, val falas: List<String>)
    private data class DetectionCandidate(val rect: RectF, val score: Float, val classIdx: Int)

    private var interpreter: Interpreter? = null
    private val translationManager = TranslationManager(context)
    private val labels = mutableListOf<String>()

    @Volatile var estaProcessando = false
    private val lastAnnouncementTime = mutableMapOf<String, Long>()
    private val INTERVALO_FALA_REPETIDA = 7000L
    private val CONFIDENCE_THRESHOLD = 0.50f
    private val NMS_THRESHOLD = 0.4f

    private var boxesIdx = -1
    private var scoresIdx = -1
    private var outputBuffers: Array<ByteBuffer>? = null
    private var outputMap: MutableMap<Int, Any>? = null

    init {
        carregarLabels()
        setupDetector()
    }

    private fun carregarLabels() {
        labels.clear()
        try {
            val jsonString = context.assets.open("open_images_br.json").bufferedReader().use { it.readText() }
            val regex = "\"([^\"]+)\":".toRegex()
            labels.add("background")
            labels.addAll(regex.findAll(jsonString).map { it.groupValues[1] })
        } catch (e: Exception) { Log.e("ModoObjeto", "Erro labels: ${e.message}") }
    }

    private fun setupDetector() {
        try {
            val modelFile = FileUtil.loadMappedFile(context, "detector.tflite")
            val options = Interpreter.Options().setNumThreads(4)
            interpreter = Interpreter(modelFile, options)
            
            val count = interpreter!!.outputTensorCount
            for (i in 0 until count) {
                val shape = interpreter!!.getOutputTensor(i).shape()
                // Mapeamento SSD Padrão: [1, N, 4] para boxes e [1, N, C] para scores
                if (shape.size == 3 && shape[2] == 4) boxesIdx = i
                if (shape.size == 3 && shape[2] > 4) scoresIdx = i
            }

            outputBuffers = Array(count) { i ->
                ByteBuffer.allocateDirect(interpreter!!.getOutputTensor(i).numBytes()).order(ByteOrder.nativeOrder())
            }
            outputMap = mutableMapOf<Int, Any>().apply {
                outputBuffers?.forEachIndexed { i, buf -> put(i, buf) }
            }
        } catch (e: Exception) { Log.e("ModoObjeto", "Erro detector: ${e.message}") }
    }

    fun processar(bitmap: Bitmap, callback: (ResultadoObjeto) -> Unit) {
        if (estaProcessando || interpreter == null) return
        estaProcessando = true
        
        try {
            val inputTensor = interpreter!!.getInputTensor(0)
            val tensorImage = TensorImage(inputTensor.dataType())
            tensorImage.load(bitmap)
            
            val inputBuffer = ImageProcessor.Builder()
                .add(ResizeOp(inputTensor.shape()[1], inputTensor.shape()[2], ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(127.5f, 127.5f))
                .build().process(tensorImage).buffer

            outputBuffers?.forEach { it.rewind() }
            interpreter!!.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap!!)
            
            val boxBuf = outputBuffers!![boxesIdx].asFloatBuffer()
            val scoreBuf = outputBuffers!![scoresIdx].asFloatBuffer()
            val numAnchors = interpreter!!.getOutputTensor(boxesIdx).shape()[1]
            val numClasses = interpreter!!.getOutputTensor(scoresIdx).shape()[2]
            
            val candidates = mutableListOf<DetectionCandidate>()
            for (i in 0 until numAnchors) {
                var maxScore = 0f
                var maxClass = -1
                for (c in 1 until numClasses) {
                    val s = scoreBuf.get(i * numClasses + c)
                    if (s > maxScore) { maxScore = s; maxClass = c }
                }

                if (maxScore > CONFIDENCE_THRESHOLD) {
                    // SSD: [ymin, xmin, ymax, xmax]
                    val rect = RectF(boxBuf.get(i*4+1), boxBuf.get(i*4), boxBuf.get(i*4+3), boxBuf.get(i*4+2))
                    candidates.add(DetectionCandidate(rect, maxScore, maxClass))
                }
            }

            val nms = applyNMS(candidates)
            val boxesFinal = mutableListOf<OverlayView.BoxData>()
            val falasFinal = mutableListOf<String>()
            val agora = System.currentTimeMillis()

            nms.forEach { det ->
                val labelOriginal = labels.getOrNull(det.classIdx) ?: "objeto"
                val traduzido = translationManager.getTranslation(labelOriginal)
                
                // Lógica Lookout: esquerda < 0.33, frente, direita > 0.66
                val centerX = det.rect.centerX()
                val posicao = when {
                    centerX < 0.33f -> "à esquerda"
                    centerX > 0.66f -> "à direita"
                    else -> "à frente"
                }
                
                boxesFinal.add(OverlayView.BoxData(
                    Rect((det.rect.left * bitmap.width).toInt(), (det.rect.top * bitmap.height).toInt(), (det.rect.right * bitmap.width).toInt(), (det.rect.bottom * bitmap.height).toInt()),
                    traduzido
                ))
                
                val chaveFala = "$traduzido-$posicao"
                if (agora - (lastAnnouncementTime[chaveFala] ?: 0L) > INTERVALO_FALA_REPETIDA) {
                    falasFinal.add("$traduzido $posicao")
                    lastAnnouncementTime[chaveFala] = agora
                }
            }
            callback(ResultadoObjeto(boxesFinal, falasFinal))
        } catch (e: Exception) {
            Log.e("ModoObjeto", "Erro no processamento: ${e.message}")
        } finally {
            estaProcessando = false
        }
    }

    private fun applyNMS(list: List<DetectionCandidate>): List<DetectionCandidate> {
        val sorted = list.sortedByDescending { it.score }
        val result = mutableListOf<DetectionCandidate>()
        for (c in sorted) {
            var keep = true
            for (r in result) {
                if (calculateIoU(c.rect, r.rect) > NMS_THRESHOLD) { keep = false; break }
            }
            if (keep) {
                result.add(c)
                if (result.size >= 5) break
            }
        }
        return result
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val inter = RectF()
        if (!inter.setIntersect(a, b)) return 0f
        val interArea = inter.width() * inter.height()
        val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - interArea
        return interArea / unionArea
    }
}
