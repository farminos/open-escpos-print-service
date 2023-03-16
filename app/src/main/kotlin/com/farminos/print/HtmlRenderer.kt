package com.farminos.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import android.webkit.WebView
import android.webkit.WebView.VisualStateCallback
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

class HtmlRenderer(
    context: Context,
    width: Double,
    height: Double,
    dpi: Int,
) {
    public val webView = WebView(context)
    private val widthPixels = cmToPixels(width, dpi)
    private val heightPixels = cmToPixels(height, dpi)

    init {
        webView.layout(0, 0, widthPixels, heightPixels)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.settings.safeBrowsingEnabled = false
            webView.settings.offscreenPreRaster = true
        }
        //webView.visibility
    }

    private fun capture(): Bitmap {
        val bitmap = Bitmap.createBitmap(
            widthPixels,
            heightPixels,
            Bitmap.Config.RGB_565,
        )
        val canvas = Canvas(bitmap)
        webView.draw(canvas)
        return bitmap
    }

    @RequiresApi(Build.VERSION_CODES.M)
    suspend fun render(content: String, id: Long): Bitmap {
        suspendCoroutine { cont ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    cont.resume(null)
                }
            }
            //Log.d("WTF", "requested $id")
            //webView.postVisualStateCallback(
            //    id,
            //    @RequiresApi(Build.VERSION_CODES.M)
            //    object : VisualStateCallback() {
            //        override fun onComplete(p0: Long) {
            //            Log.d("WTF", "completed $p0")
            //            cont.resume(null)
            //        }
            //    }
            //)
            webView.loadDataWithBaseURL(
                null,
                content,
                "text/html",
                "utf-8",
                null,
            )
        }
        Thread.sleep(3000)
        return capture();
    }
}