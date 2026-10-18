package com.jingcun.cleaner.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Drawable → Compose ImageBitmap 转换工具。
 * 通过将 Drawable 绘制到目标尺寸的 Bitmap 上再转换，
 * 兼容 BitmapDrawable、AdaptiveIconDrawable 等所有图标类型。
 * 失败或入参为 null 时返回 null（UI 回退默认图标）。
 */
fun drawableToImageBitmap(drawable: Drawable?): ImageBitmap? {
    if (drawable == null) return null
    return runCatching {
        val sizePx = 108
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}
