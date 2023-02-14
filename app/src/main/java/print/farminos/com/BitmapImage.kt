package print.farminos.com
import android.graphics.Bitmap
import com.github.anastaciocintra.escpos.image.CoffeeImage

class BitmapImage(private val bitmap: Bitmap) : CoffeeImage {
    /**
     * Returns the width of the image
     * @see BufferedImage#getWidth()
     */
    override fun getWidth(): Int {
        return this.bitmap.width
    }

    /**
     * Returns the height of the image
     * @see BufferedImage.getHeight
     */
    override fun getHeight(): Int {
        return this.bitmap.height
    }

    /**
     * Returns a subimage defined by a specified rectangular region.
     * The returned CoffeeImage shares the same data array as the original image.
     * @param x - the X coordinate of the upper-left corner of the specified rectangular region
     * @param y - the Y coordinate of the upper-left corner of the specified rectangular region
     * @param w - the width of the specified rectangular region
     * @param h - the height of the specified rectangular region
     * @return a CoffeeImage that is the subimage of this CoffeeImage.
     * @see BufferedImage.getSubimage
     */
    override fun getSubimage(x: Int, y: Int, w: Int, h: Int): CoffeeImage {
        return BitmapImage(Bitmap.createBitmap(this.bitmap, x, y, w, h))
    }

    /**
     * Returns an integer pixel in the default RGB color model (TYPE_INT_ARGB) and default sRGB colorspace.
     * Color conversion takes place if this default model does not match the image ColorModel.
     * There are only 8-bits of precision for each color component in the returned data when using this method.
     * @param x - the X coordinate of the pixel from which to get the pixel in the default RGB color model and sRGB color space
     * @param y - the Y coordinate of the pixel from which to get the pixel in the default RGB color model and sRGB color space
     * @return an integer pixel in the default RGB color model and default sRGB colorspace.
     * @see BufferedImage.getRGB
     */
    override fun getRGB(x: Int, y: Int): Int {
        return this.bitmap.getPixel(x, y)
    }

}