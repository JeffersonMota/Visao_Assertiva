package com.example.visao_pcd

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val rectPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 50f
        style = Paint.Style.FILL
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(15f, 0f, 0f, Color.BLACK)
    }

    private val backgroundPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private var boundingBoxes: List<BoxData> = emptyList()
    private var objectName: String? = null
    private var detectedColorName: String? = null
    private var appMode: AppMode = AppMode.ANDANDO
    private var proximity: Float = 0f

    private var botoesMicroondas: List<ModoMicroondas.BotaoMicroondas> = emptyList()

    private var imageWidth = 0
    private var imageHeight = 0
    private var rotationDegrees = 0

    data class BoxData(val rect: Rect, val label: String?)

    fun setImageSourceInfo(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
    }

    fun setRotation(degrees: Int) {
        rotationDegrees = degrees
        postInvalidate()
    }

    private fun getScaledRect(rect: Rect): RectF {
        if (imageWidth == 0 || imageHeight == 0) return RectF(rect)

        // Com ML Kit, se passamos a rotação no InputImage, 
        // as coordenadas retornadas já estão no sistema 'upright'.
        // Só precisamos calcular a escala baseada nas dimensões efetivas.
        
        val effectiveWidth = if (rotationDegrees % 180 == 90) imageHeight else imageWidth
        val effectiveHeight = if (rotationDegrees % 180 == 90) imageWidth else imageHeight

        val scaleX = width.toFloat() / effectiveWidth
        val scaleY = height.toFloat() / effectiveHeight

        return RectF(
            rect.left * scaleX,
            rect.top * scaleY,
            rect.right * scaleX,
            rect.bottom * scaleY
        )
    }

    fun updateMode(mode: AppMode) {
        appMode = mode
        boundingBoxes = emptyList()
        objectName = null
        detectedColorName = null
        proximity = 0f
        postInvalidate()
    }

    fun updateBoundingBox(rect: Rect?, name: String?) {
        boundingBoxes = if (rect != null) listOf(BoxData(rect, name)) else emptyList()
        objectName = name
        postInvalidate()
    }

    fun getBoundingBox(): Rect? = boundingBoxes.firstOrNull()?.rect

    fun updateBoundingBoxes(boxes: List<BoxData>) {
        boundingBoxes = boxes
        postInvalidate()
    }

    fun updateDetectedColor(colorName: String?) {
        detectedColorName = colorName
        postInvalidate()
    }

    fun updateProximity(value: Float) {
        proximity = value
        postInvalidate()
    }

    fun updateBotoesMicroondas(botoes: List<ModoMicroondas.BotaoMicroondas>) {
        botoesMicroondas = botoes
        postInvalidate()
    }

    fun detectBotaoNoToque(x: Float, y: Float): String? {
        if (appMode != AppMode.MICROONDAS) return null

        for (botao in botoesMicroondas) {
            val rectF = getScaledRect(Rect(botao.rect.left.toInt(), botao.rect.top.toInt(), botao.rect.right.toInt(), botao.rect.bottom.toInt()))
            if (rectF.contains(x, y)) {
                return botao.nome
            }
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        when (appMode) {
            AppMode.OBJETOS -> {
                val originalColor = rectPaint.color
                val originalWidth = rectPaint.strokeWidth
                rectPaint.color = Color.rgb(34, 177, 76)
                rectPaint.strokeWidth = 12f
                
                for (box in boundingBoxes) {
                    val rectF = getScaledRect(box.rect)
                    canvas.drawRoundRect(rectF, 40f, 40f, rectPaint)
                    box.label?.let { label ->
                        drawMultilineText(canvas, label, rectF.left, rectF.top - 20f)
                    }
                }
                
                if (boundingBoxes.isEmpty()) {
                    // Se não houver objetos, desenha o guia central suave
                    rectPaint.strokeWidth = 8f
                    rectPaint.alpha = 100
                    val margin = 100f
                    val guideRect = RectF(margin, margin, width.toFloat() - margin, height.toFloat() - 300f)
                    canvas.drawRoundRect(guideRect, 80f, 80f, rectPaint)
                    rectPaint.alpha = 255
                }
                
                rectPaint.color = originalColor
                rectPaint.strokeWidth = originalWidth
            }
            AppMode.TEXTO -> {
                // Removido o desenho das caixas (linhas azuis) no modo texto para um visual mais limpo
            }
            AppMode.ANDANDO -> {
                boundingBoxes.firstOrNull()?.let { boxData ->
                    val rect = boxData.rect
                    val padding = 40f
                    val roundedRect = RectF(
                        (rect.left - padding).coerceAtLeast(20f),
                        (rect.top - padding).coerceAtLeast(20f),
                        (rect.right + padding).coerceAtMost(width.toFloat() - 20f),
                        (rect.bottom + padding).coerceAtMost(height.toFloat() - 20f)
                    )
                    val originalColor = rectPaint.color
                    // Se proximity > 0.45 (aprox 1 metro dependendo da calibração da câmera), fica vermelho
                    if (proximity > 0.45f) rectPaint.color = Color.RED else rectPaint.color = Color.rgb(34, 177, 76)
                    rectPaint.strokeWidth = 18f
                    canvas.drawRoundRect(roundedRect, 80f, 80f, rectPaint)
                    rectPaint.color = originalColor
                }
            }
            AppMode.AMBIENTE -> {
                val originalColor = rectPaint.color
                val originalWidth = rectPaint.strokeWidth
                rectPaint.color = Color.rgb(34, 177, 76)
                rectPaint.strokeWidth = 12f
                for (box in boundingBoxes) {
                    val rectF = RectF(box.rect)
                    canvas.drawRoundRect(rectF, 40f, 40f, rectPaint)
                    box.label?.let { label ->
                        drawMultilineText(canvas, label, rectF.left, rectF.top - 20f)
                    }
                }
                rectPaint.color = originalColor
                rectPaint.strokeWidth = originalWidth
            }
            AppMode.COR -> {
                val centerX = width / 2f
                val centerY = height / 2f
                
                val originalColor = rectPaint.color
                val originalStyle = rectPaint.style
                
                // Desenha o ponto central (.)
                rectPaint.color = Color.WHITE
                rectPaint.style = Paint.Style.FILL
                canvas.drawCircle(centerX, centerY, 8f, rectPaint)
                
                // Desenha a mira ao redor
                rectPaint.color = Color.GREEN
                rectPaint.style = Paint.Style.STROKE
                rectPaint.strokeWidth = 4f
                canvas.drawCircle(centerX, centerY, 40f, rectPaint)

                // Desenha o nome da cor detectada embaixo da mira
                detectedColorName?.let { name ->
                    val textWidth = textPaint.measureText(name)
                    drawMultilineText(canvas, name, centerX - (textWidth / 2f), centerY + 80f)
                }
                
                rectPaint.color = originalColor
                rectPaint.style = originalStyle
            }
            AppMode.DINHEIRO -> {
                val centerX = width / 2f
                val centerY = height / 2f
                
                val originalColor = rectPaint.color
                val originalStyle = rectPaint.style
                
                // Desenha a mira exatamente igual ao modo COR
                rectPaint.color = Color.GREEN
                rectPaint.style = Paint.Style.STROKE
                rectPaint.strokeWidth = 5f
                canvas.drawCircle(centerX, centerY, 40f, rectPaint)
                
                rectPaint.style = Paint.Style.FILL
                canvas.drawCircle(centerX, centerY, 15f, rectPaint)
                
                rectPaint.color = Color.WHITE
                rectPaint.style = Paint.Style.STROKE
                rectPaint.strokeWidth = 3f
                canvas.drawCircle(centerX, centerY, 15f, rectPaint)

                // Não desenhamos mais o texto (label) nas células a pedido do usuário
                
                rectPaint.color = originalColor
                rectPaint.style = originalStyle
            }
            AppMode.MICROONDAS -> {
                val originalColor = rectPaint.color
                val originalWidth = rectPaint.strokeWidth
                rectPaint.color = Color.GREEN
                rectPaint.strokeWidth = 6f

                for (botao in botoesMicroondas) {
                    val rectF = getScaledRect(Rect(botao.rect.left.toInt(), botao.rect.top.toInt(), botao.rect.right.toInt(), botao.rect.bottom.toInt()))
                    canvas.drawRect(rectF, rectPaint)
                    // Opcional: drawText(botao.nome, rectF.left, rectF.top)
                }
                rectPaint.color = originalColor
                rectPaint.strokeWidth = originalWidth
            }
            else -> {}
        }
    }

    private fun drawMultilineText(canvas: Canvas, text: String, x: Float, y: Float) {
        if (text.isBlank()) return
        val padding = 20f
        val maxWidth = width * 0.7f
        
        // Ajusta X para não sair da tela à direita
        val textWidthForScale = textPaint.measureText(text).coerceAtMost(maxWidth)
        var currentX = x.coerceIn(padding, width - textWidthForScale - padding)
        
        // Ajusta Y para não sair do topo
        var currentY = y.coerceAtLeast(textPaint.textSize + padding)
        
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (textPaint.measureText(testLine) > maxWidth) {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                currentLine = word
            } else {
                currentLine = testLine
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)
        
        for (line in lines) {
            val lineW = textPaint.measureText(line)
            canvas.drawRect(currentX - padding, currentY - textPaint.textSize, currentX + lineW + padding, currentY + padding, backgroundPaint)
            canvas.drawText(line, currentX, currentY, textPaint)
            currentY += textPaint.textSize + 15
        }
    }
}
