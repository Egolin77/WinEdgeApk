package com.example.winedge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
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
import java.io.File

class MainActivity : AppCompatActivity() {
    private val desktopChromeUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private val startUrl = "https://outlook.cloud.microsoft/mail"

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

    // JavaScript híd osztály a Blob fájlok letöltéséhez
    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun downloadBase64File(base64Data: String, filename: String, mimeType: String) {
            try {
                val pureBase64 = base64Data.substringAfter("base64,")
                val decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        resolver.openOutputStream(uri).use { outputStream ->
                            outputStream?.write(decodedBytes)
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val file = File(downloadsDir, filename)
                    file.writeBytes(decodedBytes)
                }

                runOnUiThread {
                    Toast.makeText(context, "Sikeres letöltés: $filename", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(context, "Hiba a fájl mentésekor: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        makeFullScreen()

        webView = WebView(this)
        setContentView(webView)

        configureWebView(webView)
        configureCookies(webView)

        // JavaScript interfész regisztrálása a letöltésekhez
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidDownloader")

        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createWebChromeClient()

        // Hagyományos és Blob alapú letöltések kezelése
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (url.startsWith("blob:")) {
                // Ha blob cím, akkor JavaScript segítségével kivesszük a tartalmát
                val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype) ?: "export_file.xlsx"
                val js = """
                    (async function() {
                        try {
                            const response = await fetch('$url');
                            const blob = await response.blob();
                            const reader = new FileReader();
                            reader.onload = function() {
                                AndroidDownloader.downloadBase64File(reader.result, '$filename', '$mimetype');
                            };
                            reader.readAsDataURL(blob);
                        } catch(e) { console.error(e); }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            } else {
                // Hagyományos HTTP/HTTPS letöltés DownloadManagerrel
                try {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        val cookies = CookieManager.getInstance().getCookie(url)
                        addRequestHeader("cookie", cookies)
                        addRequestHeader("User-Agent", userAgent)
                        
                        setTitle(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype))
                        setDescription("Fájl letöltése...")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype))
                    }

                    val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                    
                    Toast.makeText(applicationContext, "A letöltés elindult...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
        })

        webView.loadUrl(intent.getStringExtra("open_url") ?: startUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
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
        if (!openUrl.isNullOrBlank() && ::webView.isInitialized) {
            webView.loadUrl(openUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(target: WebView) {
        target.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                target.settings.forceDark = WebSettings.FORCE_DARK_OFF
            }
        } catch (_: Exception) {}

        target.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 80
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = desktopChromeUserAgent
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

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectDesktopMode(view)
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

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = WebView(this@MainActivity)
                configureWebView(popup)
                configureCookies(popup)
                popup.webChromeClient = this
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (handleUrl(uri)) {
                            popup.destroy()
                            return true
                        }
                        webView.loadUrl(uri.toString())
                        popup.destroy()
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        injectDesktopMode(view)
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
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

    private fun injectDesktopMode(target: WebView) {
        val js = """
            (function(){try{
                const ua='$desktopChromeUserAgent';
                const def=(o,p,v)=>Object.defineProperty(o,p,{get:()=>v,configurable:true});
                def(navigator,'platform','Win32');
                def(navigator,'vendor','Google Inc.');
                def(navigator,'maxTouchPoints',0);
                def(navigator,'hardwareConcurrency',8);
                def(navigator,'deviceMemory',8);
                def(navigator,'webdriver',false);
                def(navigator,'language','en-US');
                def(navigator,'languages',['en-US','en']);
                def(navigator,'userAgent',ua);
                def(navigator,'appVersion','5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36');
                def(window,'chrome',{runtime:{},app:{},webstore:{}});
                if(navigator.userAgentData){
                    Object.defineProperty(navigator,'userAgentData',{get:()=>({
                        brands:[{brand:'Google Chrome',version:'138'},{brand:'Chromium',version:'138'},{brand:'Not-A.Brand',version:'99'}],
                        mobile:false,
                        platform:'Windows',
                        getHighEntropyValues:()=>Promise.resolve({architecture:'x86',bitness:'64',mobile:false,model:'',platform:'Windows',platformVersion:'10.0.0',fullVersionList:[{brand:'Google Chrome',version:'138.0.0.0'},{brand:'Chromium',version:'138.0.0.0'},{brand:'Not-A.Brand',version:'99.0.0.0'}]})
                    }),configurable:true});
                }
                document.documentElement.style.touchAction='auto';

                if (!window.__repaintFixAdded) {
                    window.__repaintFixAdded = true;
                    window.addEventListener('orientationchange', function() {
                        setTimeout(function() {
                            document.body.style.display = 'none';
                            document.body.offsetHeight;
                            document.body.style.display = '';
                        }, 100);
                    });
                }
            }catch(e){}})();
        """.trimIndent()
        target.evaluateJavascript(js, null)
    }

    private fun makeFullScreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }
}
