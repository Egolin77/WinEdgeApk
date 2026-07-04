package com.example.winedge

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private val windowsEdgeUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"

    private lateinit var container: FrameLayout
    private lateinit var webView: WebView
    private var popupWebView: WebView? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val startUrl = "https://github.com"

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.clipData?.let { clip ->
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            } ?: result.data?.data?.let { arrayOf(it) }
        } else {
            null
        }
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
                notificationPermissionLauncher.launch(
                    android.Manifest.permission.POST_NOTIFICATIONS
                )
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

        // Dinamikus konténer a főoldal és a felugró ablakok egymásra pakolásához
        container = FrameLayout(this)
        setContentView(container)

        webView = WebView(this)
        container.addView(webView)

        configureWebView(webView)

        // LETÖLTÉS KEZELŐ A FŐ ABLAKHOZ
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            setupDownload(url, userAgent, contentDisposition, mimetype)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = createMainWebViewClient()
        webView.webChromeClient = createMainWebChromeClient()

        webView.loadUrl(startUrl)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Ha van nyitva belső felugró ablak, azt zárjuk be előbb
                    if (popupWebView != null) {
                        container.removeView(popupWebView)
                        popupWebView?.destroy()
                        popupWebView = null
                    } else if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(targetWebView: WebView) {
        targetWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = windowsEdgeUserAgent
        }
    }

    private fun createMainWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return handleUrl(request.url)
            }
        }
    }

    private fun createMainWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
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

            // Új belső ablak (popup) létrehozása, ha a weboldal kéri (pl. bejelentkezések)
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                popupWebView?.let {
                    container.removeView(it)
                    it.destroy()
                }

                val newPopup = WebView(this@MainActivity)
                configureWebView(newPopup)
                
                newPopup.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // LETÖLTÉS KEZELŐ A FELUGRÓ ABLAKHOZ
                newPopup.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                    setupDownload(url, userAgent, contentDisposition, mimetype)
                }

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(newPopup, true)

                newPopup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        return handleUrl(request.url)
                    }

                    // Azonnali Google OAuth átirányítás elkapása a felugró ablakban
                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: android.graphics.Bitmap?
                    ) {
                        url?.let {
                            val uri = Uri.parse(it)
                            if (isGoogleAuth(uri)) {
                                handleUrl(uri)
                                container.removeView(newPopup)
                                newPopup.destroy()
                                popupWebView = null
                            }
                        }
                        super.onPageStarted(view, url, favicon)
                    }
                }

                // Megjelenítjük a felugró ablakot az appon belül a főoldal felett
                container.addView(newPopup)
                popupWebView = newPopup

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newPopup
                resultMsg.sendToTarget()

                return true
            }

            override fun onCloseWindow(window: WebView) {
                super.onCloseWindow(window)
                if (window == popupWebView) {
                    container.removeView(window)
                    window.destroy()
                    popupWebView = null
                }
            }
        }
    }

    private fun isGoogleAuth(uri: Uri): Boolean {
        val host = uri.host ?: ""
        return host.contains("accounts.google.com") || host.contains("google.com/accounts")
    }

    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false

        // KIVÉTEL: Kizárólag a Google bejelentkezés használhatja a rendszerszintű biztonságos motort (Custom Tabs)
        if (isGoogleAuth(uri)) {
            CustomTabsIntent.Builder()
                .build()
                .launchUrl(this@MainActivity, uri)
            return true
        }

        // Minden egyéb HTTP/HTTPS forgalom szigorúan az appon belül marad (nincs külső böngésző)
        if (scheme == "http" || scheme == "https") {
            return false 
        }

        // Nem webes protokollok (pl. tel:, mailto:, intent:) átadása a rendszernek, amit a WebView nem tudna megnyitni
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
            true
        } catch (_: Exception) {
            true
        }
    }

    // Közös letöltési logika a DownloadManager segítségével
    private fun setupDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        try {
            val request = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                val cookies = CookieManager.getInstance().getCookie(url)
                addRequestHeader("cookie", cookies)
                addRequestHeader("User-Agent", userAgent)
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Letöltés elindult...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Letöltési hiba: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
