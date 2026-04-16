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
import java.nio.FloatBuffer

/**
 * MODO OBJETO - VERSÃO FINAL ESTABILIZADA (PARA CEGOS)
 */
class ModoObjeto(private val context: Context) {
    data class ResultadoObjeto(val boxes: List<OverlayView.BoxData>, val falas: List<String>)
    private data class DetectionCandidate(val rect: RectF, val score: Float, val classIdx: Int)

    private var interpreter: Interpreter? = null
    private val translationManager = TranslationManager(context)
    private val labels = mutableListOf<String>()

    @Volatile var estaProcessando = false
    private val lastAnnouncementTime = mutableMapOf<String, Long>()
    
    private val INTERVALO_FALA_REPETIDA = 10000L
    private val CONFIDENCE_THRESHOLD = 0.65f
    private val NMS_THRESHOLD = 0.35f

    private var outputBuffers: Array<ByteBuffer>? = null
    private var outputMap: MutableMap<Int, Any>? = null
    private var boxesIdx = -1
    private var scoresIdx = -1

    init {
        carregarLabels()
        setupDetector()
    }

    private fun carregarLabels() {
        labels.clear()
        try {
            val jsonString = context.assets.open("open_images_br.json").bufferedReader().use { it.readText() }
            val regex = "\"([^\"]+)\":".toRegex()
            labels.add("background") // Índice 0
            labels.addAll(regex.findAll(jsonString).map { it.groupValues[1] })
            Log.d("ModoObjeto", "Labels carregadas: ${labels.size}")
        } catch (e: Exception) {
            Log.e("ModoObjeto", "Erro labels: ${e.message}")
        }
    }

    private fun setupDetector() {
        try {
            val modelFile = FileUtil.loadMappedFile(context, "detector.tflite")
            val interpOptions = Interpreter.Options().setNumThreads(4)
            interpreter = Interpreter(modelFile, interpOptions)
            
            // Mapeamento automático baseado em shapes [1, N, 4] e [1, N, C]
            val count = interpreter!!.outputTensorCount
            outputBuffers = Array(count) { i ->
                val tensor = interpreter!!.getOutputTensor(i)
                val shape = tensor.shape()
                if (shape.contains(4)) boxesIdx = i else scoresIdx = i
                ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
            }
            outputMap = mutableMapOf<Int, Any>().apply {
                outputBuffers?.forEachIndexed { i, buf -> put(i, buf) }
            }
            Log.d("ModoObjeto", "Modelo pronto. BoxesIdx=$boxesIdx, ScoresIdx=$scoresIdx")
        } catch (e: Exception) {
            Log.e("ModoObjeto", "Erro no setup: ${e.message}")
        }
    }

    fun processar(bitmap: Bitmap, callback: (ResultadoObjeto) -> Unit) {
        if (estaProcessando || interpreter == null) return
        estaProcessando = true
        
        try {
            val interp = interpreter!!
            val inputTensor = interp.getInputTensor(0)
            val h = inputTensor.shape()[1]
            val w = inputTensor.shape()[2]
            
            val tensorImage = TensorImage(inputTensor.dataType())
            tensorImage.load(bitmap)
            val processor = ImageProcessor.Builder()
                .add(ResizeOp(h, w, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(127.5f, 127.5f))
                .build()
            
            val inputBuffer = processor.process(tensorImage).buffer
            outputBuffers?.forEach { it.rewind() }
            interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap!!)
            
            val boxBuf = outputBuffers!![boxesIdx].asFloatBuffer()
            val scoreBuf = outputBuffers!![scoresIdx].asFloatBuffer()
            boxBuf.rewind()
            scoreBuf.rewind()

            val boxesShape = interp.getOutputTensor(boxesIdx).shape()
            val scoresShape = interp.getOutputTensor(scoresIdx).shape()
            
            val numAnchors = boxesShape.first { it > 100 } // Geralmente 1917 ou similar
            val numClasses = scoresShape.last()
            
            val candidates = mutableListOf<DetectionCandidate>()
            for (i in 0 until numAnchors) {
                var maxScore = 0f
                var maxClass = -1
                for (c in 1 until numClasses) { 
                    val s = scoreBuf.get(i * numClasses + c)
                    if (s > maxScore) {
                        maxScore = s
                        maxClass = c
                    }
                }

                if (maxScore > CONFIDENCE_THRESHOLD) {
                    val y1 = boxBuf.get(i * 4)
                    val x1 = boxBuf.get(i * 4 + 1)
                    val y2 = boxBuf.get(i * 4 + 2)
                    val x2 = boxBuf.get(i * 4 + 3)
                    
                    // Filtro de artefatos visuais (linhas presas em 0,0 ou bordas)
                    if (y1 > 0.01f && x1 > 0.01f && y2 < 0.99f && x2 < 0.99f) {
                        val rect = RectF(x1.coerceIn(0f, 1f), y1.coerceIn(0f, 1f), x2.coerceIn(0f, 1f), y2.coerceIn(0f, 1f))
                        // Filtro de tamanho mínimo para evitar ruído de fundo (15% da tela)
                        if (rect.width() > 0.15f && rect.height() > 0.15f) {
                            candidates.add(DetectionCandidate(rect, maxScore, maxClass))
                        }
                    }
                }
            }

            val nms = applyNMS(candidates)
            val boxesFinal = mutableListOf<OverlayView.BoxData>()
            val falasFinal = mutableListOf<String>()
            val agora = System.currentTimeMillis()

            nms.forEach { det ->
                val labelRaw = labels.getOrNull(det.classIdx) ?: "objeto"
                val traduzido = translationManager.getTranslation(labelRaw)
                val rect = Rect(
                    (det.rect.left * bitmap.width).toInt(), (det.rect.top * bitmap.height).toInt(),
                    (det.rect.right * bitmap.width).toInt(), (det.rect.bottom * bitmap.height).toInt()
                )
                boxesFinal.add(OverlayView.BoxData(rect, traduzido))
                
                if (agora - (lastAnnouncementTime[traduzido] ?: 0L) > INTERVALO_FALA_REPETIDA) {
                    falasFinal.add(traduzido)
                    lastAnnouncementTime[traduzido] = agora
                }
            }
            callback(ResultadoObjeto(boxesFinal, falasFinal))
        } catch (e: Exception) {
            Log.e("ModoObjeto", "Erro: ${e.message}")
        } finally {
            estaProcessando = false
        }
    }

    private fun applyNMS(detections: List<DetectionCandidate>): List<DetectionCandidate> {
        val sorted = detections.sortedByDescending { it.score }
        val selected = mutableListOf<DetectionCandidate>()
        for (candidate in sorted) {
            var keep = true
            for (sel in selected) {
                if (calculateIoU(candidate.rect, sel.rect) > NMS_THRESHOLD) { keep = false; break }
            }
            if (keep) { selected.add(candidate); if (selected.size >= 3) break }
        }
        return selected
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val inter = RectF(); if (!inter.setIntersect(a, b)) return 0f
        val interArea = inter.width() * inter.height()
        val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - interArea
        return interArea / unionArea
    }
}
