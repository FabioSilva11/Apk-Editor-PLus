package com.saas.apkeditorplus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ApkBuildService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var activeJobId: String? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Reconstrução de APK", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            activeJobId?.let { update(it, BuildJobStore.Status.CANCELLED, "Reconstrução cancelada") }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val jobId = intent.getStringExtra(EXTRA_JOB_ID).orEmpty()
        if (jobId.isBlank() || activeJobId != null) return START_NOT_STICKY
        activeJobId = jobId
        cancelled.set(false)
        startForeground(NOTIFICATION_ID, notification(jobId, "Preparando reconstrução…", true))
        executor.execute { runBuild(intent, jobId) }
        return START_REDELIVER_INTENT
    }

    private fun runBuild(intent: Intent, jobId: String) {
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        var targetPackage = packageManager.getPackageArchiveInfo(apkPath, 0)?.packageName.orEmpty()
        try {
            val replacementsBundle = intent.getBundleExtra(EXTRA_REPLACEMENTS) ?: Bundle()
            val replacements = replacementsBundle.keySet().associateWith {
                File(replacementsBundle.getString(it).orEmpty())
            }
            val deletions = intent.getStringArrayListExtra(EXTRA_DELETIONS)?.toSet().orEmpty()
            val unsignedApk = File(cacheDir, "build_jobs/$jobId/unsigned.apk").apply {
                parentFile?.mkdirs()
            }
            progress(jobId, getString(R.string.rebuilding_apk))
            ApkBuildPipeline.rebuild(
                context = applicationContext,
                sourceApk = File(apkPath),
                outputApk = unsignedApk,
                changes = ApkBuildPipeline.ChangeSet(replacements, deletions),
                onProgress = { message -> checkCancelled(); progress(jobId, message) }
            )
            checkCancelled()
            targetPackage = packageManager.getPackageArchiveInfo(unsignedApk.absolutePath, 0)?.packageName
                ?: targetPackage
            progress(jobId, getString(R.string.signing_apk))

            val prefs = AppSettings.prefs(this)
            val outputDir = getExternalFilesDir(null) ?: filesDir
            val pattern = prefs.getString(AppSettings.OUTPUT_APK_NAME, "{package}_mod.apk") ?: "{package}_mod.apk"
            val safePackage = targetPackage.ifBlank { "modded" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outputName = pattern.replace("{package}", safePackage)
                .replace(Regex("[\\/:*?\"<>|]"), "_")
                .let { if (it.endsWith(".apk", true)) it else "$it.apk" }
            val overwrite = prefs.getString(AppSettings.FILE_RENAME_MODE, "auto") == "overwrite"
            val signedApk = AppSettings.exportTarget(outputDir, outputName, overwrite)
            if (signedApk.exists()) check(signedApk.delete()) { "Não foi possível substituir o APK de saída" }

            val signed = ApkSignerManager().signApk(
                inputApk = unsignedApk,
                outputApk = signedApk,
                keyStoreFile = File(intent.getStringExtra(EXTRA_KEYSTORE).orEmpty()),
                keyStorePassword = intent.getStringExtra(EXTRA_STORE_PASSWORD).orEmpty().toCharArray(),
                keyAlias = intent.getStringExtra(EXTRA_ALIAS).orEmpty(),
                keyPassword = intent.getStringExtra(EXTRA_KEY_PASSWORD).orEmpty().toCharArray(),
                enableV1 = prefs.getBoolean(AppSettings.SIGN_V1, true),
                enableV2 = prefs.getBoolean(AppSettings.SIGN_V2, true),
                enableV3 = prefs.getBoolean(AppSettings.SIGN_V3, true),
                enableV4 = prefs.getBoolean(AppSettings.SIGN_V4, false),
                listener = object : ApkSignerManager.SignerListener {
                    override fun onStart() = Unit
                    override fun onProgress(message: String) { checkCancelled(); progress(jobId, message) }
                    override fun onSuccess() = Unit
                    override fun onError(message: String) = Unit
                }
            )
            check(signed && signedApk.isFile && signedApk.length() > 0L) { "Falha ao assinar o APK" }
            update(
                jobId,
                BuildJobStore.Status.SUCCESS,
                "APK salvo em:\n${signedApk.absolutePath}",
                signedApk.absolutePath,
                targetPackage
            )
        } catch (cancel: BuildCancelledException) {
            update(jobId, BuildJobStore.Status.CANCELLED, "Reconstrução cancelada", packageName = targetPackage)
        } catch (error: Exception) {
            update(
                jobId,
                BuildJobStore.Status.FAILED,
                getString(R.string.error_during_build, error.message),
                packageName = targetPackage
            )
        } finally {
            activeJobId = null
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw BuildCancelledException()
    }

    private fun progress(jobId: String, detail: String) {
        update(jobId, BuildJobStore.Status.RUNNING, detail)
    }

    private fun update(
        jobId: String,
        status: BuildJobStore.Status,
        detail: String,
        outputPath: String = "",
        packageName: String = ""
    ) {
        val state = BuildJobStore.State(jobId, status, detail, outputPath, packageName)
        BuildJobStore.write(this, state)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(jobId, detail, status == BuildJobStore.Status.RUNNING)
        )
        sendBroadcast(Intent(ACTION_STATE).setPackage(this.packageName).apply {
            putExtra(EXTRA_JOB_ID, jobId)
        })
    }

    private fun notification(jobId: String, detail: String, ongoing: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (ongoing) "Reconstruindo APK" else "Reconstrução finalizada")
            .setContentText(detail.lineSequence().firstOrNull().orEmpty())
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setProgress(0, 0, ongoing)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    jobId.hashCode(),
                    Intent(this, ApkCreateActivity::class.java).apply {
                        putExtra(EXTRA_JOB_ID, jobId)
                        putExtra(EXTRA_OBSERVE_ONLY, true)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .apply {
                if (ongoing) addAction(
                    0,
                    "Cancelar",
                    PendingIntent.getService(
                        this@ApkBuildService,
                        jobId.hashCode(),
                        Intent(this@ApkBuildService, ApkBuildService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            .build()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class BuildCancelledException : RuntimeException()

    companion object {
        const val ACTION_START = "com.saas.apkeditorplus.action.BUILD_START"
        const val ACTION_CANCEL = "com.saas.apkeditorplus.action.BUILD_CANCEL"
        const val ACTION_STATE = "com.saas.apkeditorplus.action.BUILD_STATE"
        const val EXTRA_JOB_ID = "buildJobId"
        const val EXTRA_OBSERVE_ONLY = "observeBuildOnly"
        const val EXTRA_APK_PATH = "apkPath"
        const val EXTRA_REPLACEMENTS = "modifiedFiles"
        const val EXTRA_DELETIONS = "deletedEntries"
        const val EXTRA_KEYSTORE = "keyStorePath"
        const val EXTRA_STORE_PASSWORD = "keyStorePassword"
        const val EXTRA_ALIAS = "keyAlias"
        const val EXTRA_KEY_PASSWORD = "keyPassword"
        private const val CHANNEL_ID = "apk_build"
        private const val NOTIFICATION_ID = 4512
    }
}
