package com.koala.koackpit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** Un type d'entretien et son intervalle (modifiable). 0 = pas d'intervalle. */
data class Kind(val id: String, val name: String, val km: Int, val months: Int)

/** Un entretien fait : type, date (jj/mm/aaaa → millisecondes, 0 = date inconnue), kilométrage, note. */
data class Entry(val kind: String, val date: Long, val km: Int, val note: String = "")

enum class DueState { OK, SOON, LATE, NEVER }

/** Prochaine échéance d'un type d'entretien. */
data class Due(val kind: Kind, val last: Entry?, val dueKm: Int?, val dueDate: Long?, val state: DueState)

/**
 * Carnet d'entretien de la Golf, gardé sur l'autoradio (fichier entretien.json).
 * Intervalles de départ courants pour une Golf 5 : indicatifs, chacun est modifiable dans l'écran.
 */
object Carnet {
    val DEFAULT_KINDS = listOf(
        Kind("vidange", "Vidange + filtre à huile", 15_000, 12),
        Kind("air", "Filtre à air", 30_000, 24),
        Kind("habitacle", "Filtre d'habitacle", 15_000, 12),
        Kind("carburant", "Filtre à carburant", 60_000, 48),
        Kind("freins_av", "Plaquettes avant", 0, 0),
        Kind("liquide_frein", "Liquide de frein", 0, 24),
        Kind("distribution", "Courroie de distribution", 120_000, 60),
        Kind("pneus", "Pneus", 0, 0),
        Kind("ct", "Contrôle technique", 0, 24),
    )

    data class Data(val kinds: List<Kind>, val entries: List<Entry>, val odometer: Int)

    private fun file(ctx: Context) = File(ctx.filesDir, "entretien.json")

    fun load(ctx: Context): Data = runCatching {
        val js = JSONObject(file(ctx).readText())
        val saved = js.optJSONObject("intervals")
        val kinds = DEFAULT_KINDS.map { k ->
            saved?.optJSONObject(k.id)?.let { o -> k.copy(km = o.optInt("km", k.km), months = o.optInt("months", k.months)) } ?: k
        }
        val arr = js.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { Entry(it.getString("kind"), it.getLong("date"), it.getInt("km"), it.optString("note")) }
        }
        Data(kinds, entries.sortedByDescending { it.km }, js.optInt("odometer", 0))
    }.getOrElse { Data(DEFAULT_KINDS, emptyList(), 0) }

    fun save(ctx: Context, d: Data) {
        val js = JSONObject()
        js.put("odometer", d.odometer)
        js.put("intervals", JSONObject().apply { d.kinds.forEach { put(it.id, JSONObject().put("km", it.km).put("months", it.months)) } })
        js.put("entries", JSONArray().apply {
            d.entries.forEach { put(JSONObject().put("kind", it.kind).put("date", it.date).put("km", it.km).put("note", it.note)) }
        })
        runCatching { file(ctx).writeText(js.toString()) }
    }

    private fun addMonths(t: Long, m: Int) = Calendar.getInstance().apply { timeInMillis = t; add(Calendar.MONTH, m) }.timeInMillis

    /** Échéances, les plus urgentes d'abord. « Bientôt » : moins de 1 500 km ou de 30 jours. */
    fun dues(d: Data, now: Long = System.currentTimeMillis()): List<Due> = d.kinds.filter { it.km > 0 || it.months > 0 }.map { k ->
        // Le dernier = le plus haut kilométrage (la date peut être inconnue)
        val last = d.entries.filter { it.kind == k.id }.maxByOrNull { it.km }
        if (last == null) Due(k, null, null, null, DueState.NEVER) else {
            val dueKm = if (k.km > 0) last.km + k.km else null
            val dueDate = if (k.months > 0 && last.date > 0) addMonths(last.date, k.months) else null
            val late = (dueKm != null && d.odometer >= dueKm) || (dueDate != null && now >= dueDate)
            val soon = (dueKm != null && d.odometer >= dueKm - 1500) || (dueDate != null && now >= dueDate - 30L * 86_400_000)
            Due(k, last, dueKm, dueDate, if (late) DueState.LATE else if (soon) DueState.SOON else DueState.OK)
        }
    }.sortedBy { listOf(DueState.LATE, DueState.SOON, DueState.NEVER, DueState.OK).indexOf(it.state) }
}
