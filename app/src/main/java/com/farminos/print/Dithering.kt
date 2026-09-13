package com.farminos.print

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set

fun ditherGradient(bitmap: Bitmap): Bitmap {
    // Dithering logic from com.dantsu.escposprinter.EscPosPrinterCommands
    val width = bitmap.width
    val height = bitmap.height
    val output = createBitmap(width, height)
    var greyscaleCoefficientInit = 0
    val gradientStep = 6
    val colorLevelStep = 765.0 / (15 * gradientStep + gradientStep - 1)
    for (y in 0 until height) {
        var greyscaleCoefficient = greyscaleCoefficientInit
        val greyscaleLine = y % gradientStep
        for (x in 0 until width) {
            val color = bitmap[x, y]
            val red = (color shr 16) and 255
            val green = (color shr 8) and 255
            val blue = color and 255
            val isBlack =
                (red + green + blue) <
                    ((greyscaleCoefficient * gradientStep + greyscaleLine) * colorLevelStep)
            output[x, y] =
                if (isBlack) {
                    Color.BLACK
                } else {
                    Color.WHITE
                }
            greyscaleCoefficient += 5
            if (greyscaleCoefficient > 15) {
                greyscaleCoefficient -= 16
            }
        }
        greyscaleCoefficientInit += 2
        if (greyscaleCoefficientInit > 15) {
            greyscaleCoefficientInit = 0
        }
    }
    return output
}

fun getLuminanceData(bitmap: Bitmap): DoubleArray {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = DoubleArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val color = bitmap[x, y]
            val red = (color shr 16) and 255
            val green = (color shr 8) and 255
            val blue = color and 255
            pixels[y * width + x] = 0.299 * red + 0.587 * green + 0.114 * blue
        }
    }
    return pixels
}

fun ditherFloydSteinberg(bitmap: Bitmap): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val output = createBitmap(width, height)
    // Work with grayscale values so error diffusion can modify them.
    val pixels = getLuminanceData(bitmap)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            val oldPixel = pixels[index]
            val newPixel = if (oldPixel < 128.0) 0.0 else 255.0
            val error = oldPixel - newPixel
            output[x, y] = if (newPixel == 0.0) {
                Color.BLACK
            } else {
                Color.WHITE
            }
            // Floyd-Steinberg error diffusion:
            //
            //             X     7/16
            //       3/16  5/16  1/16
            //
            if (x + 1 < width) {
                pixels[index + 1] += error * 7.0 / 16.0
            }
            if (y + 1 < height) {
                if (x > 0) {
                    pixels[index + width - 1] += error * 3.0 / 16.0
                }
                pixels[index + width] += error * 5.0 / 16.0
                if (x + 1 < width) {
                    pixels[index + width + 1] += error * 1.0 / 16.0
                }
            }
        }
    }
    return output
}

fun ditherAtkinson(bitmap: Bitmap): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val output = createBitmap(width, height)
    val pixels = getLuminanceData(bitmap)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            val oldPixel = pixels[index]
            val newPixel = if (oldPixel < 128.0) 0.0 else 255.0
            val error = oldPixel - newPixel
            output[x, y] = if (newPixel == 0.0) {
                Color.BLACK
            } else {
                Color.WHITE
            }
            // Atkinson dithering:
            //
            //             X  1
            //          1  1  1
            //             1  1
            //          1/8 of the error to each neighbor
            //
            //         X  1  1
            //      1  1  1
            //         1  1
            val errorPart = error / 8.0
            if (x + 1 < width) {
                pixels[index + 1] += errorPart
            }
            if (x + 2 < width) {
                pixels[index + 2] += errorPart
            }
            if (y + 1 < height) {
                if (x > 0) {
                    pixels[index + width - 1] += errorPart
                }
                pixels[index + width] += errorPart
                if (x + 1 < width) {
                    pixels[index + width + 1] += errorPart
                }
            }
            if (y + 2 < height) {
                pixels[index + width * 2] += errorPart
            }
        }
    }
    return output
}