package com.farminos.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.math.ceil
import kotlin.math.min

@Throws(IOException::class)
fun decompress(compressed: ByteArray?): String {
    val bufferSize = 32
    val builder = StringBuilder()
    val data = ByteArray(bufferSize)
    var bytesRead: Int
    ByteArrayInputStream(compressed).use { inputStream ->
        GZIPInputStream(inputStream, bufferSize).use { gis ->
            while (gis.read(data).also { bytesRead = it } != -1) {
                builder.append(String(data, 0, bytesRead))
            }
        }
    }
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

fun pdfToBitmaps(
    document: ParcelFileDescriptor,
    dpi: Int,
    w: Float,
    h: Float,
) = sequence<Bitmap> {
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

fun bitmapSlices(
    bitmap: Bitmap,
    step: Int,
) = sequence<Bitmap> {
    val width: Int = bitmap.width
    val height: Int = bitmap.height
    for (y in 0 until height step step) {
        val slice =
            Bitmap.createBitmap(
                bitmap,
                0,
                y,
                width,
                if (y + step >= height) height - y else step,
            )
        yield(slice)
    }
}

data class Tile(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

fun bitmapTiles(
    bitmap: Bitmap,
    tileWidth: Int,
    tileHeight: Int,
) = sequence<Tile> {
    for (y in 0 until bitmap.height step tileHeight) {
        for (x in 0 until bitmap.width step tileWidth) {
            val width = min(tileWidth, bitmap.width - x)
            val height = min(tileHeight, bitmap.height - y)
            yield(Tile(x, y, width, height))
        }
    }
}

fun bitmapRegionIsWhite(
    bitmap: Bitmap,
    tile: Tile,
): Boolean {
    val pixels = IntArray(tile.width * tile.height)
    bitmap.getPixels(pixels, 0, tile.width, tile.x, tile.y, tile.width, tile.height)
    for (pixel in pixels) {
        if (pixel != Color.WHITE) {
            return false
        }
    }
    return true
}

fun bitmapNonEmptyTiles(
    bitmap: Bitmap,
    tileSize: Int,
) = sequence<Tile> {
    for (tile in bitmapTiles(bitmap, tileSize, tileSize)) {
        if (!bitmapRegionIsWhite(bitmap, tile)) {
            yield(tile)
        }
    }
}

fun bitmapLastNonWhiteLine(bitmap: Bitmap): Int {
    var lastNonWhiteLine = 0
    for ((index, line) in bitmapTiles(bitmap, bitmap.width, 1).withIndex()) {
        if (!bitmapRegionIsWhite(bitmap, line)) {
            lastNonWhiteLine = index
        }
    }
    return lastNonWhiteLine
}

fun bitmapCropWhiteEnd(bitmap: Bitmap): Bitmap {
    val lastNonWhiteLine = bitmapLastNonWhiteLine(bitmap)
    val height = lastNonWhiteLine + 1
    if (bitmap.height == height) {
        return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, height)
}

fun copyToTmpFile(
    cacheDir: File,
    fd: FileDescriptor,
): ParcelFileDescriptor {
    val outputFile = File.createTempFile(System.currentTimeMillis().toString(), null, cacheDir)
    val buffer = ByteArray(8192)
    var length: Int
    FileOutputStream(outputFile).use { outputStream ->
        FileInputStream(fd).use { inputStream ->
            while (inputStream.read(buffer).also { length = it } > 0) {
                outputStream.write(buffer, 0, length)
            }
        }
    }
    return ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_ONLY)
}

fun addMargins(
    bitmap: Bitmap,
    marginLeftPx: Int,
    marginTopPx: Int,
    marginRightPx: Int,
    marginBottomPx: Int,
): Bitmap {
    val result =
        Bitmap.createBitmap(
            marginLeftPx + bitmap.width + marginRightPx,
            marginTopPx + bitmap.height + marginBottomPx,
            bitmap.config ?: Bitmap.Config.ARGB_8888,
        )
    result.eraseColor(Color.WHITE)
    val canvas = Canvas(result)
    canvas.drawBitmap(bitmap, marginLeftPx.toFloat(), marginTopPx.toFloat(), Paint())
    return result
}

private const val INCH = 2.54F

private fun cmToDots(
    cm: Float,
    dpi: Int,
): Int = ceil((cm / INCH) * dpi).toInt()

fun cmToMils(cm: Float): Int = ceil(cm / INCH * 1000).toInt()

fun cmToPixels(
    cm: Float,
    dpi: Int,
): Int = (cm / INCH * dpi).toInt()

fun pixelsToCm(
    pixels: Int,
    dpi: Int,
): Float = pixels.toFloat() / dpi * INCH

fun scaleBitmap(
    bitmap: Bitmap,
    printerSettings: PrinterSettings,
): Bitmap {
    val width = printerSettings.width
    val marginLeft = printerSettings.marginLeft
    val marginTop = printerSettings.marginTop
    val marginRight = printerSettings.marginRight
    val marginBottom = printerSettings.marginBottom
    val dpi = printerSettings.dpi
    val widthPx = cmToPixels(width, dpi)
    val marginLeftPx = cmToPixels(marginLeft, dpi)
    val marginTopPx = cmToPixels(marginTop, dpi)
    val marginRightPx = cmToPixels(marginRight, dpi)
    val marginBottomPx = cmToPixels(marginBottom, dpi)
    val renderWidthPx = widthPx - marginLeftPx - marginRightPx
    val ratio = renderWidthPx.toFloat() / bitmap.width
    val resizedBitmap = Bitmap.createScaledBitmap(bitmap, renderWidthPx, (bitmap.height * ratio).toInt(), true)
    return if (marginLeftPx == 0 && marginTopPx == 0 && marginRightPx == 0 && marginBottomPx == 0) {
        resizedBitmap
    } else {
        addMargins(resizedBitmap, marginLeftPx, marginTopPx, marginRightPx, marginBottomPx)
    }
}

fun rotateBitmap(
    bitmap: Bitmap,
    orientation: Int,
): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> {
            matrix.postRotate(90f)
            matrix.postTranslate(bitmap.height.toFloat(), 0f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postScale(-1f, 1f)
            matrix.postTranslate(bitmap.width.toFloat(), 0f)
            matrix.postRotate(90f)
            matrix.postTranslate(bitmap.height.toFloat(), 0f)
        }
        ExifInterface.ORIENTATION_ROTATE_180 -> {
            matrix.postRotate(180f)
            matrix.postTranslate(bitmap.width.toFloat(), bitmap.height.toFloat())
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> {
            matrix.postRotate(270f)
            matrix.postTranslate(0f, bitmap.width.toFloat())
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postScale(-1f, 1f)
            matrix.postTranslate(bitmap.width.toFloat(), 0f)
            matrix.postRotate(270f)
            matrix.postTranslate(0f, bitmap.width.toFloat())
        }
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
            matrix.postScale(-1f, 1f)
            matrix.postTranslate(bitmap.width.toFloat(), 0f)
        }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.postScale(1f, -1f)
            matrix.postTranslate(0f, bitmap.height.toFloat())
        }
    }
    val rotatedRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    matrix.mapRect(rotatedRect)
    val newWidth = rotatedRect.width().toInt()
    val newHeight = rotatedRect.height().toInt()
    return Bitmap.createBitmap(newWidth, newHeight, bitmap.config ?: Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.concat(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
