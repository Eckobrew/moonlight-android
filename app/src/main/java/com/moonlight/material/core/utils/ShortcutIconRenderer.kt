package com.moonlight.material.core.utils

import android.graphics.*
import kotlin.math.max
import kotlin.math.min

enum class ShortcutIconStyle(val label: String) { SQUIRCLE("Squircle"), CIRCLE("Circle"), CUTOUT("Logo Cutout"), BOX_ART("Box Art") }
enum class ShortcutFillMode(val label: String) { CROP("Crop to Fill"), FIT("Fit Inside") }
enum class ShortcutBackdrop(val label: String) { BLURRED("Blurred Art"), DARK("Dark"), BLACK("Black"), NONE("None") }

object ShortcutIconRenderer {
    fun render(image: Bitmap, style: ShortcutIconStyle, fill: ShortcutFillMode, backdrop: ShortcutBackdrop, size: Int = 512): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val extent = size * if (style == ShortcutIconStyle.CIRCLE) 0.824f else 0.84f
        val bounds = RectF((size - extent) / 2, (size - extent) / 2, (size + extent) / 2, (size + extent) / 2)
        fun draw(bitmap: Bitmap, area: RectF, crop: Boolean) {
            val scale = if (crop) max(area.width() / bitmap.width, area.height() / bitmap.height)
                else min(area.width() / bitmap.width, area.height() / bitmap.height)
            val w = bitmap.width * scale; val h = bitmap.height * scale
            canvas.drawBitmap(bitmap, null, RectF(area.centerX() - w / 2, area.centerY() - h / 2,
                area.centerX() + w / 2, area.centerY() + h / 2), paint)
        }
        if (style == ShortcutIconStyle.CUTOUT || style == ShortcutIconStyle.BOX_ART) {
            draw(image, bounds, false)
            return output
        }
        val clip = Path().apply {
            if (style == ShortcutIconStyle.CIRCLE) addOval(bounds, Path.Direction.CW)
            else addRoundRect(bounds, size * 0.19f, size * 0.19f, Path.Direction.CW)
        }
        canvas.save(); canvas.clipPath(clip)
        when (backdrop) {
            ShortcutBackdrop.BLACK -> canvas.drawColor(Color.BLACK)
            ShortcutBackdrop.DARK -> canvas.drawColor(Color.rgb(36, 36, 41))
            ShortcutBackdrop.BLURRED -> {
                canvas.drawColor(Color.rgb(31, 31, 36))
                val soft = Bitmap.createScaledBitmap(image, 24, 24, true)
                paint.alpha = 190; draw(soft, bounds, true); paint.alpha = 255
                if (soft !== image) soft.recycle()
            }
            ShortcutBackdrop.NONE -> Unit
        }
        val imageBounds = RectF(bounds)
        if (fill == ShortcutFillMode.FIT) imageBounds.inset(extent * 0.05f, extent * 0.05f)
        draw(image, imageBounds, fill == ShortcutFillMode.CROP)
        canvas.restore()
        return output
    }
}
