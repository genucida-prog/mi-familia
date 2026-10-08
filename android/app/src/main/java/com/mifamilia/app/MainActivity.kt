package com.mifamilia.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

/**
 * Hosts Mi Familia in a WebView.
 *
 * The app loads the site from GitHub Pages ([REMOTE_URL]) so every `git push`
 * updates installed devices without a new APK: the page owns a service worker
 * (`sw.js`) that revalidates on each navigation. First run (and any remote
 * failure) uses the bundled copy served by [WebViewAssetLoader] from
 * [LOCAL_URL] — a real https origin either way, which is what keeps
 * `localStorage` (the family store) and `navigator.geolocation` working.
 *
 * Origins do not share storage, so on first run the family data is exported
 * from the local origin, replayed onto the Pages origin (only keys that do not
 * exist yet), and only then marked migrated in SharedPreferences. If the Pages
 * origin ever fails to load, the WebView falls back to the bundled copy for
 * that launch; tiles, MQTT, OSRM and Nominatim go to the real network through
 * the INTERNET permission.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    private val prefs by lazy { getSharedPreferences("mifamilia_web", MODE_PRIVATE) }

    private var exportRequested = false
    private var exportedData: String? = null
    private var mergeInjected = false
    private var fallbackUsed = false

    private val locationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* [hasLocationPermission] is re-checked whenever the page asks. */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(BACKGROUND_COLOR)
            overScrollMode = WebView.OVER_SCROLL_NEVER
        }
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            // Family data lives in localStorage; without this it is lost on
            // every restart.
            domStorageEnabled = true
            databaseEnabled = true
            // Assets are served through the loader, not the filesystem.
            allowFileAccess = false
            allowContentAccess = false
            // The MQTT super-call alarm plays as soon as a message arrives,
            // without any preceding tap on the WebView.
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = object : WebViewClientCompat() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                // Our own origins (bundled copy and GitHub Pages) stay in the
                // WebView; anything else is handed off to a real app.
                return if (isOwnOrigin(url)) false else openExternally(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(SHELL_COLLAPSE_JS, null)
                when {
                    // First run finished on the bundled copy: export the family
                    // data, then replay it onto the Pages origin.
                    !migrated() && !exportRequested && !fallbackUsed && isLocal(url) -> {
                        exportRequested = true
                        view.evaluateJavascript("JSON.stringify(localStorage)") { value ->
                            exportedData = if (value == null || value == "null") "\"{}\"" else value
                            view.loadUrl(REMOTE_URL)
                        }
                    }
                    // Pages origin is up and we hold exported data: merge only
                    // the missing keys, then reload so the page boots with them.
                    !mergeInjected && !fallbackUsed && exportedData != null && isRemote(url) -> {
                        mergeInjected = true
                        val data = exportedData
                        exportedData = null
                        view.evaluateJavascript("$MERGE_JS($data)") {
                            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
                            view.reload()
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceErrorCompat
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) fallBackToLocal(view, request.url)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame) fallBackToLocal(view, request.url)
            }
        }

        // GPS: the OS permission is requested below; the page's own prompt is
        // answered from it so navigator.geolocation works inside the WebView.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, hasLocationPermission(), false)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (!hasLocationPermission()) {
            locationPermissions.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        webView.loadUrl(if (migrated()) REMOTE_URL else LOCAL_URL)
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun migrated(): Boolean = prefs.getBoolean(KEY_MIGRATED, false)

    private fun isRemote(url: String): Boolean = url.startsWith(REMOTE_URL)

    private fun isLocal(url: String): Boolean = url.startsWith(LOCAL_URL)

    private fun isOwnOrigin(uri: Uri): Boolean =
        uri.host == ASSET_DOMAIN || uri.host == REMOTE_HOST

    /**
     * The Pages origin failed (offline, Pages not deployed yet, repo renamed):
     * hand this launch to the bundled copy. One attempt per launch, and the
     * migration flags stay untouched so the next online launch retries.
     */
    private fun fallBackToLocal(view: WebView, uri: Uri) {
        if (fallbackUsed || uri.host != REMOTE_HOST) return
        fallbackUsed = true
        if (view.url != null && isLocal(view.url!!)) return
        view.loadUrl(LOCAL_URL)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Returns true when navigation was consumed, so the WebView stays put. */
    private fun openExternally(uri: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private companion object {
        const val ASSET_DOMAIN = "appassets.androidplatform.net"
        const val LOCAL_URL = "https://appassets.androidplatform.net/assets/www/index.html"
        const val REMOTE_HOST = "genucida-prog.github.io"
        const val REMOTE_URL = "https://genucida-prog.github.io/mi-familia/"
        const val BACKGROUND_COLOR = 0xFFF5F8F7.toInt()
        const val KEY_MIGRATED = "migrated"

        /**
         * Replays the exported local storage onto the Pages origin, touching
         * only keys that do not exist there yet (never overwrites remote data).
         * The argument is the JSON-encoded export string evaluated by the page.
         */
        const val MERGE_JS =
            "(function(d){try{var o=JSON.parse(d);for(var k in o){" +
                "if(localStorage.getItem(k)===null){localStorage.setItem(k,o[k]);}" +
                "}}catch(e){}})"

        /**
         * The canonical page embeds the OD presentation shell (fake status bar,
         * punch-hole, gesture pill) for desktop previews. Inside the app the
         * real system bars are already there, so on every page load the shell
         * collapses to a plain fullscreen document.
         */
        const val SHELL_COLLAPSE_JS =
            "(function(){if(document.getElementById('od-apk-shell'))return;" +
                "var s=document.createElement('style');s.id='od-apk-shell';" +
                "s.textContent='body{padding:0!important;display:block!important;background:#F5F8F7!important}" +
                ".phone-frame{width:100%!important;height:100dvh!important;padding:0!important;border-radius:0!important;background:none!important;box-shadow:none!important;zoom:1!important}" +
                ".phone-frame:before,.phone-frame:after,.hardware-button,.status-bar,.punch-hole,.camera-dot,.gesture-indicator{display:none!important}" +
                ".phone-screen{width:100%!important;height:100%!important;border-radius:0!important}" +
                ".phone-content{padding-top:0!important;padding-bottom:0!important}';" +
                "document.head.appendChild(s);})();"
    }
}
