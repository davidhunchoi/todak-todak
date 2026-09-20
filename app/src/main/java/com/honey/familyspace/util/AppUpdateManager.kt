package com.honey.familyspace.util

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
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
 *
 * 주의: debug 서명 APK끼리는 같은 키로 덮어설치되지만,
 * 빌드 환경이 바뀌면 서명이 달라져 "설치 안 됨"이 뜰 수 있다.
 * 정식 배포는 항상 같은 release 키로 서명한 APK를 같은 릴리스 자산명으로 올려야 한다.
 */
object AppUpdateManager {

    private const val VERSION_CHECK_URL = "https://todak-todak-ruby.vercel.app/api/version"

    /** 설치 권한 화면을 다녀온 뒤 대기 중이던 APK URL (MainActivity.onResume에서 이어받기용) */
    @Volatile
    var pendingApkUrlAfterPermission: String? = null
        private set

    fun consumePendingApkUrl(): String? {
        val url = pendingApkUrlAfterPermission
        pendingApkUrlAfterPermission = null
        return url
    }

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
            // 신규 고정 자산명(app-release.apk) 우선, 없으면 구버전 서버의 fallback 사용
            var apkUrl = json.optString("apk_url", "")
            if (apkUrl.isBlank()) apkUrl = json.optString("apk_url_fallback", "")
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
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 최신 APK 다운로드 및 원터치 설치 창 호출
     * - 사용자가 팝업에서 [지금 업데이트]를 눌렀을 때만 호출할 것 (자동 호출 금지)
     */
    fun startDownloadAndInstall(context: Context, apkUrl: String) {
        // 안드로이드 8.0+ 출처를 알 수 없는 앱 설치 권한 체크
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                // 권한 화면을 다녀오면 MainActivity.onResume에서 이어서 다운로드하도록 URL 보관
                pendingApkUrlAfterPermission = apkUrl
                Toast.makeText(context, "앱 업데이트를 위해 다음 화면에서 '허용'을 눌러주세요. 🙏", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "설정 화면을 열 수 없습니다. 직접 '출처를 알 수 없는 앱 설치'를 허용해 주세요.", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        pendingApkUrlAfterPermission = null

        Toast.makeText(context, "새 버전을 다운로드하고 있습니다... ⏳", Toast.LENGTH_SHORT).show()

        val fileName = "todak-todak-update.apk"
        // getExternalFilesDir(DIRECTORY_DOWNLOADS)의 Download 하위 폴더 사용
        // (최상위 바로 쓰면 FileProvider 매핑 이슈로 설치 실패가 잦음)
        val downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Download")
        if (!downloadDir.exists()) downloadDir.mkdirs()
        val destinationFile = File(downloadDir, fileName)
        // 구버전 경로(Download 바로 아래)에 남은 반쪽짜리 파일 정리
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { oldDir ->
            val legacy = File(oldDir, fileName)
            if (legacy.exists() && legacy.absolutePath != destinationFile.absolutePath) {
                try { legacy.delete() } catch (e: Exception) {}
            }
        }
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
        val downloadId: Long
        try {
            downloadId = downloadManager.enqueue(request)
        } catch (e: Exception) {
            Toast.makeText(context, "다운로드를 시작할 수 없습니다. 저장 공간과 네트워크를 확인해 주세요.", Toast.LENGTH_LONG).show()
            return
        }

        val appContext = context.applicationContext
        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                try {
                    appContext.unregisterReceiver(this)
                } catch (e: Exception) {}

                // 다운로드 성공 여부 확인 후 설치 (중간에 끊긴 파일로 설치창 띄우면 "설치 안 됨")
                if (!isDownloadSuccessful(appContext, downloadId)) {
                    Toast.makeText(
                        appContext,
                        "다운로드가 중단되었습니다. 😢 와이파이 상태에서 다시 [지금 업데이트]를 눌러주세요.",
                        Toast.LENGTH_LONG
                    ).show()
                    try { destinationFile.delete() } catch (e: Exception) {}
                    return
                }
                installApk(appContext, destinationFile)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                onComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            appContext.registerReceiver(
                onComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun isDownloadSuccessful(context: Context, downloadId: Long): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var cursor: Cursor? = null
        return try {
            val query = DownloadManager.Query().setFilterById(downloadId)
            cursor = dm.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
                status == DownloadManager.STATUS_SUCCESSFUL
            } else {
                false
            }
        } catch (e: Exception) {
            false
        } finally {
            try { cursor?.close() } catch (e: Exception) {}
        }
    }

    private fun installApk(context: Context, file: File) {
        if (!file.exists() || file.length() < 1024 * 100) {
            Toast.makeText(context, "다운로드된 파일이 손상되었습니다. 다시 시도해 주세요. 😢", Toast.LENGTH_LONG).show()
            return
        }

        val apkUri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Toast.makeText(context, "설치 파일을 열 수 없습니다. 앱을 다시 실행해 주세요.", Toast.LENGTH_LONG).show()
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(installIntent)
            Toast.makeText(context, "다운로드 완료! 🎉 다음 화면에서 [설치]를 눌러주세요.", Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "설치 화면을 열 수 없습니다. 파일 앱에서 todak-todak-update.apk를 직접 실행해 주세요.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "설치 화면을 여는 중 오류가 발생했습니다.", Toast.LENGTH_LONG).show()
        }
    }
}
