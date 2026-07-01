package com.example.winedge

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private val windowsEdgeUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"

    private data class Tab(val webView: WebView, var title: String = "Új lap")

    private val tabs = mutableListOf<Tab>()
    private var activeTabIndex = 0
    private lateinit var tabBar: LinearLayout
    private lateinit var webViewContainer: FrameLayout

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.clipData?.let { clip ->
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            } ?: result.data?.data?.let { arrayOf(it) }
        } else null
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    private val cameraAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val request = pendingPermissionRequest ?: return@registerForActivityResult
        if (grants.values.all { it }) {
            request.grant(request.resources)
        } else {
            request.deny()
        }
        pendingPermissionRequest = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        val rootFrame = FrameLayout(this)
        val mainLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val tabBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1F1F1F"))
        }

        val scrollView = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scrollView.addView(tabBar)

        val newTabBtn = TextView(this).apply {
            text = "+"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(14, 8, 14, 8)
            setOnClickListener { openNewTab("https://www.bing.com/?PC=EMMX01") }
        }

        tabBarRow.addView(scrollView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabBarRow.addView(newTabBtn)

        webViewContainer = FrameLayout(this)

        mainLayout.addView(tabBarRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        mainLayout.addView(webViewContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        rootFrame.addView(mainLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setContentView(rootFrame)

        openNewTab("https://www.bing.com/?PC=EMMX01")

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
                mediaPlaybackRequiresUserGesture = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String) {
                    val idx = tabs.indexOfFirst { it.webView == view }
                    if (idx >= 0) { tabs[idx].title = title; refreshTabBar() }
                }

                override fun onShowFileChooser(
                    webView: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = callback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    fileChooserLauncher.launch(intent)
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    val androidPermissions = mutableListOf<String>()
                    if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        androidPermissions.add(android.Manifest.permission.CAMERA)
                    }
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        androidPermissions.add(android.Manifest.permission.RECORD_AUDIO)
                    }
                    if (androidPermissions.isEmpty()) {
                        request.deny()
                        return
                    }
                    pendingPermissionRequest = request
                    cameraAudioPermissionLauncher.launch(androidPermissions.toTypedArray())
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
                    val currentUrl = view.url
                    if (currentUrl != null && currentUrl != "about:blank") {
                        openNewTab(u.toString())
                        return true
                    }
                    return false
                }
            }
            loadUrl(url)
        }

        tabs.add(Tab(webView))
        webViewContainer.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
        if (tabs.size == 1) openNewTab("https://www.bing.com/?PC=EMMX01")
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
                setPadding(10, 0, 4, 0)
            }
            val titleView = TextView(this).apply {
                val t = tab.title
                text = if (t.length > 14) t.take(14) + "…" else t
                textSize = 11f
                setTextColor(if (isActive) Color.WHITE else Color.parseColor("#AAAAAA"))
                setPadding(0, 8, 8, 8)
                setOnClickListener { switchToTab(index) }
            }
            val closeTabBtn = TextView(this).apply {
                text = "×"
                textSize = 13f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(4, 8, 4, 8)
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
