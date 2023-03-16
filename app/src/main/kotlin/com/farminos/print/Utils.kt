package com.farminos.print

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.math.ceil

@Throws(IOException::class)
fun decompress(compressed: ByteArray?): String {
    val bufferSize = 32
    val inputStream = ByteArrayInputStream(compressed)
    val gis = GZIPInputStream(inputStream, bufferSize)
    val builder = StringBuilder()
    val data = ByteArray(bufferSize)
    var bytesRead: Int
    while (gis.read(data).also { bytesRead = it } != -1) {
        builder.append(String(data, 0, bytesRead))
    }
    gis.close()
    inputStream.close()
    return builder.toString()
}

fun convertTransparentToWhite(bitmap: Bitmap) {
    val pixels = IntArray(bitmap.height * bitmap.width)
    bitmap.getPixels(
        pixels,
        0,
        bitmap.width,
        0,
        0,
        bitmap.width,
        bitmap.height
    )
    for (j in pixels.indices) {
        if (pixels[j] == Color.TRANSPARENT) {
            pixels[j] = Color.WHITE
        }
    }
    bitmap.setPixels(
        pixels,
        0,
        bitmap.width,
        0,
        0,
        bitmap.width,
        bitmap.height
    )
}

fun pdfToBitmaps(document: ParcelFileDescriptor, dpi: Int, w: Double, h: Double) = sequence<Bitmap> {
    // TODO: On receipt printers: truncate each page once only white or transparent pixels remain.
    val renderer = PdfRenderer(document)
    val pageCount = renderer.pageCount
    for (i in 0 until pageCount) {
        val width = cmToDots(w, dpi)
        val height = cmToDots(h, dpi)
        val page = renderer.openPage(i)
        val transform = Matrix()
        val ratio = width.toFloat() / page.width
        transform.postScale(ratio, ratio)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        convertTransparentToWhite(bitmap)
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        yield(bitmap)
        page.close()
    }
    renderer.close()
}

fun bitmapSlices(bitmap: Bitmap, step: Int) = sequence<Bitmap> {
    val width: Int = bitmap.width
    val height: Int = bitmap.height
    for (y in 0 until height step step) {
        val slice = Bitmap.createBitmap(
            bitmap,
            0,
            y,
            width,
            if (y + step >= height) height - y else step
        )
        yield(slice)
    }
}

private fun cmToDots(cm: Double, dpi: Int): Int {
    return ceil((cm / 2.54) * dpi).toInt()
}

fun cmToMils(cm: Double): Int {
    return ceil(cm / 2.54 * 1000).toInt()
}

fun milsToCm(mils: Int): Double {
    return mils / 1000 * 2.54
}

fun cmToPixels(cm: Double, dpi: Int): Int {
    return (cm / 2.54 * dpi).toInt()
}

fun pixelsToCm(pixels: Int, dpi: Int): Double {
    return pixels.toDouble() / dpi * 2.54
}