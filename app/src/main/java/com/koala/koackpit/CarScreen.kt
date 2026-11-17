package com.koala.koackpit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Locale

private val Green = Color(0xFF2ECC71)
private val dayFmt get() = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
private val monthFmt get() = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)
private fun km(n: Int) = String.format(Locale.FRANCE, "%,d km", n).replace(' ', ' ').replace(' ', ' ')

/** Écran « Voiture » : la Golf (ouvrants en direct) et le carnet d'entretien. */
@Composable
fun CarScreen(onBack: () -> Unit, onCarInfo: () -> Unit) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf(Carnet.load(ctx)) }
    fun update(d: Data) { data = d; Carnet.save(ctx, d) }
    var adding by remember { mutableStateOf<Kind?>(null) }        // dialogue d'ajout (type présélectionné)
    var showAdd by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Entry?>(null) }
    var editKm by remember { mutableStateOf(false) }

    CarScreenContent(
        data, CanBus.doors.value, onBack, onCarInfo,
        onAdd = { k -> adding = k; showAdd = true },
        onEntry = { removing = it },
        onOdometer = { editKm = true },
    )

    if (showAdd) AddDialog(data, adding, onDismiss = { showAdd = false }) { e, kind ->
        val kinds = data.kinds.map { if (it.id == kind.id) kind else it }
        update(Data(kinds, (data.entries + listOfNotNull(e)).sortedByDescending { it.date }, maxOf(data.odometer, e?.km ?: 0)))
        showAdd = false
    }
    removing?.let { e ->
        Confirm(
            "Supprimer cet entretien ?",
            "${data.kinds.firstOrNull { it.id == e.kind }?.name ?: e.kind} — ${dayFmt.format(e.date)} — ${km(e.km)}",
            "Supprimer", onDismiss = { removing = null },
        ) { update(data.copy(entries = data.entries - e)); removing = null }
    }
    if (editKm) NumberDialog("Kilométrage au compteur", data.odometer, onDismiss = { editKm = false }) {
        update(data.copy(odometer = it)); editKm = false
    }
}

private typealias Data = Carnet.Data

@Composable
fun CarScreenContent(
    data: Carnet.Data, doors: Int, onBack: () -> Unit, onCarInfo: () -> Unit,
    onAdd: (Kind?) -> Unit, onEntry: (Entry) -> Unit, onOdometer: () -> Unit,
) {
    val dues = remember(data) { Carnet.dues(data) }
    Column(Modifier.fillMaxSize().background(Navy)) {
        // En-tête
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Card).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, "Retour", tint = Yellow, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text("Voiture", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            Text(
                "Car Info d'origine", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Card).clickable(onClick = onCarInfo).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // La Golf
            Column(
                Modifier.width(200.dp).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Card).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CarTopView(doors, Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (doors == 0) "Tout est fermé" else Doors.label(doors),
                    color = if (doors == 0) Muted else Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Navy).clickable(onClick = onOdometer).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Compteur", color = Muted, fontSize = 11.sp)
                    Text(if (data.odometer > 0) km(data.odometer) else "à renseigner", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
            }
            // Carnet d'entretien
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Card).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Entretien", color = Yellow, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "+  Ajouter", color = Navy, fontSize = 14.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Yellow).clickable { onAdd(null) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(dues, key = { "d" + it.kind.id }) { d -> DueRow(d) { onAdd(d.kind) } }
                    if (data.entries.isNotEmpty()) item(key = "h") {
                        Text("Historique", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    }
                    items(data.entries, key = { "e" + it.kind + it.date + it.km }) { e ->
                        EntryRow(e, data.kinds.firstOrNull { it.id == e.kind }?.name ?: e.kind) { onEntry(e) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DueRow(d: Due, onClick: () -> Unit) {
    val (color, label) = when (d.state) {
        DueState.LATE -> Red to "Dépassé"
        DueState.SOON -> Yellow to "Bientôt"
        DueState.OK -> Green to "OK"
        DueState.NEVER -> Muted to "À renseigner"
    }
    val next = listOfNotNull(d.dueKm?.let { km(it) }, d.dueDate?.let { monthFmt.format(it) }).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Navy).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.kind.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                if (d.last == null) "Jamais renseigné — touche pour l'ajouter" else "Prochain : $next",
                color = Muted, fontSize = 12.sp,
            )
        }
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun EntryRow(e: Entry, name: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dayFmt.format(e.date), color = Muted, fontSize = 13.sp, modifier = Modifier.width(88.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (e.note.isNotBlank()) Text(e.note, color = Muted, fontSize = 12.sp)
        }
        Text(km(e.km), color = Color.White, fontSize = 13.sp)
    }
}

// ---------- Dialogues ----------

@Composable
private fun Sheet(content: @Composable () -> Unit) {
    Column(Modifier.width(520.dp).clip(RoundedCornerShape(20.dp)).background(CardHi).border(1.dp, Yellow.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).padding(18.dp)) {
        content()
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier, number: Boolean = false, onChange: (String) -> Unit) {
    Column(modifier) {
        Text(label, color = Muted, fontSize = 12.sp)
        BasicTextField(
            value, { onChange(if (number) it.filter(Char::isDigit).take(7) else it.take(80)) },
            singleLine = true, cursorBrush = SolidColor(Yellow),
            textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold),
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Navy).padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Buttons(ok: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
        Text("Annuler", color = Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 10.dp))
        Spacer(Modifier.width(8.dp))
        Text(ok, color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.clip(RoundedCornerShape(50)).background(Yellow).clickable(onClick = onOk).padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

/** Ajout d'un entretien (et réglage de l'intervalle de ce type). */
@Composable
private fun AddDialog(data: Carnet.Data, preset: Kind?, onDismiss: () -> Unit, onSave: (Entry?, Kind) -> Unit) {
    var kind by remember { mutableStateOf(preset ?: data.kinds.first()) }
    var date by remember { mutableStateOf(dayFmt.format(System.currentTimeMillis())) }
    var kmTxt by remember { mutableStateOf(if (data.odometer > 0) data.odometer.toString() else "") }
    var note by remember { mutableStateOf("") }
    var ivKm by remember(kind) { mutableStateOf(if (kind.km > 0) kind.km.toString() else "") }
    var ivMonths by remember(kind) { mutableStateOf(if (kind.months > 0) kind.months.toString() else "") }
    var error by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Sheet {
            Text("Ajouter un entretien", color = Yellow, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(data.kinds, key = { it.id }) { k ->
                    val on = k.id == kind.id
                    Text(
                        k.name, color = if (on) Navy else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Yellow else Navy).clickable { kind = k }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Date (jj/mm/aaaa)", date, Modifier.weight(1f)) { date = it }
                Field("Kilométrage", kmTxt, Modifier.weight(1f), number = true) { kmTxt = it }
            }
            Spacer(Modifier.height(8.dp))
            Field("Note (garage, pièces, prix…)", note, Modifier.fillMaxWidth()) { note = it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Intervalle (km, vide = aucun)", ivKm, Modifier.weight(1f), number = true) { ivKm = it }
                Field("Intervalle (mois)", ivMonths, Modifier.weight(1f), number = true) { ivMonths = it }
            }
            if (error.isNotEmpty()) Text(error, color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Buttons("Enregistrer", onDismiss) {
                val d = runCatching { dayFmt.apply { isLenient = false }.parse(date)?.time }.getOrNull()
                val k = kmTxt.toIntOrNull()
                if (d == null) { error = "Date invalide (ex. 17/11/2026)"; return@Buttons }
                if (k == null) { error = "Kilométrage manquant"; return@Buttons }
                onSave(Entry(kind.id, d, k, note.trim()), kind.copy(km = ivKm.toIntOrNull() ?: 0, months = ivMonths.toIntOrNull() ?: 0))
            }
        }
    }
}

@Composable
private fun NumberDialog(title: String, value: Int, onDismiss: () -> Unit, onOk: (Int) -> Unit) {
    var txt by remember { mutableStateOf(if (value > 0) value.toString() else "") }
    Dialog(onDismissRequest = onDismiss) {
        Sheet {
            Text(title, color = Yellow, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            Field("Kilomètres", txt, Modifier.fillMaxWidth(), number = true) { txt = it }
            Buttons("Enregistrer", onDismiss) { txt.toIntOrNull()?.let(onOk) }
        }
    }
}

@Composable
private fun Confirm(title: String, text: String, ok: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Sheet {
            Text(title, color = Yellow, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(text, color = Color.White, fontSize = 15.sp)
            Buttons(ok, onDismiss, onOk)
        }
    }
}
