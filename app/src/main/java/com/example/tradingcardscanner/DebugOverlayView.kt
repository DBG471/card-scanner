package com.example.tradingcardscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.example.tradingcardscanner.data.CardImageAnalysis

class DebugOverlayView(context: Context) : View(context) {
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 193, 7)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 76, 175, 80)
        style = Paint.Style.FILL
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 33, 150, 243)
        style = Paint.Style.FILL
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 244, 67, 54)
        style = Paint.Style.FILL
    }

    private var analysis: CardImageAnalysis? = null

    fun setAnalysis(newAnalysis: CardImageAnalysis?) {
        analysis = newAnalysis
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = analysis ?: return
        val imageWidth = current.croppedBitmap.width.toFloat()
        val imageHeight = current.croppedBitmap.height.toFloat()
        if (imageWidth <= 0f || imageHeight <= 0f) return

        val scale = minOf(width / imageWidth, height / imageHeight)
        val drawnWidth = imageWidth * scale
        val drawnHeight = imageHeight * scale
        val offsetX = (width - drawnWidth) / 2f
        val offsetY = (height - drawnHeight) / 2f

        canvas.drawRect(mapRect(current.titleRegion, scale, offsetX, offsetY), titlePaint)
        canvas.drawRect(mapRect(current.bodyRegion, scale, offsetX, offsetY), bodyPaint)
        canvas.drawRect(mapRect(current.numberRegion, scale, offsetX, offsetY), numberPaint)
        canvas.drawRect(offsetX, offsetY, offsetX + drawnWidth, offsetY + drawnHeight, cardPaint)
    }

    private fun mapRect(rect: Rect, scale: Float, offsetX: Float, offsetY: Float): android.graphics.RectF {
        return android.graphics.RectF(
            offsetX + rect.left * scale,
            offsetY + rect.top * scale,
            offsetX + rect.right * scale,
            offsetY + rect.bottom * scale
        )
    }
}
