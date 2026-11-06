package com.koala.koackpit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DiagScreen(server) { zip = it } }
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }
}

private const val LIMIT_MB = 300

@Composable
fun DiagScreen(server: FileServer, onZip: (File) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val upd = remember { UpdateState() }
    val apps = remember { mutableStateListOf<AppEntry>() }
    val selected = remember { mutableStateListOf<String>() }
    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var step by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<File?>(null) }
    var savedAt by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf<String?>(null) }
    var rootLog by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { Diag.apps(ctx) }
        apps.addAll(list)
        selected.addAll(list.filter { it.carInfo || it.vendor }.map { it.pkg })
        loading = false
        runCatching { upd.info = Updater.check() }
    }

    val totalMb = apps.filter { it.pkg in selected }.sumOf { it.size } / 1_048_576
    val carInfo = apps.firstOrNull { it.carInfo }

    Column(Modifier.fillMaxSize().background(Navy).padding(16.dp)) {
        UpdateBanner(upd)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Koackpit", color = Yellow, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(12.dp))
            Text("Diagnostic ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    rootLog = "Test en cours (10 s max)…"
                    scope.launch { rootLog = withContext(Dispatchers.IO) { RootAccess.test(ctx) } }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Navy),
            ) { Text("Tester l'accès système", fontWeight = FontWeight.Black) }
        }
        if (rootLog.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                rootLog, color = Color.White, fontSize = 13.sp, lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).padding(10.dp),
            )
        }
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Liste des applis
            Column(Modifier.weight(1.1f).fillMaxHeight()) {
                Text(
                    when {
                        loading -> "Recherche des applis…"
                        carInfo != null -> "Car Info trouvée : ${carInfo.pkg}"
                        else -> "Car Info non reconnue automatiquement : coche-la dans la liste"
                    },
                    color = if (carInfo != null || loading) Color.White else Red, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                )
                Text("Applis cochées : $totalMb Mo", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Card)) {
                    items(apps, key = { it.pkg }) { a ->
                        val on = a.pkg in selected
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = progress == null) {
                                if (on) selected.remove(a.pkg) else selected.add(a.pkg)
                            }.padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = on, onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Yellow, checkmarkColor = Navy, uncheckedColor = Muted),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(
                                    a.label + if (a.carInfo) "  ★" else "",
                                    color = if (a.carInfo) Yellow else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                )
                                Text(a.pkg, color = Muted, fontSize = 11.sp)
                            }
                            Text("${a.size / 1_048_576} Mo", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Actions
            Column(Modifier.weight(0.9f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Card).padding(16.dp)) {
                Text("1. Créer le fichier", color = Yellow, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(
                    "Les applis du fabricant sont déjà cochées. Rien n'est modifié sur l'autoradio : on fait juste une copie.",
                    color = Color.White, fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                val p = progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth(), color = Yellow, trackColor = Navy)
                    Text("${(p * 100).toInt()} % — $step", color = Muted, fontSize = 12.sp)
                } else {
                    Button(
                        enabled = !loading && selected.isNotEmpty() && totalMb <= LIMIT_MB,
                        onClick = {
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
                                    url = Diag.ip()?.takeIf { port > 0 }?.let { "http://$it:$port" }
                                }
                                progress = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Navy),
                    ) { Text(if (result == null) "Créer le fichier" else "Recréer le fichier", fontWeight = FontWeight.Bold) }
                    if (totalMb > LIMIT_MB) Text("Trop lourd : décoche des applis (max $LIMIT_MB Mo)", color = Red, fontSize = 12.sp)
                }

                result?.let { f ->
                    Spacer(Modifier.height(14.dp))
                    Text("2. Le récupérer sur le S25", color = Yellow, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text("Fichier prêt : ${f.length() / 1_048_576} Mo", color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    if (url != null) {
                        Text("Sur le S25, ouvre Firefox à cette adresse :", color = Color.White, fontSize = 13.sp)
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(Navy).padding(10.dp)) {
                            Text(url!!, color = Yellow, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        }
                        Text("Garde Koackpit ouvert pendant le téléchargement.", color = Muted, fontSize = 12.sp)
                    } else {
                        Text("Pas de Wi-Fi : active le partage de connexion du S25.", color = Red, fontSize = 13.sp)
                    }
                    savedAt?.let { Text("Copie aussi dans : $it", color = Muted, fontSize = 12.sp) }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
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
                        colors = ButtonDefaults.buttonColors(containerColor = Navy, contentColor = Yellow),
                    ) { Text("Ou partager…") }
                }
            }
        }
    }
}
