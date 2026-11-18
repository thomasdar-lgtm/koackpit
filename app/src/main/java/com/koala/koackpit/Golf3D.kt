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
    val web = remember {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
            .build()
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            // La page lit l'état des ouvrants au moment où le modèle a fini de charger
            addJavascriptInterface(Host(), "KoackpitHost")
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
    AndroidView(factory = { web }, modifier = modifier)
}

private class Host {
    @JavascriptInterface
    fun doors(): Int = CanBus.doors.value
}
