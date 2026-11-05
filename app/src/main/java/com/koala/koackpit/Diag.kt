package com.koala.koackpit

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class AppEntry(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val apks: List<String>,
    val size: Long,
    val carInfo: Boolean,
    val vendor: Boolean,
)

object Diag {
    const val ZIP_NAME = "koackpit-diag.zip"

    /** Préfixes des applis Android/Google standard : inutiles pour trouver le canal CAN du fabricant. */
    private val STANDARD = listOf(
        "com.android.", "com.google.", "android.ext.", "android.auto_generated", "com.qualcomm.", "com.svox.",
        "org.chromium.", "com.koala.", "com.breel.", "com.example.android.",
    )

    private fun isStandard(pkg: String) = pkg == "android" || STANDARD.any { pkg.startsWith(it) }

    fun apps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0).map { ai ->
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName)
            val sys = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val apks = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
            val size = apks.sumOf { File(it).length() }
            val flat = (label + " " + ai.packageName).lowercase().replace(" ", "")
            val car = "carinfo" in flat || "canbus" in flat
            AppEntry(ai.packageName, label, sys, apks, size, car, sys && !isStandard(ai.packageName))
        }.sortedWith(
            compareByDescending<AppEntry> { it.carInfo }.thenByDescending { it.vendor }.thenBy { it.pkg }
        )
    }

    private fun sh(cmd: String, limit: Int = 300_000): String = runCatching {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val t = Thread {
            runCatching {
                p.inputStream.bufferedReader().useLines { lines ->
                    for (l in lines) {
                        if (out.length > limit) break
                        out.appendLine(l)
                    }
                }
            }
        }.apply { start() }
        if (!p.waitFor(8, TimeUnit.SECONDS)) p.destroy()
        t.join(2000)
        out.toString()
    }.getOrElse { "ERREUR: $it\n" }

    private fun readFile(path: String, limit: Int = 300_000) = runCatching {
        File(path).readText().take(limit)
    }.getOrElse { "illisible: $it\n" }

    private fun StringBuilder.section(title: String, body: String) {
        appendLine()
        appendLine("==================== $title")
        append(body)
        if (!body.endsWith("\n")) appendLine()
    }

    private fun details(pm: PackageManager, pkg: String): String = buildString {
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
        val pi = runCatching { pm.getPackageInfo(pkg, flags) }.getOrElse {
            return "  (détails illisibles: $it)\n"
        }
        appendLine("  version=${pi.versionName} (${pi.longVersionCode}) sharedUid=${pi.sharedUserId}")
        pi.applicationInfo?.let { appendLine("  dir=${it.sourceDir} uid=${it.uid} process=${it.processName}") }
        pi.requestedPermissions?.forEach { appendLine("  uses-permission $it") }
        pi.permissions?.forEach { appendLine("  defines-permission ${it.name} level=${it.protectionLevel}") }
        pi.activities?.forEach { appendLine("  activity ${it.name} exported=${it.exported}") }
        pi.services?.forEach { appendLine("  service ${it.name} exported=${it.exported} perm=${it.permission} process=${it.processName}") }
        pi.receivers?.forEach { appendLine("  receiver ${it.name} exported=${it.exported} perm=${it.permission}") }
        pi.providers?.forEach { appendLine("  provider ${it.name} authority=${it.authority} exported=${it.exported}") }
        pi.applicationInfo?.metaData?.let { md -> md.keySet().forEach { appendLine("  meta $it=${md.get(it)}") } }
    }

    fun report(ctx: Context, apps: List<AppEntry>, selected: Set<String>): String = buildString {
        val pm = ctx.packageManager
        appendLine("Koackpit diagnostic ${BuildConfig.VERSION_NAME} — ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(Date())}")
        section("BUILD", Build::class.java.fields.joinToString("\n") { f ->
            "${f.name}=${runCatching { f.get(null)?.let { if (it is Array<*>) it.joinToString() else it.toString() } }.getOrNull()}"
        })
        section("getprop", sh("getprop"))
        section("/system/build.prop", readFile("/system/build.prop"))
        section("/vendor/build.prop", readFile("/vendor/build.prop"))
        section("ls /dev", sh("ls -la /dev"))
        for (dir in listOf("/system/app", "/system/priv-app", "/vendor/app", "/vendor/priv-app", "/oem", "/oem/app",
            "/system/framework", "/vendor/framework", "/system/lib", "/system/lib64", "/vendor/lib", "/vendor/lib64",
            "/system/etc/init", "/vendor/etc/init", "/system/bin", "/vendor/bin")) {
            section("ls $dir", sh("ls -la $dir"))
        }
        section("service list", sh("service list"))
        section("ps", sh("ps -A"))
        section("mount", sh("cat /proc/mounts"))

        val b = StringBuilder()
        for (a in apps) {
            b.appendLine("${if (a.carInfo) "[CARINFO] " else ""}${if (a.vendor) "[FABRICANT] " else ""}${if (a.pkg in selected) "[EXPORTÉ] " else ""}" +
                "${a.pkg} « ${a.label} » sys=${a.system} ${a.size / 1024} Ko ${a.apks.joinToString()}")
            if (a.vendor || a.carInfo) b.append(details(pm, a.pkg))
        }
        section("PAQUETS (${apps.size})", b.toString())
    }

    /** Crée le zip (rapport + APK choisis) dans le cache de l'appli. */
    fun build(ctx: Context, apps: List<AppEntry>, selected: Set<String>, onProgress: (Float, String) -> Unit): File {
        onProgress(0f, "Rapport système…")
        val rep = report(ctx, apps, selected)
        val chosen = apps.filter { it.pkg in selected }
        val total = chosen.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, ZIP_NAME)
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("rapport.txt"))
            z.write(rep.toByteArray())
            z.closeEntry()
            val buf = ByteArray(128 * 1024)
            for (a in chosen) {
                a.apks.forEachIndexed { i, path ->
                    val f = File(path)
                    if (!f.canRead()) return@forEachIndexed
                    onProgress(done.toFloat() / total, a.label)
                    z.putNextEntry(ZipEntry("apks/${a.pkg}${if (i == 0) "" else "-split$i"}.apk"))
                    f.inputStream().use { inp ->
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            z.write(buf, 0, n)
                            done += n
                        }
                    }
                    z.closeEntry()
                }
            }
        }
        onProgress(1f, "Terminé")
        return out
    }

    /** Copie aussi le zip dans Téléchargements/Koackpit (pour une clé USB ou un gestionnaire de fichiers). */
    fun copyToDownloads(ctx: Context, f: File): String? = runCatching {
        val r = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Koackpit")
        }
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
        r.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        "Téléchargements/Koackpit/${f.name}"
    }.getOrNull()

    fun ip(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }?.hostAddress
    }.getOrNull()
}
