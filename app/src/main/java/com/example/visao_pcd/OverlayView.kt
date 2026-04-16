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
    private var detectedMoneyValue: String? = null
    private var appMode: AppMode = AppMode.NENHUM
    private var proximity: Float = 0f

    private var imageWidth = 0
    private var imageHeight = 0
    private var rotationDegrees = 0
    
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f

    data class BoxData(val rect: Rect, val label: String?)

    fun setImageSourceInfo(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
        updateScales()
    }

    private fun updateScales() {
        if (imageWidth > 0 && imageHeight > 0 && width > 0 && height > 0) {
            scaleX = width.toFloat() / imageWidth
            scaleY = height.toFloat() / imageHeight
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateScales()
    }

    fun setRotation(degrees: Int) {
        rotationDegrees = degrees
        postInvalidate()
    }

    fun getScaledRect(rect: Rect): RectF {
        return RectF(
            rect.left * scaleX,
            rect.top * scaleY,
            rect.right * scaleX,
            rect.bottom * scaleY
        )
    }

    fun getScaledRectForAnalysis(rectF: RectF): RectF {
        val rect = Rect(rectF.left.toInt(), rectF.top.toInt(), rectF.right.toInt(), rectF.bottom.toInt())
        return getScaledRect(rect)
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

    fun updateDetectedMoney(moneyValue: String?) {
        detectedMoneyValue = moneyValue
        postInvalidate()
    }

    fun updateProximity(value: Float) {
        proximity = value
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        when (appMode) {
            AppMode.OBJETOS -> {
                val originalColor = rectPaint.color
                val originalWidth = rectPaint.strokeWidth
                val originalAlpha = rectPaint.alpha
                
                rectPaint.color = Color.rgb(34, 177, 76)
                rectPaint.strokeWidth = 12f
                rectPaint.alpha = 255
                
                for (box in boundingBoxes) {
                    val rectF = getScaledRect(box.rect)
                    canvas.drawRoundRect(rectF, 40f, 40f, rectPaint)
                    box.label?.let { label ->
                        drawMultilineText(canvas, label, rectF.left, rectF.top - 20f)
                    }
                }
                
                rectPaint.color = originalColor
                rectPaint.strokeWidth = originalWidth
                rectPaint.alpha = originalAlpha
            }
            AppMode.TEXTO -> {
            }
            AppMode.AMBIENTE -> {
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
                rectPaint.color = originalColor
                rectPaint.strokeWidth = originalWidth
            }
            AppMode.COR -> {
                val centerX = width / 2f
                val centerY = height / 2f
                
                val originalColor = rectPaint.color
                val originalStyle = rectPaint.style
                
                rectPaint.color = Color.WHITE
                rectPaint.style = Paint.Style.FILL
                canvas.drawCircle(centerX, centerY, 8f, rectPaint)
                
                rectPaint.color = Color.GREEN
                rectPaint.style = Paint.Style.STROKE
                rectPaint.strokeWidth = 4f
                canvas.drawCircle(centerX, centerY, 40f, rectPaint)

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

                detectedMoneyValue?.let { value ->
                    val textWidth = textPaint.measureText(value.uppercase())
                    drawMultilineText(canvas, value.uppercase(), centerX - (textWidth / 2f), centerY + 80f)
                }
                
                rectPaint.color = originalColor
                rectPaint.style = originalStyle
            }
            AppMode.MICROONDAS -> {
                val originalColor = rectPaint.color
                val originalWidth = rectPaint.strokeWidth
                rectPaint.color = Color.rgb(34, 177, 76) // Verde padrão
                rectPaint.strokeWidth = 10f
                
                for (box in boundingBoxes) {
                    val rectF = getScaledRect(box.rect)
                    canvas.drawRoundRect(rectF, 15f, 15f, rectPaint)
                    box.label?.let { label ->
                        drawMultilineText(canvas, label, rectF.left, rectF.top - 10f)
                    }
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
        
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""
        var maxLineW = 0f
        
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val testW = textPaint.measureText(testLine)
            if (testW > maxWidth) {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                    maxLineW = maxOf(maxLineW, textPaint.measureText(currentLine))
                }
                currentLine = word
            } else {
                currentLine = testLine
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
            maxLineW = maxOf(maxLineW, textPaint.measureText(currentLine))
        }

        // Ajusta X para manter o texto na tela com base na linha mais longa
        val currentX = x.coerceIn(padding, width - maxLineW - padding)
        
        // Ajusta Y para garantir que o bloco de texto caia dentro da tela
        val lineHeight = textPaint.textSize + 15f
        val totalHeight = lines.size * lineHeight
        var drawY = y
        if (drawY - textPaint.textSize < padding) drawY = textPaint.textSize + padding
        if (drawY + totalHeight > height - padding) drawY = height - totalHeight - padding
        
        for (line in lines) {
            val lineW = textPaint.measureText(line)
            canvas.drawRect(
                currentX - padding, 
                drawY - textPaint.textSize, 
                currentX + lineW + padding, 
                drawY + padding / 2, 
                backgroundPaint
            )
            canvas.drawText(line, currentX, drawY, textPaint)
            drawY += lineHeight
        }
    }
}
