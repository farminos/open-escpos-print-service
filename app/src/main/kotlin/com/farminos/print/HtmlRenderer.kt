package com.farminos.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

class HtmlRenderer(
    context: Context,
    width: Double,
    height: Double,
    dpi: Int,
) {
    private val webView = WebView(context)
    private val widthPixels = cmToPixels(width, dpi)
    private val heightPixels = cmToPixels(height, dpi)

    init {
        webView.layout(0, 0, widthPixels, heightPixels)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.settings.safeBrowsingEnabled = false
        }
    }

    private fun capture(): Bitmap {
        val bitmap = Bitmap.createBitmap(
            widthPixels,
            heightPixels,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        webView.draw(canvas)
        return bitmap
    }

    suspend fun render(content: String): Bitmap {
        suspendCoroutine { cont ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    cont.resume(null)
                }
            }
            webView.loadDataWithBaseURL(
                null,
                content,
                "text/html",
                "utf-8",
                null,
            )
        }
        return capture();
    }
}