package com.example.energy

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

private const val RELEASE_API = "https://api.github.com/repos/AlexandrVK/energy-widget/releases/latest"
private const val UPDATE_WORK = "energy_update"

class ReleaseInfo(val build: Int, val apkUrl: String)

fun installedBuild(ctx: Context): Int =
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()

fun installedVersionName(ctx: Context): String =
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

// Последний релиз с GitHub. Номер сборки берётся из названия релиза «build N».
fun fetchLatestRelease(): ReleaseInfo? {
    val c = URL(RELEASE_API).openConnection() as HttpURLConnection
    c.setRequestProperty("User-Agent", "energy-widget")
    c.setRequestProperty("Accept", "application/vnd.github+json")
    c.connectTimeout = 5_000
    c.readTimeout = 5_000
    try {
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val build = Regex("\\d+").find(json.getString("name"))?.value?.toIntOrNull() ?: return null
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                return ReleaseInfo(build, a.getString("browser_download_url"))
            }
        }
        return null
    } finally {
        c.disconnect()
    }
}

// Проверяет релиз и сохраняет результат. null — GitHub не ответил или APK не найден.
fun checkForUpdate(ctx: Context): ReleaseInfo? {
    val info = try {
        fetchLatestRelease()
    } catch (e: Exception) {
        return null
    }
    if (info != null) {
        prefs(ctx).edit()
            .putInt("remote_build", info.build)
            .putString("remote_url", info.apkUrl)
            .putLong("update_checked_at", System.currentTimeMillis())
            .apply()
    }
    return info
}

// Скачивает APK в кэш приложения и возвращает адрес для установщика
fun downloadApk(ctx: Context, url: String): Uri {
    val file = File(ctx.cacheDir, "update.apk")
    val c = URL(url).openConnection() as HttpURLConnection
    c.instanceFollowRedirects = true
    c.connectTimeout = 10_000
    c.readTimeout = 30_000
    try {
        c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
    } finally {
        c.disconnect()
    }
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
}

fun installIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        checkForUpdate(applicationContext)
        return Result.success()
    }
}

// Проверка раз в сутки в фоне
fun scheduleUpdateCheck(ctx: Context) {
    val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        UPDATE_WORK, ExistingPeriodicWorkPolicy.KEEP, req
    )
}
