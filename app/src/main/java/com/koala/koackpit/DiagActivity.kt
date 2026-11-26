package com.koala.koackpit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DiagActivity : ComponentActivity() {
    private var zip: File? = null
    private val server = FileServer { zip }

    override fun onResume() {
        super.onResume()
        fullScreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) fullScreen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fullScreen()
        setContent { DiagScreen(server, onBack = { finish() }) { zip = it } }
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }
}

private const val LIMIT_MB = 300

/** QR code (noir sur blanc) de [text], pour le scanner avec l'appareil photo du S25. */
fun qrBitmap(text: String, size: Int = 512): android.graphics.Bitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(
        text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
    )
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
}

@Composable
fun DiagScreen(server: FileServer, onBack: () -> Unit = {}, onZip: (File) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val upd = UpdateHub.state
    val apps = remember { mutableStateListOf<AppEntry>() }
    val selected = remember { mutableStateListOf<String>() }
    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var step by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<File?>(null) }
    var savedAt by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var rootLog by remember { mutableStateOf("") }
    var activeTool by remember { mutableStateOf(if (CanBus.capturing() || CanBus.lastCaptureEnd != null) 3 else 0) }   // 1 = test d'accès, 2 = journal : bouton surligné
    var export by remember { mutableStateOf(false) }   // fenêtre « Créer le fichier »

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { Diag.apps(ctx) }
        apps.addAll(list)
        loading = false
        runCatching { upd.info = Updater.check() }
    }

    fun create() {
        progress = 0f
        result = null
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    Diag.build(ctx, apps.toList(), selected.toSet()) { v, s -> progress = v; step = s }
                }.onFailure { e -> withContext(Dispatchers.Main) { toast(ctx, "Erreur : ${e.message}") } }.getOrNull()
            }
            if (f != null) {
                onZip(f)
                result = f
                savedAt = withContext(Dispatchers.IO) { Diag.copyToDownloads(ctx, f) }
                val port = server.start()
                url = Diag.ip()?.takeIf { port > 0 }?.let { "http://$it:$port/${Diag.ZIP_NAME}" }
            }
            progress = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().screenBg().padding(16.dp)) {
            DiagHeader(
                activeTool,
                onBack = onBack,
                onTest = {
                    activeTool = 1
                    rootLog = "Test en cours (10 s max)…"
                    scope.launch { rootLog = withContext(Dispatchers.IO) { RootAccess.test(ctx) } }
                },
                onLogs = {
                    activeTool = 2
                    rootLog = "Lecture du journal…"
                    scope.launch { rootLog = withContext(Dispatchers.IO) { RootAccess.lastInstallLog(ctx) } }
                },
                onCapture = {
                    if (!CanBus.capturing()) CanBus.startCapture(ctx, 10)
                    activeTool = 3
                },
                onExport = { export = true },
            )
            Spacer(Modifier.height(10.dp))
            // Relevé voiture : avancement visible, mis à jour chaque seconde (0.20.3)
            if (activeTool == 3) {
                var tick by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
                tick
                val left = CanBus.captureLeftSec()
                val end = CanBus.lastCaptureEnd
                rootLog = when {
                    left > 0 -> "Relevé voiture EN COURS : ${left / 60}:${"%02d".format(left % 60)} restantes · ${CanBus.captureLines()} lignes enregistrées.\n\n" +
                        "Tu peux quitter cet écran et utiliser l'autoradio : le relevé continue. Reviens ici pour voir où il en est ; " +
                        "un message s'affichera aussi à la fin."
                    end != null -> "Relevé voiture TERMINÉ à ${end.first} · ${end.second} lignes enregistrées.\n\n" +
                        "Appuie sur « Créer le fichier » pour préparer le diagnostic à m'envoyer (le relevé est dedans)."
                    else -> "Aucun relevé en cours."
                }
            }
            // Résultat de l'outil choisi : toute la hauteur de l'écran
            Text(
                rootLog.ifEmpty { "Choisis un outil ci-dessus. « Créer le fichier » prépare le diagnostic complet à envoyer à Claude." },
                color = if (rootLog.isEmpty()) Muted else Ink, fontSize = 14.sp, lineHeight = 19.sp,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Card)
                    .verticalScroll(rememberScrollState()).padding(14.dp),
            )
        }
        if (export) ExportPanel(
            apps = apps, selected = selected, loading = loading,
            progress = progress, step = step, result = result, url = url, savedAt = savedAt,
            onToggle = { pkg -> if (pkg in selected) selected.remove(pkg) else selected.add(pkg) },
            onCreate = { create() },
            onShare = { f ->
                Apps.markUserOpen(ctx)
                runCatching {
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Envoyer le diagnostic",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { toast(ctx, "Partage impossible") }
            },
            onClose = { export = false },
        )
    }
}

@Composable
fun DiagHeader(activeTool: Int, onBack: () -> Unit, onTest: () -> Unit, onLogs: () -> Unit, onCapture: () -> Unit, onExport: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BackDisc(onBack)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Koackpit", color = AccentText, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text("Diagnostic ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp)
        Button(onClick = onTest, colors = toolColors(activeTool == 1), contentPadding = pad) { Text("Tester l'accès", fontWeight = FontWeight.Black, fontSize = 14.sp) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onLogs, colors = toolColors(activeTool == 2), contentPadding = pad) { Text("Journaux", fontWeight = FontWeight.Black, fontSize = 14.sp) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onCapture, colors = toolColors(activeTool == 3), contentPadding = pad) {
            Text(if (CanBus.capturing()) "Relevé : en cours" else "Relevé (10 min)", fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onExport, colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = OnAccent), contentPadding = pad) {
            Text("Créer le fichier", fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
    }
}

@Composable
private fun BackDisc(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(BtnDark).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Filled.ArrowBack, "Retour", tint = AccentOnDark, modifier = Modifier.size(26.dp),
        )
    }
}

/** Fenêtre plein écran « Créer le fichier » : applis à joindre à gauche, création et QR code à droite. */
@Composable
fun ExportPanel(
    apps: List<AppEntry>, selected: List<String>, loading: Boolean,
    progress: Float?, step: String, result: File?, url: String?, savedAt: String?,
    onToggle: (String) -> Unit, onCreate: () -> Unit, onShare: (File) -> Unit, onClose: () -> Unit,
    qr: android.graphics.Bitmap? = null,
) {
    val totalMb = apps.filter { it.pkg in selected }.sumOf { it.size } / 1_048_576
    val carInfo = apps.firstOrNull { it.carInfo }
    val code = qr ?: remember(url) { url?.let { runCatching { qrBitmap(it) }.getOrNull() } }
    Column(
        Modifier.fillMaxSize().screenBg().clickable(enabled = false) {}.padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackDisc(onClose)
            Spacer(Modifier.width(12.dp))
            Text("Créer le fichier de diagnostic", color = AccentText, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Applis à joindre (rarement utile)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(
                    when {
                        loading -> "Recherche des applis…"
                        carInfo != null -> "Joindre une appli (seulement si Claude le demande)"
                        else -> "Car Info non reconnue automatiquement : coche-la si Claude le demande"
                    },
                    color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                )
                Text("Applis cochées : $totalMb Mo", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Card)) {
                    items(apps, key = { it.pkg }) { a ->
                        val on = a.pkg in selected
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = progress == null) { onToggle(a.pkg) }.padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = on, onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Yellow, checkmarkColor = OnAccent, uncheckedColor = Muted),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(
                                    a.label + if (a.carInfo) "  ★" else "",
                                    color = if (a.carInfo) AccentText else Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                )
                                Text(a.pkg, color = Muted, fontSize = 11.sp)
                            }
                            Text("${a.size / 1_048_576} Mo", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Création puis récupération
            Column(
                Modifier.weight(1.5f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Card)
                    .verticalScroll(rememberScrollState()).padding(18.dp),
            ) {
                if (progress != null) {
                    Text("Création en cours…", color = AccentText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = AccentText, trackColor = Navy)
                    Text("${(progress * 100).toInt()} % — $step", color = Muted, fontSize = 13.sp)
                } else if (result == null) {
                    Text("Journaux, rapport système et relevés dans un seul fichier.", color = Ink, fontSize = 15.sp)
                    Spacer(Modifier.height(14.dp))
                    Button(
                        enabled = !loading && totalMb <= LIMIT_MB, onClick = onCreate,
                        colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = OnAccent),
                    ) { Text("Créer le fichier", fontWeight = FontWeight.Black, fontSize = 18.sp) }
                    if (totalMb > LIMIT_MB) Text("Trop lourd : décoche des applis (max $LIMIT_MB Mo)", color = Red, fontSize = 13.sp)
                } else {
                    Row {
                        if (code != null) {
                            androidx.compose.foundation.Image(
                                code.asImageBitmap(), "QR code du fichier",
                                Modifier.size(190.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Fichier prêt (${result.length() / 1_048_576} Mo)", color = AccentText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Spacer(Modifier.height(6.dp))
                            if (url != null) {
                                Text("Scanne le QR code avec l'appareil photo du S25 : le fichier se télécharge.", color = Ink, fontSize = 14.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(url, color = Muted, fontSize = 12.sp)
                            } else {
                                Text("Pas de Wi-Fi : active le partage de connexion du S25.", color = Red, fontSize = 14.sp)
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = onCreate, colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = OnAccent)) {
                                Text("Recréer le fichier", fontWeight = FontWeight.Bold)
                            }
                            Button(onClick = { onShare(result) }, colors = ButtonDefaults.buttonColors(containerColor = Navy, contentColor = AccentOnDark)) {
                                Text("Partager…")
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Garde cette fenêtre ouverte pendant le téléchargement.", color = Muted, fontSize = 12.sp)
                    savedAt?.let { Spacer(Modifier.height(6.dp)); Text("Copie aussi dans : $it", color = Muted, fontSize = 12.sp) }
                }
            }
        }
    }
}

/** Bouton d'outil : jaune quand c'est son résultat qui est affiché, sombre sinon. */
@Composable
private fun toolColors(active: Boolean) =
    if (active) ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = OnAccent)
    else ButtonDefaults.buttonColors(containerColor = CardSolid, contentColor = AccentText)
