package com.example.winedge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private val windowsDesktopChromeUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val startUrl = "https://bing.com"

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.clipData?.let { clip ->
                Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
            } ?: result.data?.data?.let { uri -> arrayOf(uri) }
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

        NotificationHelper.createWebActivityNotificationChannel(this)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN

        webView = WebView(this)
        setContentView(webView)

        configureWebView(webView)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(
            UniversalWebNotificationBridge(this),
            "WinEdgeNotifier"
        )

        webView.webViewClient = createMainWebViewClient()
        webView.webChromeClient = createMainWebChromeClient()

        setupDownloadListener(webView)

        val initialUrl = intent.getStringExtra("open_url") ?: startUrl
        webView.loadUrl(initialUrl)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val openUrl = intent.getStringExtra("open_url")
        if (!openUrl.isNullOrBlank() && ::webView.isInitialized) {
            webView.loadUrl(openUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(targetWebView: WebView) {
        targetWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        targetWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            loadWithOverviewMode = true
            useWideViewPort = true

            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false

            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)

            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false

            userAgentString = windowsDesktopChromeUserAgent
            textZoom = 100

            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            @Suppress("DEPRECATION")
            pluginState = WebSettings.PluginState.ON
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

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                injectDesktopMode(view)
                injectUniversalNotificationWatcher(view)
            }
        }
    }

    private fun createMainWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
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

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val popupWebView = WebView(this@MainActivity)

                configureWebView(popupWebView)

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(popupWebView, true)

                popupWebView.addJavascriptInterface(
                    UniversalWebNotificationBridge(this@MainActivity),
                    "WinEdgeNotifier"
                )

                setupDownloadListener(popupWebView)

                popupWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val uri = request.url

                        if (handleUrl(uri)) {
                            popupWebView.destroy()
                            return true
                        }

                        webView.loadUrl(uri.toString())
                        popupWebView.destroy()
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)

                        injectDesktopMode(view)
                        injectUniversalNotificationWatcher(view)
                    }
                }

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popupWebView
                resultMsg.sendToTarget()

                return true
            }
        }
    }

    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        val host = uri.host ?: ""

        if (host.contains("accounts.google.com")) {
            CustomTabsIntent.Builder()
                .build()
                .launchUrl(this@MainActivity, uri)
            return true
        }

        if (scheme == "http" || scheme == "https") {
            return false
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun setupDownloadListener(targetWebView: WebView) {
        targetWebView.addJavascriptInterface(
            BlobDownloadInterface(this),
            "AndroidBlobDownloader"
        )

        targetWebView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val safeMimeType = mimetype ?: "application/octet-stream"
            val safeDisposition = contentDisposition ?: ""

            if (url.startsWith("blob:")) {
                val safeUrl = url.replace("'", "\\'")
                val safeMime = safeMimeType.replace("'", "\\'")
                val safeContentDisposition = safeDisposition.replace("'", "\\'")

                val jsCode = """
                    (function() {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$safeUrl', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            if (this.status === 200) {
                                var blob = this.response;
                                var reader = new FileReader();
                                reader.readAsDataURL(blob);
                                reader.onloadend = function() {
                                    AndroidBlobDownloader.processBlob(
                                        reader.result,
                                        '$safeMime',
                                        '$safeContentDisposition'
                                    );
                                };
                            }
                        };
                        xhr.send();
                    })();
                """.trimIndent()

                targetWebView.evaluateJavascript(jsCode, null)

                Toast.makeText(
                    this,
                    "Dinamikus tartalom feldolgozása...",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                try {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        val cookies = CookieManager.getInstance().getCookie(url)
                        if (!cookies.isNullOrBlank()) {
                            addRequestHeader("cookie", cookies)
                        }

                        addRequestHeader("User-Agent", userAgent)

                        setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )

                        val fileName = URLUtil.guessFileName(
                            url,
                            safeDisposition,
                            safeMimeType
                        )

                        setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName
                        )
                    }

                    val downloadManager =
                        getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)

                    Toast.makeText(
                        this,
                        "Letöltés elindult...",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Hiba a letöltés során: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun injectDesktopMode(targetWebView: WebView) {
        val js = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'platform', {
                        get: function() { return 'Win32'; },
                        configurable: true
                    });

                    Object.defineProperty(navigator, 'vendor', {
                        get: function() { return 'Google Inc.'; },
                        configurable: true
                    });

                    Object.defineProperty(navigator, 'maxTouchPoints', {
                        get: function() { return 0; },
                        configurable: true
                    });

                    Object.defineProperty(navigator, 'hardwareConcurrency', {
                        get: function() { return 8; },
                        configurable: true
                    });

                    Object.defineProperty(navigator, 'deviceMemory', {
                        get: function() { return 8; },
                        configurable: true
                    });

                    Object.defineProperty(navigator, 'webdriver', {
                        get: function() { return false; },
                        configurable: true
                    });

                    Object.defineProperty(navigator, 'userAgent', {
                        get: function() { return '$windowsDesktopChromeUserAgent'; },
                        configurable: true
                    });

                    Object.defineProperty(navigator, 'appVersion', {
                        get: function() { return '5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'; },
                        configurable: true
                    });

                    if (navigator.userAgentData) {
                        Object.defineProperty(navigator, 'userAgentData', {
                            get: function() {
                                return {
                                    brands: [
                                        { brand: 'Google Chrome', version: '124' },
                                        { brand: 'Chromium', version: '124' },
                                        { brand: 'Not-A.Brand', version: '99' }
                                    ],
                                    mobile: false,
                                    platform: 'Windows',
                                    getHighEntropyValues: function(hints) {
                                        return Promise.resolve({
                                            architecture: 'x86',
                                            bitness: '64',
                                            brands: [
                                                { brand: 'Google Chrome', version: '124' },
                                                { brand: 'Chromium', version: '124' },
                                                { brand: 'Not-A.Brand', version: '99' }
                                            ],
                                            fullVersionList: [
                                                { brand: 'Google Chrome', version: '124.0.0.0' },
                                                { brand: 'Chromium', version: '124.0.0.0' },
                                                { brand: 'Not-A.Brand', version: '99.0.0.0' }
                                            ],
                                            mobile: false,
                                            model: '',
                                            platform: 'Windows',
                                            platformVersion: '10.0.0'
                                        });
                                    }
                                };
                            },
                            configurable: true
                        });
                    }

                    document.documentElement.style.touchAction = 'auto';
                } catch (e) {
                    console.log('Desktop mode injection failed', e);
                }
            })();
        """.trimIndent()

        targetWebView.evaluateJavascript(js, null)
    }

    private fun injectUniversalNotificationWatcher(targetWebView: WebView) {
        targetWebView.evaluateJavascript(
            UniversalNotificationScript.create(),
            null
        )
    }
}
