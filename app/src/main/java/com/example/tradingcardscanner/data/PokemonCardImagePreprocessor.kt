package com.example.tradingcardscanner.data

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CardImageAnalysis(
    val croppedBitmap: Bitmap,
    val cardBoundsInOriginal: Rect,
    val titleRegion: Rect,
    val bodyRegion: Rect,
    val numberRegion: Rect,
    val usedFallbackCrop: Boolean
)

class PokemonCardImagePreprocessor {
    fun prepare(bitmap: Bitmap): CardImageAnalysis {
        val detectedBounds = detectCardBounds(bitmap)
        val bounds = detectedBounds ?: fallbackCardBounds(bitmap)
        val cropped = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
        val width = cropped.width
        val height = cropped.height

        return CardImageAnalysis(
            croppedBitmap = cropped,
            cardBoundsInOriginal = bounds,
            titleRegion = Rect(0, 0, width, (height * TITLE_REGION_RATIO).toInt()),
            bodyRegion = Rect(0, (height * TITLE_REGION_RATIO).toInt(), width, (height * NUMBER_REGION_TOP_RATIO).toInt()),
            numberRegion = Rect(0, (height * NUMBER_REGION_TOP_RATIO).toInt(), width, height),
            usedFallbackCrop = detectedBounds == null
        )
    }

    private fun detectCardBounds(bitmap: Bitmap): Rect? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 80 || height < 80) return null

        val borderColor = averageBorderColor(bitmap)
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        var hits = 0
        val step = max(4, min(width, height) / 180)

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (colorDistance(bitmap.getPixel(x, y), borderColor) > FOREGROUND_DISTANCE) {
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                    hits++
                }
                x += step
            }
            y += step
        }

        if (hits < 50 || right <= left || bottom <= top) return null

        val padding = min(width, height) / 60
        val rect = Rect(
            (left - padding).coerceAtLeast(0),
            (top - padding).coerceAtLeast(0),
            (right + padding).coerceAtMost(width - 1),
            (bottom + padding).coerceAtMost(height - 1)
        )

        val areaRatio = rect.width().toFloat() * rect.height().toFloat() / (width.toFloat() * height.toFloat())
        val aspectRatio = rect.width().toFloat() / rect.height().toFloat()
        if (areaRatio !in MIN_CARD_AREA_RATIO..MAX_CARD_AREA_RATIO) return null
        if (aspectRatio !in MIN_CARD_ASPECT_RATIO..MAX_CARD_ASPECT_RATIO) return null

        return rect
    }

    private fun fallbackCardBounds(bitmap: Bitmap): Rect {
        val width = bitmap.width
        val height = bitmap.height
        val targetAspect = CARD_ASPECT_RATIO
        val cropWidth: Int
        val cropHeight: Int

        if (width.toFloat() / height > targetAspect) {
            cropHeight = (height * 0.92f).toInt()
            cropWidth = (cropHeight * targetAspect).toInt()
        } else {
            cropWidth = (width * 0.92f).toInt()
            cropHeight = (cropWidth / targetAspect).toInt().coerceAtMost(height)
        }

        val left = ((width - cropWidth) / 2).coerceAtLeast(0)
        val top = ((height - cropHeight) / 2).coerceAtLeast(0)
        return Rect(left, top, (left + cropWidth).coerceAtMost(width), (top + cropHeight).coerceAtMost(height))
    }

    private fun averageBorderColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val insetX = max(1, width / 30)
        val insetY = max(1, height / 30)
        val points = listOf(
            insetX to insetY,
            width - insetX - 1 to insetY,
            insetX to height - insetY - 1,
            width - insetX - 1 to height - insetY - 1,
            width / 2 to insetY,
            width / 2 to height - insetY - 1
        )

        var red = 0
        var green = 0
        var blue = 0
        points.forEach { (x, y) ->
            val color = bitmap.getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
            red += color.red()
            green += color.green()
            blue += color.blue()
        }
        return rgb(red / points.size, green / points.size, blue / points.size)
    }

    private fun colorDistance(left: Int, right: Int): Int {
        return abs(left.red() - right.red()) +
            abs(left.green() - right.green()) +
            abs(left.blue() - right.blue())
    }

    private fun Int.red(): Int = this shr 16 and 0xFF
    private fun Int.green(): Int = this shr 8 and 0xFF
    private fun Int.blue(): Int = this and 0xFF
    private fun rgb(red: Int, green: Int, blue: Int): Int = (red shl 16) or (green shl 8) or blue

    private companion object {
        const val CARD_ASPECT_RATIO = 0.716f
        const val TITLE_REGION_RATIO = 0.24f
        const val NUMBER_REGION_TOP_RATIO = 0.72f
        const val FOREGROUND_DISTANCE = 82
        const val MIN_CARD_AREA_RATIO = 0.22f
        const val MAX_CARD_AREA_RATIO = 0.96f
        const val MIN_CARD_ASPECT_RATIO = 0.52f
        const val MAX_CARD_ASPECT_RATIO = 0.86f
    }
}
