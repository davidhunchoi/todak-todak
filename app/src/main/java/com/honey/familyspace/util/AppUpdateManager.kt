package com.honey.familyspace.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 인앱 원터치 자체 자동 업데이트 매니저 (구글 플레이스토어 심사/비용 불필요)
 */
object AppUpdateManager {

    private const val VERSION_CHECK_URL = "https://todak-todak.onrender.com/api/version"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )

    /**
     * 서버에 새 버전이 있는지 확인
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }

            val request = Request.Builder()
                .url(VERSION_CHECK_URL)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val resBody = response.body?.string() ?: return@withContext null
            val json = JSONObject(resBody)

            val serverVersionCode = json.optInt("version_code", 1)
            val versionName = json.optString("version_name", "1.0.0")
            val apkUrl = json.optString("apk_url", "")
            val changelog = json.optString("changelog", "새로운 편의 기능이 업데이트되었습니다.")

            if (serverVersionCode > currentVersionCode && apkUrl.isNotBlank()) {
                UpdateInfo(
                    hasUpdate = true,
                    latestVersionCode = serverVersionCode,
                    versionName = versionName,
                    apkUrl = apkUrl,
                    changelog = changelog
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 최신 APK 다운로드 및 원터치 설치 창 호출
     */
    fun startDownloadAndInstall(context: Context, apkUrl: String) {
        // 안드로이드 8.0+ 출처를 알 수 없는 앱 설치 권한 체크
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "앱 업데이트를 위해 설치 권한을 허용해 주세요.", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }

        Toast.makeText(context, "새 버전을 다운로드하고 있습니다... ⏳", Toast.LENGTH_SHORT).show()

        val fileName = "todak-todak-update.apk"
        val destinationFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("토닥토닥 업데이트 다운로드")
            setDescription("최신 버전 앱을 다운로드 중입니다.")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(destinationFile))
            setMimeType("application/vnd.android.package-archive")
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    try {
                        ctxt.unregisterReceiver(this)
                    } catch (_: Exception) {}

                    installApk(ctxt, destinationFile)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                onComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                onComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun installApk(context: Context, file: File) {
        if (!file.exists()) return

        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(installIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "설치 화면을 여는 중 오류가 발생했습니다.", Toast.LENGTH_LONG).show()
        }
    }
}
