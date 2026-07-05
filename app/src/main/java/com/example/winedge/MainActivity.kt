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

    // JAVÍTOTT (ASZTALI LINUX CHROME) USER AGENT:
private val windowsEdgeUserAgent =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val startUrl = "https://www.bing.com"

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

        webView = WebView(this)
        setContentView(webView)

        configureWebView(webView)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = createMainWebViewClient()
        webView.webChromeClient = createMainWebChromeClient()

        // Figyelők beállítása a letöltésekhez
        setupDownloadListener(webView)

        webView.loadUrl(startUrl)

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

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(targetWebView: WebView) {
        // Hardveres gyorsítás kényszerítése a HTML5 Canvas (Excel grid) sima kirajzolásához
        targetWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

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

            // Fixen 100%-on tartja a szövegméretet, így nagy felbontású (asztali) módban sem csúsznak szét a cellák
            textZoom = 100 
            
            // Biztonságos vegyes tartalom kezelés modern webes funkciókhoz
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
    }

    private fun setupDownloadListener(targetWebView: WebView) {
        // Regisztráljuk a háttérben futó JavaScript interfészt a blobok kezeléséhez
        targetWebView.addJavascriptInterface(BlobDownloadInterface(this), "AndroidBlobDownloader")

        targetWebView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (url.startsWith("blob:")) {
                // Dinamikus Blob URL esetén JavaScript injektálással Base64 formátumra alakítjuk a fájlt
                val jsCode = """
                    (function() {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function(e) {
                            if (this.status == 200) {
                                var blob = this.response;
                                var reader = new FileReader();
                                reader.readAsDataURL(blob);
                                reader.onloadend = function() {
                                    var base64data = reader.result;
                                    AndroidBlobDownloader.processBlob(base64data, '$mimetype', '$contentDisposition');
                                }
                            }
                        };
                        xhr.send();
                    })();
                """.trimIndent()
                
                targetWebView.evaluateJavascript(jsCode, null)
                Toast.makeText(this, "Dinamikus tartalom feldolgozása...", Toast.LENGTH_SHORT).show()
            } else {
                // Normál HTTP/HTTPS letöltés a gyári DownloadManager segítségével
                try {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        val cookies = CookieManager.getInstance().getCookie(url)
                        addRequestHeader("cookie", cookies)
                        addRequestHeader("User-Agent", userAgent)
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                    val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                    Toast.makeText(this, "Letöltés elindult...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Hiba a letöltés során: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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
                
                // Letöltések kezelése a felugró ablakokban is
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

    // Belső osztály a JavaScript-ből érkező Base64 kódolású Blob adatok elmentésére (Android 10+ támogatással)
    inner class BlobDownloadInterface(private val context: Context) {
        @android.webkit.JavascriptInterface
        fun processBlob(base64Data: String, mimeType: String, contentDisposition: String) {
            try {
                val pureBase64 = if (base64Data.contains(",")) base64Data.split(",")[1] else base64Data
                val fileBytes = android.util.Base64.decode(pureBase64, android.util.Base64.DEFAULT)
                val fileName = URLUtil.guessFileName("", contentDisposition, mimeType)
                
                val resolver = context.contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(fileBytes)
                    }
                    runOnUiThread {
                        Toast.makeText(context, "Sikeresen mentve a Letöltésekbe: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(context, "Blob letöltési hiba: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
