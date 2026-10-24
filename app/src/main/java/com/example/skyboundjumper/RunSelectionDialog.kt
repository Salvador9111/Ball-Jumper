package com.example.skyboundjumper

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.webkit.*

class RunSelectionDialog(private val activity: Activity, choose: (Int) -> Unit) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
    private val web = WebView(activity)
    private var ready=false
    private var pending: String?=null
    private var disposed=false
    fun present(snapshot: String) {
        pending=snapshot
        if(ready) displayPrepared()
    }
    fun cancelPending() { pending=null }
    private fun displayPrepared() {
        val snapshot=pending ?: return
        web.evaluateJavascript("updateRun($snapshot)") {
            if(disposed || pending==null || activity.isFinishing) return@evaluateJavascript
            pending=null
            show()
        }
    }
    fun dispose() { disposed=true; pending=null; dismiss(); web.destroy() }
    init {
        web.setBackgroundColor(Color.rgb(228,235,227))
        web.settings.javaScriptEnabled=true
        web.settings.allowFileAccess=false
        web.settings.allowContentAccess=false
        web.settings.textZoom=100
        web.settings.useWideViewPort=true
        web.settings.loadWithOverviewMode=true
        web.addJavascriptInterface(object {
            @JavascriptInterface fun ready() {
                activity.runOnUiThread { if(!disposed) { ready=true; displayPrepared() } }
            }
            @JavascriptInterface fun choose(index: Int) {
                activity.runOnUiThread {
                    if(isShowing && index in 0..5) { dismiss(); choose(index) }
                }
            }
        }, "RunPage")
        web.webViewClient=object: WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                val path=request.url.path.orEmpty().removePrefix("/")
                if(request.url.host!="run.local" || path !in setOf("run-selection.html","run-selection.js"))
                    return WebResourceResponse("text/plain","UTF-8",java.io.ByteArrayInputStream(ByteArray(0)))
                return WebResourceResponse(if(path.endsWith(".js")) "application/javascript" else "text/html","UTF-8",activity.assets.open(path))
            }
        }
        setContentView(web)
        setOnCancelListener { choose(0) }
        web.loadUrl("https://run.local/run-selection.html")
    }
}
