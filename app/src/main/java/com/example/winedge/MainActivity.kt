package com.example.winedge

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

class MainActivity : AppCompatActivity() {

    private val windowsEdgeUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"

    private data class Tab(val webView: WebView, var title: String = "Új lap")

    private val tabs = mutableListOf<Tab>()
    private var activeTabIndex = 0
    private lateinit var tabBar: LinearLayout
    private lateinit var webViewContainer: FrameLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        val rootFrame = FrameLayout(this)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val tabBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1F1F1F"))
        }

        val scrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scrollView.addView(tabBar)

        val newTabBtn = TextView(this).apply {
            text = " + "
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(20, 16, 20, 16)
            setOnClickListener { openNewTab("https://www.bing.com") }
        }

        tabBarRow.addView(
            scrollView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        tabBarRow.addView(newTabBtn)

        webViewContainer = FrameLayout(this)

        mainLayout.addView(
            tabBarRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        mainLayout.addView(
            webViewContainer,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        rootFrame.addView(
            mainLayout,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        setContentView(rootFrame)

        openNewTab("https://www.bing.com")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = currentWebView()
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun currentWebView(): WebView? =
        if (tabs.isEmpty()) null else tabs[activeTabIndex].webView

    @SuppressLint("SetJavaScriptEnabled")
    private fun openNewTab(url: String) {
        val webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = windowsEdgeUserAgent
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String) {
                    val idx = tabs.indexOfFirst { it.webView == view }
                    if (idx >= 0) { tabs[idx].title = title; refreshTabBar() }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("""
                        (function() {
                            Object.defineProperty(navigator, 'maxTouchPoints', {get: function(){ return 0; }});
                            Object.defineProperty(navigator, 'msMaxTouchPoints', {get: function(){ return 0; }});
                        })();
                    """.trimIndent(), null)
                }
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: android.webkit.WebResourceRequest
                ): Boolean {
                    val u = request.url
                    if (u.host?.contains("accounts.google.com") == true) {
                        CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, u)
                        return true
                    }
                    return false
                }
            }
            loadUrl(url)
        }

        tabs.add(Tab(webView))
        webViewContainer.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        switchToTab(tabs.size - 1)
    }

    private fun switchToTab(index: Int) {
        activeTabIndex = index
        tabs.forEachIndexed { i, tab ->
            tab.webView.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        refreshTabBar()
    }

    private fun closeTab(index: Int) {
        if (tabs.size == 1) {
            openNewTab("https://www.bing.com/?PC=EMMX01")
        }
        val tab = tabs[index]
        webViewContainer.removeView(tab.webView)
        tab.webView.destroy()
        tabs.removeAt(index)
        switchToTab(if (activeTabIndex >= tabs.size) tabs.size - 1 else activeTabIndex)
    }

    private fun refreshTabBar() {
        tabBar.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val isActive = index == activeTabIndex

            val tabView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(if (isActive) Color.parseColor("#333333") else Color.parseColor("#1F1F1F"))
                setPadding(16, 0, 8, 0)
            }

            val titleView = TextView(this).apply {
                val t = tab.title
                text = if (t.length > 14) t.take(14) + "…" else t
                textSize = 13f
                setTextColor(if (isActive) Color.WHITE else Color.parseColor("#AAAAAA"))
                setPadding(0, 16, 12, 16)
                setOnClickListener { switchToTab(index) }
            }

            val closeTabBtn = TextView(this).apply {
                text = "×"
                textSize = 17f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(4, 16, 4, 16)
                setOnClickListener { closeTab(index) }
            }

            tabView.addView(titleView)
            tabView.addView(closeTabBtn)

            tabBar.addView(tabView)
            tabBar.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#444444"))
            }, LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }
}
