package com.farminos.print

import android.content.Context
import android.os.Build
import android.print.PdfPrinter
import android.print.PrintAttributes
import android.print.PrintAttributes.Resolution
import android.print.PrintDocumentAdapter
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.UiThread
import java.io.File

// TODO: We could probably just screenshot the WebView and skip the pdf generation part
//  (unless we need to print several pages)

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
        pdfLocation: File,
        htmlString: String,
        width: Double,
        height: Double,
        dpi: Int,
        onPdfGenerationFailed: PdfGenerationFailedCallback? = null,
        onPdfGenerated: PdfGeneratedCallback,
    ) {

        Log.d("WTF", "convert ${Thread.currentThread().name} $context ${android.os.Process.myPid()}")

        // maintain pdf generation status
        var pdfGenerationStarted = false
        try {

            // create new webview
            val pdfWebView = WebView(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pdfWebView.settings.safeBrowsingEnabled = false
            }

            // set webview enable/ disable javascript property
            enableJavascript?.let { pdfWebView.settings.javaScriptEnabled = it }

            // job name
            val jobName = Math.random().toString()

            // generate pdf attributes and properties
            val attributes = getPrintAttributes(width, height, dpi)

            // generate print document adapter
            val printAdapter = getPrintAdapter(pdfWebView, jobName)

            pdfWebView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Page is done loading;
                    Log.d("WTF", "page finished url ${pdfWebView.width} ${pdfWebView.contentHeight}")
                }
            }

            pdfWebView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    Log.d("WTF", "progress $newProgress")

                    // some times progress provided by this method is wrong, that's why we are getting progress directly provided by WebView
                    val progress = pdfWebView.progress


                    // when page is fully loaded, start creating PDF
                    if (progress == 100 && !pdfGenerationStarted) {

                        // change the status of pdf generation
                        pdfGenerationStarted = true

                        // generate pdf
                        val pdfPrinter = PdfPrinter(attributes)
                        pdfPrinter.generate(printAdapter, pdfLocation, onPdfGenerated)
                    }
                }
            }

            // load html in WebView when it's setup is completed
            Log.d("WTF", "load data")
            pdfWebView.loadDataWithBaseURL(baseUrl, htmlString, "text/html", "utf-8", null)
            Log.d("WTF", "after load data")
            println("load data : I'm working in thread ${Thread.currentThread().name} $context ${android.os.Process.myPid()}")

        } catch (e: Exception) {
            Log.d("WTF", "catch")
            onPdfGenerationFailed?.invoke(e)
        }
    }


    private fun getPrintAdapter(pdfWebView: WebView, jobName: String): PrintDocumentAdapter {
        return pdfWebView.createPrintDocumentAdapter(jobName)
    }

    private fun getPrintAttributes(width: Double, height: Double, dpi: Int): PrintAttributes {
        val size = PrintAttributes.MediaSize(
            "pdf",
            "pdf",
            cmToMils(width),
            cmToMils(height),
        )
        return PrintAttributes.Builder().apply {
            setMediaSize(size)
            setResolution(Resolution("pdf", Context.PRINT_SERVICE, dpi, dpi))
            setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        }.build()
    }
}

private typealias PdfGeneratedCallback = (File) -> Unit
private typealias PdfGenerationFailedCallback = (Exception) -> Unit