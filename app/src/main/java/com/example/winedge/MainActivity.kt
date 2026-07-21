package com.example.winedge

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private val startUrl = "https://google.com"

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.clipData?.let { clip -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri } }
                ?: result.data?.data?.let { uri -> arrayOf(uri) }
        } else null
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val request = pendingPermissionRequest ?: return@registerForActivityResult
        if (grants.values.all { it }) request.grant(request.resources) else request.deny()
        pendingPermissionRequest = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_FULLSCREEN

        webView = WebView(this)
        setContentView(webView)

        configureWebView(webView)
        configureCookies(webView)

        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createWebChromeClient()

        webView.loadUrl(intent.getStringExtra("open_url") ?: startUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val openUrl = intent.getStringExtra("open_url")
        if (!openUrl.isNullOrBlank() && ::webView.isInitialized) webView.loadUrl(openUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(target: WebView) {
        target.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        target.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            
            // Kikapcsolva, hogy a bejelentkezési ablakok ne nyissanak új WebView-t
            setSupportMultipleWindows(false)
            
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            // A mesterséges Desktop UA helyett a gyári mobil User-Agent-ből csak a WebView azonosítókat vágjuk ki,
            // így a böngésző fingerprintje (hangrendszer, GPU, platform) összhangban marad a fejléc adatokkal.
            val defaultUa = WebSettings.getDefaultUserAgent(target.context)
            userAgentString = defaultUa.replace("; wv", "").replace("Version/4.0 ", "")
        }
    }

    private fun configureCookies(target: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(target, true)
            flush()
        }
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleUrl(request.url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                CookieManager.getInstance().flush()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
            }
        }
    }

    private fun createWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
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
                val permissions = mutableListOf<String>()
                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) permissions.add(android.Manifest.permission.CAMERA)
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) permissions.add(android.Manifest.permission.RECORD_AUDIO)
                if (permissions.isEmpty()) {
                    request.grant(request.resources)
                    return
                }
                pendingPermissionRequest = request
                permissionLauncher.launch(permissions.toTypedArray())
            }
        }
    }

    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        if (scheme == "http" || scheme == "https") return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            true
        }
    }
}
