package com.farminos.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
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
        bitmap.height,
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
        bitmap.height,
    )
}

fun pdfToBitmaps(document: ParcelFileDescriptor, dpi: Int, w: Float, h: Float) = sequence<Bitmap> {
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
            if (y + step >= height) height - y else step,
        )
        yield(slice)
    }
}

fun copyToTmpFile(cacheDir: File, fd: FileDescriptor): ParcelFileDescriptor {
    val outputFile = File.createTempFile(System.currentTimeMillis().toString(), null, cacheDir)
    val outputStream = FileOutputStream(outputFile)
    val inputStream = FileInputStream(fd)
    val buffer = ByteArray(8192)
    var length: Int
    while (inputStream.read(buffer).also { length = it } > 0) {
        outputStream.write(buffer, 0, length)
    }
    inputStream.close()
    outputStream.close()
    return ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_ONLY)
}

fun addMargins(
    bitmap: Bitmap,
    marginLeftPx: Int,
    marginTopPx: Int,
    marginRightPx: Int,
    marginBottomPx: Int,
): Bitmap {
    val result = Bitmap.createBitmap(
        marginLeftPx + bitmap.width + marginRightPx,
        marginTopPx + bitmap.height + marginBottomPx,
        bitmap.config,
    )
    result.eraseColor(Color.WHITE)
    val canvas = Canvas(result)
    canvas.drawBitmap(bitmap, marginLeftPx.toFloat(), marginTopPx.toFloat(), Paint())
    return result
}

private const val INCH = 2.54F

private fun cmToDots(cm: Float, dpi: Int): Int {
    return ceil((cm / INCH) * dpi).toInt()
}

fun cmToMils(cm: Float): Int {
    return ceil(cm / INCH * 1000).toInt()
}

fun cmToPixels(cm: Float, dpi: Int): Int {
    return (cm / INCH * dpi).toInt()
}

fun pixelsToCm(pixels: Int, dpi: Int): Float {
    return pixels.toFloat() / dpi * INCH
}
