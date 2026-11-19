package com.koala.koackpit

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader

/**
 * La Golf 5 en 3D (assets/golf3d : three.js embarqué, fonctionne hors ligne).
 * Les ouvrants suivent la trame CAN « portes » ([Doors]) ; toucher une pièce affiche sa fiche.
 * Source de la vue : web/golf3d (main.js → golf3d.js avec build.sh).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Golf3D(mask: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    // Prête (modèle affiché) / en échec : la photo de la Golf remplace la vue 3D si elle ne s'affiche pas
    val ready = remember { androidx.compose.runtime.mutableStateOf(false) }
    val failed = remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val v = runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }.getOrNull() ?: "?"
        WakeLog.add(ctx, "Golf 3D : ouverture (WebView $v)")
        kotlinx.coroutines.delay(30_000)
        if (!ready.value && !failed.value) { failed.value = true; WakeLog.add(ctx, "Golf 3D : pas affichée au bout de 30 s") }
    }
    val web = remember {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
            .build()
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            // La page lit l'état des ouvrants au moment où le modèle a fini de charger
            addJavascriptInterface(Host(ctx.applicationContext, { ready.value = true }, { failed.value = true }), "KoackpitHost")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
            loadUrl("https://appassets.androidplatform.net/assets/golf3d/index.html?embed=1")
        }
    }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }
    // Chaque changement d'ouvrant est envoyé à la vue (ignoré tant que le modèle charge : il relit l'état à la fin)
    LaunchedEffect(mask) { web.evaluateJavascript("window.koackpitDoors && window.koackpitDoors($mask)", null) }
    androidx.compose.foundation.layout.Box(modifier) {
        AndroidView(factory = { web }, modifier = Modifier.matchParentSize())
        if (failed.value && !ready.value) androidx.compose.foundation.layout.Column(
            Modifier.matchParentSize().background(Card),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.golf_profil), "Ma Golf",
                Modifier.fillMaxWidth(0.85f), contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            androidx.compose.material3.Text("Vue 3D indisponible sur cet écran (détail dans le journal)", color = Muted,
                fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp))
        }
    }
}

/** Pont avec la page 3D : état des ouvrants, journal des erreurs, modèle affiché. */
private class Host(private val app: android.content.Context, private val onReady: () -> Unit, private val onFail: () -> Unit) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var logged = 0

    @JavascriptInterface
    fun doors(): Int = CanBus.doors.value

    @JavascriptInterface
    fun ready() { WakeLog.add(app, "Golf 3D : affichée"); main.post(onReady) }

    @JavascriptInterface
    fun log(msg: String) {
        if (logged++ < 5) WakeLog.add(app, "Golf 3D : ${msg.take(200)}")
        main.post(onFail)
    }
}
