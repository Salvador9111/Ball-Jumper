package com.example.skyboundjumper

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController

class MainActivity : Activity() {
    private lateinit var gameView: GameView
    private lateinit var root: android.widget.FrameLayout
    private lateinit var scene3D: ClimbSurface3D
    private var skinsView: android.webkit.WebView? = null
    private var skinsOpen = false
    private var skinsReady = false
    private var skinsLoadingView: android.widget.TextView? = null
    private var launchIntro: LaunchIntroView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCheckInstaller.install(this)
        gameView = GameView(this)
        root = android.widget.FrameLayout(this)
        scene3D = ClimbSurface3D(this, gameView.world3D)
        gameView.scene3D = scene3D
        root.addView(scene3D)
        root.addView(gameView)
        if (savedInstanceState == null) {
            launchIntro = LaunchIntroView(this) {
                launchIntro?.let { root.removeView(it) }
                launchIntro = null
            }
            root.addView(launchIntro, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        prepareSkins()
        gameView.prepareRunSelection()

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 5894
        }
    }

    override fun onResume() {
        super.onResume()
        if (::scene3D.isInitialized) scene3D.onResume()
        launchIntro?.resumeIntro()
        if (skinsOpen) skinsView?.onResume()
        else if (::gameView.isInitialized) gameView.resumeGame()
    }

    override fun onPause() {
        launchIntro?.pauseIntro()
        if (::gameView.isInitialized) gameView.pauseGame()
        if (::scene3D.isInitialized) scene3D.onPause()
        skinsView?.onPause()
        super.onPause()
    }

    @Deprecated("Android framework callback remains required for pre-API 33 devices")
    override fun onBackPressed() {
        if (launchIntro != null) { launchIntro?.skip(); return }
        if (skinsOpen) { closeSkins(); return }
        if (!::gameView.isInitialized || !gameView.handleBackPressed()) {
            super.onBackPressed()
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun prepareSkins() {
        if (skinsView != null) return
        val web = android.webkit.WebView(this)
        skinsView = web
        web.visibility = android.view.View.INVISIBLE
        web.setBackgroundColor(android.graphics.Color.rgb(14, 12, 10))
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse {
                val path = request.url.path.orEmpty().removePrefix("/")
                if (request.url.host != "skins.local" || path.contains(".."))
                    return android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                return try {
                    android.webkit.WebResourceResponse(if (path.endsWith(".ttf")) "font/ttf" else "text/html", "UTF-8", assets.open(path))
                } catch (_: java.io.IOException) {
                    android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
            }
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest) = true
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                skinsReady = true
                if (skinsOpen) showPreparedSkins()
            }
        }
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface fun snapshot() = gameView.skinSnapshot()
            @android.webkit.JavascriptInterface fun choose(index: Int) = gameView.chooseReferenceSkin(index)
            @android.webkit.JavascriptInterface fun cacheSphere(index: Int, data: String) = gameView.cacheSkinSphere(index, data)
            @android.webkit.JavascriptInterface fun close() { runOnUiThread { closeSkins() } }
        }, "NativeSkins")
        root.addView(web, 0, android.widget.FrameLayout.LayoutParams(-1, -1))
        web.loadUrl("https://skins.local/ball_skins.html")
    }

    fun openSkins() {
        if (skinsOpen) return
        skinsOpen = true
        gameView.pauseGame()
        prepareSkins()
        if (skinsReady) showPreparedSkins() else showSkinsLoadingView()
    }

    private fun showPreparedSkins() {
        if (!skinsOpen) return
        skinsLoadingView?.let(root::removeView)
        skinsLoadingView = null
        skinsView?.apply {
            visibility = android.view.View.VISIBLE
            bringToFront()
            isFocusableInTouchMode = true
            requestFocus()
            onResume()
            evaluateJavascript("document.activeElement && document.activeElement.blur()", null)
            evaluateJavascript("window.openNativeShop && window.openNativeShop()", null)
        }
    }

    private fun showSkinsLoadingView() {
        if (skinsLoadingView != null) return
        skinsLoadingView = android.widget.TextView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(14, 12, 10))
            setTextColor(android.graphics.Color.rgb(243, 236, 225))
            text = "BALL SKINS\n\nPreparing collection…"
            textSize = 25f
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.SERIF
        }.also { root.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)) }
    }

    private fun closeSkins() {
        val web = skinsView ?: return
        skinsOpen = false
        skinsLoadingView?.let(root::removeView)
        skinsLoadingView = null
        web.evaluateJavascript("window.closeNativeShop && window.closeNativeShop()", null)
        web.onPause()
        web.visibility = android.view.View.INVISIBLE
        gameView.handleBackPressed()
        gameView.resumeGame()
    }

    override fun onDestroy() {
        if (::gameView.isInitialized) gameView.disposeRunSelection()
        skinsView?.destroy()
        skinsView = null
        super.onDestroy()
    }

    fun openAchievements() {
        gameView.pauseGame()
        val dialog = AchievementsDialog(this, gameView.achievementSnapshot(), gameView::claimAchievement)
        dialog.setOnDismissListener { gameView.resumeGame() }
        dialog.show()
    }
}
