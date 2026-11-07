package com.farminos.print

import java.util.BitSet

class BinaryImage(
    val width: Int,
    val height: Int,
    private val pixels: BitSet = BitSet(width * height),
    private val yOffset: Int = 0,
) {
    companion object {
        const val BLACK = false
        const val WHITE = true
    }

    val bytesPerLine: Int
        get() = (width + 7) / 8

    private fun position(
        x: Int,
        y: Int,
    ): Int {
        require(x in 0 until width && y in 0 until height)
        return (yOffset + y) * width + x
    }

    operator fun get(
        x: Int,
        y: Int,
    ): Boolean = pixels[position(x, y)]

    operator fun set(
        x: Int,
        y: Int,
        value: Boolean,
    ) {
        pixels.set(position(x, y), value)
    }

    fun slice(
        yOffset: Int,
        height: Int,
    ): BinaryImage {
        require(yOffset in 0 until this.height)
        require(height in 1..(this.height - yOffset))
        return BinaryImage(
            this.width,
            height,
            this.pixels,
            this.yOffset + yOffset,
        )
    }

    fun slices(step: Int): Sequence<BinaryImage> =
        sequence {
            require(step > 0)
            var y = 0
            while (y < height) {
                val sliceHeight = minOf(step, height - y)
                yield(slice(y, sliceHeight))
                y += step
            }
        }

    fun pack(inverted: Boolean = false): ByteArray {
        val result =
            ByteArray(bytesPerLine * height) {
                if (inverted) 0 else 0xFF.toByte() // 0xFF, so padding stays blank
            }
        var i = 0
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                for (k in 0 until 8) {
                    val posX = x + k
                    if (posX >= width) {
                        break
                    }
                    val mask = 1 shl (7 - k)
                    if (get(posX, y) != inverted) {
                        result[i] = (result[i].toInt() or mask).toByte()
                    } else {
                        result[i] = (result[i].toInt() and mask.inv()).toByte()
                    }
                }
                i++
                x += 8
            }
        }
        return result
    }
}
