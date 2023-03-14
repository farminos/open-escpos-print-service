package com.farminos.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.UiThread

fun captureWebView(webView: WebView, widthPixels: Int, heightPixels: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(
        widthPixels,
        heightPixels,
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    webView.draw(canvas)
    return bitmap
}

private fun cmToPixels(cm: Double, dpi: Int): Int {
    return (cm / 2.54 * dpi).toInt()
}

class HtmlToPdfConverter(private val context: Context) {

    private var baseUrl: String? = null
    private var enableJavascript: Boolean? = null

    fun setBaseUrl(baseUrl: String) {
        this.baseUrl = baseUrl
    }

    fun setJavaScriptEnabled(flag: Boolean) {
        this.enableJavascript = flag
    }

    @UiThread
    fun convert(
        htmlString: String,
        width: Double,
        height: Double,
        dpi: Int,
        marginMils: Int,
        onPdfGenerationFailed: PdfGenerationFailedCallback? = null,
        onPdfGenerated: PdfGeneratedCallback,
    ) {
        Log.d("WTF", "convert ${Thread.currentThread().name} $context ${android.os.Process.myPid()}")

        try {
            val pdfWebView = WebView(context)
            val widthPixels = cmToPixels(width, dpi)
            val heightPixels = cmToPixels(height, dpi)
            pdfWebView.layout(0, 0, widthPixels, heightPixels)
            //WebView.enableSlowWholeDocumentDraw()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pdfWebView.settings.safeBrowsingEnabled = false
            }

            enableJavascript?.let { pdfWebView.settings.javaScriptEnabled = it }

            pdfWebView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d("WTF", "page finished url ${pdfWebView.width} ${pdfWebView.contentHeight}")
                    val bitmap = captureWebView(pdfWebView, widthPixels, heightPixels)
                    onPdfGenerated(bitmap)
                }
            }

            // load html in WebView when it's setup is completed
            Log.d("WTF", "load data")
            pdfWebView.loadDataWithBaseURL(
                baseUrl,
                htmlString,
                "text/html",
                "utf-8",
                null,
            )
            Log.d("WTF", "after load data")
            println("load data : I'm working in thread ${Thread.currentThread().name} $context ${android.os.Process.myPid()}")

        } catch (e: Exception) {
            Log.d("WTF", "catch")
            onPdfGenerationFailed?.invoke(e)
        }
    }
}

private typealias PdfGeneratedCallback = (Bitmap) -> Unit
private typealias PdfGenerationFailedCallback = (Exception) -> Unit