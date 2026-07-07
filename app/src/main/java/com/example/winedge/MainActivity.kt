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
import android.webkit.WebView
import android.webkit.WebViewClient
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

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val startUrl = "https://outlook.cloud.microsoft/mail"

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

        NotificationHelper.createWebActivityNotificationChannel(this)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )

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

            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)

            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false

            userAgentString = windowsEdgeUserAgent
            textZoom = 100

            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
    }

    private fun setupDownloadListener(targetWebView: WebView) {
        targetWebView.addJavascriptInterface(
            BlobDownloadInterface(this),
            "AndroidBlobDownloader"
        )

        targetWebView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (url.startsWith("blob:")) {
                val safeUrl = url.replace("'", "\\'")
                val safeMimeType = mimetype.replace("'", "\\'")
                val safeContentDisposition = contentDisposition.replace("'", "\\'")

                val jsCode = """
                    (function() {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$safeUrl', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            if (this.status == 200) {
                                var blob = this.response;
                                var reader = new FileReader();
                                reader.readAsDataURL(blob);
                                reader.onloadend = function() {
                                    var base64data = reader.result;
                                    AndroidBlobDownloader.processBlob(
                                        base64data,
                                        '$safeMimeType',
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
                            contentDisposition,
                            mimetype
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

    private fun createMainWebViewClient(): WebViewClient {
        return object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                return handleUrl(uri)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
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

    private fun injectUniversalNotificationWatcher(targetWebView: WebView) {
        val js = """
            (function() {
                if (window.__WinEdgeUniversalWatcherInstalled) {
                    return;
                }

                window.__WinEdgeUniversalWatcherInstalled = true;

                var lastSignal = "";
                var lastSignalAt = 0;

                function now() {
                    return Date.now();
                }

                function safeText(value) {
                    if (!value) return "";
                    return String(value)
                        .replace(/\s+/g, " ")
                        .trim()
                        .substring(0, 240);
                }

                function sendSignal(title, message) {
                    try {
                        var currentUrl = location.href || "";
                        var cleanTitle = safeText(title || document.title || "Web activity detected");
                        var cleanMessage = safeText(message || "Possible new activity on this page");

                        var signal = cleanTitle + "|" + cleanMessage + "|" + currentUrl;
                        var t = now();

                        if (signal === lastSignal && (t - lastSignalAt) < 60000) return;
                        if ((t - lastSignalAt) < 8000) return;

                        lastSignal = signal;
                        lastSignalAt = t;

                        if (window.WinEdgeNotifier && window.WinEdgeNotifier.notifyFromWeb) {
                            window.WinEdgeNotifier.notifyFromWeb(
                                cleanTitle,
                                cleanMessage,
                                currentUrl
                            );
                        }
                    } catch (e) {}
                }

                function titleLooksLikeNotification(title) {
                    if (!title) return false;

                    var t = title.toLowerCase();

                    if (/^$$\d+$$/.test(t)) return true;
                    if (/\b\d+\s+(new|unread|notification|message|messages)\b/.test(t)) return true;
                    if (t.indexOf("new message") >= 0) return true;
                    if (t.indexOf("unread") >= 0) return true;
                    if (t.indexOf("notification") >= 0) return true;
                    if (t.indexOf("értesítés") >= 0) return true;
                    if (t.indexOf("üzenet") >= 0) return true;
                    if (t.indexOf("új üzenet") >= 0) return true;

                    return false;
                }

                var lastTitle = document.title || "";

                setInterval(function() {
                    try {
                        var currentTitle = document.title || "";

                        if (currentTitle !== lastTitle) {
                            lastTitle = currentTitle;

                            if (titleLooksLikeNotification(currentTitle)) {
                                sendSignal("Web notification", currentTitle);
                            }
                        }
                    } catch (e) {}
                }, 3000);

                function detectBadges() {
                    try {
                        var selectors = [
                            "[aria-label*='unread' i]",
                            "[aria-label*='notification' i]",
                            "[aria-label*='message' i]",
                            "[aria-label*='new' i]",
                            "[aria-label*='értesítés' i]",
                            "[aria-label*='üzenet' i]",
                            "[aria-label*='új' i]",
                            "[title*='unread' i]",
                            "[title*='notification' i]",
                            "[title*='message' i]",
                            "[title*='new' i]",
                            "[title*='értesítés' i]",
                            "[title*='üzenet' i]",
                            "[data-testid*='badge' i]",
                            "[data-testid*='notification' i]",
                            "[data-testid*='unread' i]",
                            "[class*='badge' i]",
                            "[class*='unread' i]",
                            "[class*='notification' i]",
                            "[class*='counter' i]",
                            "[class*='count' i]"
                        ];

                        var foundTexts = [];

                        selectors.forEach(function(selector) {
                            var nodes = document.querySelectorAll(selector);

                            for (var i = 0; i < Math.min(nodes.length, 20); i++) {
                                var n = nodes[i];

                                var txt =
                                    n.getAttribute("aria-label") ||
                                    n.getAttribute("title") ||
                                    n.getAttribute("data-count") ||
                                    n.innerText ||
                                    n.textContent ||
                                    "";

                                txt = safeText(txt);

                                if (!txt) continue;

                                var lower = txt.toLowerCase();

                                if (
                                    /\b\d+\b/.test(lower) ||
                                    lower.indexOf("unread") >= 0 ||
                                    lower.indexOf("notification") >= 0 ||
                                    lower.indexOf("message") >= 0 ||
                                    lower.indexOf("new") >= 0 ||
                                    lower.indexOf("értesítés") >= 0 ||
                                    lower.indexOf("üzenet") >= 0 ||
                                    lower.indexOf("új") >= 0
                                ) {
                                    foundTexts.push(txt);
                                }
                            }
                        });

                        if (foundTexts.length > 0) {
                            sendSignal(
                                "Web activity detected",
                 
