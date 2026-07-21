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
    private val desktopChromeUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private val startUrl = "https://cloud.mediusflow.com/nilfisk/#/Home"

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
            
            // MÓDOSÍTÁS: Szélesség illesztése a képernyőhöz
            useWideViewPort = true
            loadWithOverviewMode = false // Kikapcsolva, hogy ne méretezze túl fekvő módban
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING // Automatikus szöveg/tartalom illesztés
            
            textZoom = 100
            setSupportZoom(true)
            builtInZoomControls = true // Engedélyezzük a zoomot, ha mégis manuálisan igazítani kéne
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            
            setSupportMultipleWindows(false)
            
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = desktopChromeUserAgent.replace("; wv", "").replace("Version/4.0 ", "")
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
                injectDesktopMode(view)
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
                    addCategory(Intent.ACTION_GET_CONTENT)
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

    private fun injectDesktopMode(target: WebView) {
        // MÓDOSÍTÁS: Dinamikus HTML viewport beszúrás, ami kényszeríti a 100%-os szélességillesztést
        val js = """
            (function(){
                try {
                    // Viewport beállítása a kijelző szélességére
                    let meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        document.getElementsByTagName('head')[0].appendChild(meta);
                    }
                    meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';

                    // CSS szabályok az eltúlzott szélesség és kilógó elemek ellen
                    const style = document.createElement('style');
                    style.innerHTML = 'html, body { max-width: 100% !important; overflow-x: auto !important; }';
                    document.head.appendChild(style);

                    // User Agent emuláció
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
                        Object.defineProperty(navigator,'userAgentData',{
                            get:()=>({
                                brands:[{brand:'Google Chrome',version:'138'},{brand:'Chromium',version:'138'},{brand:'Not-A.Brand',version:'99'}],
                                mobile:false,
                                platform:'Windows',
                                getHighEntropyValues:()=>Promise.resolve({architecture:'x86',bitness:'64',mobile:false,model:'',platform:'Windows',platformVersion:'10.0.0',fullVersionList:[{brand:'Google Chrome',version:'138.0.0.0'},{brand:'Chromium',version:'138.0.0.0'},{brand:'Not-A.Brand',version:'99.0.0.0'}]})
                            }),
                            configurable:true
                        });
                    }
                    document.documentElement.style.touchAction='auto';
                } catch(e) {}
            })();
        """.trimIndent()
        target.evaluateJavascript(js, null)
    }
}
