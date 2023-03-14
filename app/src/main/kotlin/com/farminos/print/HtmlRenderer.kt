package com.farminos.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import kotlinx.coroutines.*
import kotlinx.coroutines.GlobalScope.coroutineContext
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

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

fun foo(lambda: () -> Unit) {
    //...do something heavy
    lambda()
}

fun bar(completion: () -> Unit) {
    GlobalScope.launch(Dispatchers.IO) {
        suspendCoroutine<Unit> {
            val lambda = {
                it.resume(Unit)
            }
            foo(lambda)
        }
        withContext(Dispatchers.Main) {
            completion()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
suspend fun renderHtml(
    context: Context,
    content: String,
    width: Double,
    height: Double,
    dpi: Int,
) = withContext(Dispatchers.Main) {
    val pageLoadJob = Job()
    val pageLoadScope = CoroutineScope(coroutineContext + pageLoadJob)
    val webView = WebView(context)
    val widthPixels = cmToPixels(width, dpi)
    val heightPixels = cmToPixels(height, dpi)
    webView.layout(0, 0, widthPixels, heightPixels)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        webView.settings.safeBrowsingEnabled = false
    }
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            Log.d("WTF", "page finished url ${webView.width} ${webView.contentHeight}")
            val bitmap = captureWebView(webView, widthPixels, heightPixels)
            //continuation.resume(bitmap)
            //callback(bitmap)
            //pageLoadJob.complete()
            pageLoadScope.launch {
                pageLoadJob.complete()
            }
        }
    }
    Log.d("WTF", "load data 0")
    webView.loadDataWithBaseURL(
        null,
        content,
        "text/html",
        "utf-8",
        null,
    )
    Log.d("WTF", "load data 1")
    pageLoadJob.join()
}

class HtmlRenderer(private val context: Context) {

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
        // TODO: implement margins
        marginMils: Int,
        onPdfGenerationFailed: (Exception) -> Unit,
        onPdfGenerated: (Bitmap) -> Unit,
    ) {
        Log.d("WTF", "convert ${Thread.currentThread().name} $context ${android.os.Process.myPid()}")

        try {
            val pdfWebView = WebView(context)
            val widthPixels = cmToPixels(width, dpi)
            val heightPixels = cmToPixels(height, dpi)
            pdfWebView.layout(0, 0, widthPixels, heightPixels)
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