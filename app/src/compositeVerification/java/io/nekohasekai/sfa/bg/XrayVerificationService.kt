package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Verification-only protected Xray bridge owner.
 *
 * Compiled only when -PverifyCompositeJni=true. Does not change production
 * BoxService / sing-box executor selection. Intended for device gates D1–D4
 * (e.g. Realme GT 6) via explicit adb start/stop.
 */
class XrayVerificationService : android.net.VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var owner: XrayNetworkOwner? = null
    private var phaseJob: Job? = null
    private var tun: ParcelFileDescriptor? = null

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val config = loadConfig(intent)
                if (config == null) {
                    Log.e(TAG, "missing config: pass $EXTRA_CONFIG_PATH or $EXTRA_CONFIG_TEXT")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (prepare(this) != null) {
                    Log.e(TAG, "VPN permission not granted; open XrayVerificationPrepareActivity once")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground()
                if (!ensureMinimalTun()) {
                    Log.e(TAG, "failed to establish minimal verification TUN (needed for protect)")
                    stopSelf()
                    return START_NOT_STICKY
                }
                scope.launch { startOwner(config) }
                return START_STICKY
            }
            else -> {
                Log.w(TAG, "unknown action ${intent.action}")
                return START_NOT_STICKY
            }
        }
    }

    private suspend fun startOwner(config: String) {
        try {
            val manager = getSystemService(ConnectivityManager::class.java)
            val next = XrayNetworkOwner(this, manager)
            owner?.stop()
            owner = next
            phaseJob?.cancel()
            phaseJob =
                scope.launch {
                    next.phase.collectLatest { phase ->
                        Log.i(TAG, "phase=$phase")
                        updateNotification(phase.name)
                    }
                }
            Log.i(TAG, "owner start begin configBytes=${config.length}")
            next.start(config)
            Log.i(TAG, "owner start returned phase=${next.phase.value}")
        } catch (error: Exception) {
            Log.e(TAG, "owner start failed: ${error.javaClass.simpleName}: ${error.message}")
            shutdown()
            stopSelf()
        }
    }

    private fun loadConfig(intent: Intent?): String? {
        if (intent == null) return null
        val path = intent.getStringExtra(EXTRA_CONFIG_PATH)
        if (!path.isNullOrBlank()) {
            val candidates =
                listOf(
                    File(path),
                    File(filesDir, path),
                    File(getExternalFilesDir(null), path),
                )
            val file = candidates.firstOrNull { it.isFile }
            if (file == null) {
                Log.e(TAG, "config file not found")
                return null
            }
            return file.readText()
        }
        return intent.getStringExtra(EXTRA_CONFIG_TEXT)?.takeIf { it.isNotBlank() }
    }

    /** Empty routes: do not hijack device traffic; only enable VpnService.protect. */
    private fun ensureMinimalTun(): Boolean {
        if (tun != null) return true
        return try {
            val builder =
                Builder()
                    .setSession("XrayVerification")
                    .addAddress("10.255.255.1", 32)
                    .setMtu(1500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            tun = builder.establish()
            tun != null
        } catch (error: Exception) {
            Log.e(TAG, "TUN establish failed: ${error.message}")
            false
        }
    }

    private fun startInForeground() {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("starting"),
            foregroundServiceType(),
        )
    }

    private fun updateNotification(phase: String) {
        Application.notification.notify(NOTIFICATION_ID, buildNotification(phase))
    }

    private fun buildNotification(phase: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_menu)
            .setContentTitle("Xray verification")
            .setContentText(phase)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    pendingIntentFlags(),
                ),
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    getText(R.string.stop),
                    PendingIntent.getService(
                        this,
                        0,
                        Intent(this, XrayVerificationService::class.java).setAction(ACTION_STOP),
                        pendingIntentFlags(),
                    ),
                ).build(),
            )
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        Application.notification.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, "Xray verification", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun shutdown() {
        phaseJob?.cancel()
        phaseJob = null
        try {
            runBlocking { owner?.stop() }
        } catch (error: Exception) {
            Log.w(TAG, "owner stop: ${error.message}")
        }
        owner = null
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked")
        shutdown()
        stopSelf()
    }

    companion object {
        const val ACTION_START = "io.nekohasekai.sfa.action.XRAY_VERIFICATION_START"
        const val ACTION_STOP = "io.nekohasekai.sfa.action.XRAY_VERIFICATION_STOP"
        const val EXTRA_CONFIG_PATH = "config_path"
        const val EXTRA_CONFIG_TEXT = "config_text"

        private const val TAG = "XrayVerification"
        private const val NOTIFICATION_ID = 7741
        private const val NOTIFICATION_CHANNEL = "xray_verification"

        private fun pendingIntentFlags() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }

        private fun foregroundServiceType() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            } else {
                0
            }

        fun start(context: Context, configPath: String) {
            val intent =
                Intent(context, XrayVerificationService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CONFIG_PATH, configPath)
            ServiceCompat.startForegroundService(context, intent)
        }
    }
}
