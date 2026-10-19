package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.hasPermission
import io.nekohasekai.sfa.utils.UserRoutingConfig
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    companion object {
        private const val PROFILE_UPDATE_INTERVAL = 15L * 60 * 1000 // 15 minutes in milliseconds
        private const val TAG = "BoxService"
        private const val SWITCH_TAG = "SFA.Switch"

        /** Optional explicit profile for R2b switch; absent/≤0 → Settings.selectedProfile. */
        const val EXTRA_TARGET_PROFILE_ID = "io.nekohasekai.sfa.extra.TARGET_PROFILE_ID"

        /** Optional switch generation; absent/≤0 → RuntimeProfileState.currentSwitchRequestId(). */
        const val EXTRA_SWITCH_REQUEST_ID = "io.nekohasekai.sfa.extra.SWITCH_REQUEST_ID"
        const val EXTRA_STOP_PROFILE_ID = "io.nekohasekai.sfa.extra.STOP_PROFILE_ID"
        const val EXTRA_STOP_REQUEST_ID = "io.nekohasekai.sfa.extra.STOP_REQUEST_ID"

        /**
         * Start VPN/proxy service.
         * @param targetProfileId profile to load; ≤0 keeps legacy Settings.selectedProfile
         * @param switchRequestId R2a generation for stale guards; ≤0 allocates a new manual-start id
         */
        fun start(targetProfileId: Long = -1L, switchRequestId: Long = -1L) {
            val requestId = if (switchRequestId > 0L) switchRequestId else RuntimeProfileState.nextSwitchRequestId()
            val intent =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        Intent(Application.application, Settings.serviceClass()).apply {
                            if (targetProfileId > 0L) {
                                putExtra(EXTRA_TARGET_PROFILE_ID, targetProfileId)
                            }
                            putExtra(EXTRA_SWITCH_REQUEST_ID, requestId)
                        }
                    }
                }
            ContextCompat.startForegroundService(Application.application, intent)
        }

        fun stop(expectedProfileId: Long = -1L, stopRequestId: Long = -1L) {
            Application.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(
                    Application.application.packageName,
                ).apply {
                    if (expectedProfileId > 0L) putExtra(EXTRA_STOP_PROFILE_ID, expectedProfileId)
                    if (stopRequestId > 0L) putExtra(EXTRA_STOP_REQUEST_ID, stopRequestId)
                },
            )
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(service)
    private lateinit var commandServer: CommandServer

    private var receiverRegistered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        val stopRequestId = intent.getLongExtra(EXTRA_STOP_REQUEST_ID, -1L)
                        if (stopRequestId > 0L &&
                            stopRequestId != RuntimeProfileState.currentSwitchRequestId()
                        ) {
                            return
                        }
                        val expectedProfileId = intent.getLongExtra(EXTRA_STOP_PROFILE_ID, -1L)
                        if (expectedProfileId > 0L &&
                            !RuntimeProfileState.shouldStopForDeletion(
                                expectedProfileId,
                                Settings.selectedProfile,
                                status.value != Status.Stopped,
                            )
                        ) {
                            return
                        }
                        // Notification and Quick Settings send an unscoped user Stop.
                        // Invalidate a start even while Starting: its completion takes
                        // quietStopAfterStaleStart instead of publishing Started.
                        if (stopRequestId <= 0L && expectedProfileId <= 0L) {
                            RuntimeProfileState.requestUserStop()
                        }
                        stopService()
                    }

                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            serviceUpdateIdleMode()
                        }
                    }
                }
            }
        }

    private fun startCommandServer() {
        Libbox.promoteOOMDraft()
        val commandServer = CommandServer(this, platformInterface)
        commandServer.start()
        this.commandServer = commandServer
    }

    private var lastProfileName = ""
    private val reloadMutex = Mutex()

    /** Serializes stop vs start body so start cannot overlap an in-flight stop. */
    private val lifecycleMutex = Mutex()

    private fun sanitizeRuntimeConfig(content: String): String = io.nekohasekai.sfa.utils.OutboundProfileState.runtimeConfig(
        content,
        Settings.routingBlockIpv6,
        Settings.tunStack,
        Settings.coreOptionsJson,
    )

    private suspend fun startService(requestId: Long, profileId: Long) {
        try {
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            val selectedProfileId = profileId
            if (selectedProfileId == -1L) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            if (!RuntimeProfileState.beginStart(requestId, selectedProfileId)) {
                quietStopAfterStaleStart()
                return
            }

            val profile = ProfileManager.get(selectedProfileId)
            if (profile == null) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val selected = org.json.JSONObject(Settings.outboundSelections)
                .optJSONObject(selectedProfileId.toString())?.toString().orEmpty()
            val content = sanitizeRuntimeConfig(
                io.nekohasekai.sfa.utils.OutboundProfileState.withSelections(
                    File(profile.typed.path).readText(),
                    selected,
                ),
            )
            if (content.isBlank()) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }
            val runtimeContent = io.nekohasekai.sfa.utils.RuleSetUpdater.ensureRuleSets(Application.application, UserRoutingConfig.applyToConfig(content, Settings.routingConfigJson), Settings.ruleSetUpdateInterval)

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            DefaultNetworkMonitor.start()

            if (!RuntimeProfileState.isStartCurrent(requestId, selectedProfileId)) {
                quietStopAfterStaleStart()
                return
            }

            try {
                commandServer.startOrReloadService(
                    runtimeContent,
                    OverrideOptions().apply {
                        autoRedirect = Settings.autoRedirect
                        if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
                            val appList = Settings.getEffectivePerAppProxyList()
                            if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                                includePackage =
                                    PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                            } else {
                                excludePackage =
                                    PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                            }
                        }
                    },
                )
            } catch (e: Exception) {
                stopAndAlert(Alert.CreateService, e.message)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) serviceUpdateIdleMode()

            if (commandServer.needWIFIState()) {
                val wifiPermission =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }
                if (!service.hasPermission(wifiPermission)) {
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            if (!RuntimeProfileState.tryMarkLoaded(requestId, selectedProfileId, runtimeContent)) {
                android.util.Log.w(
                    SWITCH_TAG,
                    "STALE_DISCARD requestId=$requestId current=${RuntimeProfileState.currentSwitchRequestId()} " +
                        "profileId=$selectedProfileId (start completion superseded)",
                )
                // Do not publish Started for a superseded generation.
                quietStopAfterStaleStart()
                return
            }
            // R2b: persist selected only after successful load of this generation.
            if (SwitchStartResolver.shouldPersistSelectedAfterLoad(
                    targetProfileId = selectedProfileId,
                    markLoadedSucceeded = true,
                    requestIdStillCurrent = requestId == RuntimeProfileState.currentSwitchRequestId(),
                )
            ) {
                Settings.selectedProfile = selectedProfileId
            }
            android.util.Log.i(
                SWITCH_TAG,
                "RUNTIME_LOADED requestId=$requestId profileId=$selectedProfileId " +
                    "fp=${RuntimeProfileState.loadedConfigFingerprint}",
            )
            status.postValue(Status.Started)
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_started)
            }
            notification.start()
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    override fun serviceStop() {
        notification.close()
        status.postValue(Status.Starting)
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        closeService()
    }

    override fun serviceReload() {
        runBlocking {
            serviceReload0()
        }
    }

    suspend fun serviceReload0() = reloadMutex.withLock {
        serviceReloadLocked()
    }

    private suspend fun serviceReloadLocked() {
        val selectedProfileId = Settings.selectedProfile
        if (selectedProfileId == -1L) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val profile = ProfileManager.get(selectedProfileId)
        if (profile == null) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val selected = org.json.JSONObject(Settings.outboundSelections)
            .optJSONObject(selectedProfileId.toString())?.toString().orEmpty()
        val content = sanitizeRuntimeConfig(
            io.nekohasekai.sfa.utils.OutboundProfileState.withSelections(
                File(profile.typed.path).readText(),
                selected,
            ),
        )
        if (content.isBlank()) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        val runtimeContent = io.nekohasekai.sfa.utils.RuleSetUpdater.ensureRuleSets(Application.application, UserRoutingConfig.applyToConfig(content, Settings.routingConfigJson), Settings.ruleSetUpdateInterval)
        lastProfileName = profile.name
        status.postValue(Status.Starting)
        withContext(Dispatchers.Main) {
            notification.show(lastProfileName, R.string.status_starting)
        }
        fileDescriptor?.close()
        fileDescriptor = null
        try {
            commandServer.startOrReloadService(
                runtimeContent,
                OverrideOptions().apply {
                    autoRedirect = Settings.autoRedirect
                    if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
                        val appList = Settings.getEffectivePerAppProxyList()
                        if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                            includePackage = PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                        } else {
                            excludePackage = PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                        }
                    }
                },
            )
        } catch (e: Exception) {
            stopAndAlert(Alert.CreateService, e.message)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) serviceUpdateIdleMode()

        if (commandServer.needWIFIState()) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }
        }
        val requestId = RuntimeProfileState.currentSwitchRequestId()
        if (!RuntimeProfileState.tryMarkLoaded(requestId, selectedProfileId, runtimeContent)) {
            android.util.Log.w(
                SWITCH_TAG,
                "STALE_DISCARD requestId=$requestId current=${RuntimeProfileState.currentSwitchRequestId()} " +
                    "profileId=$selectedProfileId (reload completion superseded)",
            )
            return
        }
        android.util.Log.i(
            SWITCH_TAG,
            "RUNTIME_LOADED requestId=$requestId profileId=$selectedProfileId " +
                "fp=${RuntimeProfileState.loadedConfigFingerprint}",
        )
        status.postValue(Status.Started)
        withContext(Dispatchers.Main) {
            notification.show(lastProfileName, R.string.status_started)
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? {
        val status = SystemProxyStatus()
        if (service is VPNService) {
            status.available = service.systemProxyAvailable
            status.enabled = service.systemProxyEnabled
        }
        return status
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        serviceReload()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun serviceUpdateIdleMode() {
        if (!::commandServer.isInitialized) return
        if (Application.powerManager.isDeviceIdleMode) {
            commandServer.pause()
        } else {
            commandServer.wake()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        if (status.value != Status.Started) return
        status.value = Status.Stopping
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        GlobalScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                val pfd = fileDescriptor
                if (pfd != null) {
                    pfd.close()
                    fileDescriptor = null
                }
                DefaultNetworkMonitor.stop()
                if (::commandServer.isInitialized) {
                    closeService()
                    commandServer.apply {
                        close()
                    }
                }
                PowerReportManager.refresh()
                Settings.startedByUser = false
                withContext(Dispatchers.Main) {
                    RuntimeProfileState.clear()
                    status.value = Status.Stopped
                    service.stopSelf()
                }
            }
        }
    }

    private fun closeService() {
        runCatching {
            commandServer.closeService()
        }.onFailure {
            commandServer.setError("android: close service: ${it.message}")
        }
    }

    /**
     * Tear down after a start completed with a superseded switchRequestId.
     * Does not broadcast a user-facing Alert (expected under rapid switch / timeout).
     */
    private suspend fun quietStopAfterStaleStart() {
        Settings.startedByUser = false
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        DefaultNetworkMonitor.stop()
        if (::commandServer.isInitialized) {
            closeService()
            runCatching { commandServer.close() }
        }
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            RuntimeProfileState.clear()
            status.value = Status.Stopped
            service.stopSelf()
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        Settings.startedByUser = false
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        DefaultNetworkMonitor.stop()
        if (::commandServer.isInitialized) {
            closeService()
            commandServer.close()
        }
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            RuntimeProfileState.clear()
            binder.broadcast { callback ->
                callback.onServiceAlert(type.ordinal, message)
            }
            status.value = Status.Stopped
            service.stopSelf()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(intent: Intent? = null): Int {
        if (status.value != Status.Stopped) return Service.START_NOT_STICKY
        status.value = Status.Starting

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    addAction(Action.SERVICE_CLOSE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        val intentTarget = intent?.getLongExtra(EXTRA_TARGET_PROFILE_ID, -1L) ?: -1L
        val intentSwitch = intent?.getLongExtra(EXTRA_SWITCH_REQUEST_ID, -1L) ?: -1L

        GlobalScope.launch(Dispatchers.IO) {
            lifecycleMutex.withLock {
                Settings.startedByUser = true
                val requestId =
                    SwitchStartResolver.resolveRequestId(
                        intentSwitch,
                        RuntimeProfileState.currentSwitchRequestId(),
                    )
                val profileId =
                    SwitchStartResolver.resolveProfileId(intentTarget, Settings.selectedProfile)
                if (profileId != -1L && !RuntimeProfileState.beginStart(requestId, profileId)) {
                    quietStopAfterStaleStart()
                    return@withLock
                }
                android.util.Log.i(
                    SWITCH_TAG,
                    "START_BEGIN requestId=$requestId profileId=$profileId " +
                        "intentTarget=$intentTarget",
                )
                try {
                    startCommandServer()
                } catch (e: Exception) {
                    stopAndAlert(Alert.StartCommandServer, e.message)
                    return@withLock
                }
                startService(requestId, profileId)
            }
        }
        return Service.START_NOT_STICKY
    }

    internal fun onBind(): IBinder = binder

    internal fun onDestroy() {
        binder.close()
    }

    internal fun onRevoke() {
        stopService()
    }

    internal fun sendNotification(notification: Notification) {
        val channel = "notification-${notification.typeID}"
        val builder =
            NotificationCompat.Builder(service, channel).setShowWhen(false)
                .setContentTitle(notification.title).setContentText(notification.body)
                .setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_menu)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).apply {
                        setAction(Action.OPEN_URL).setData(Uri.parse(notification.openURL))
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    },
                    ServiceNotification.flags,
                ),
            )
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Application.notification.createNotificationChannel(
                    NotificationChannel(
                        channel,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            Application.notification.notify(notification.identifier, notification.typeID, builder.build())
        }
    }

    internal fun cancelNotification(identifier: String, typeID: Int) {
        GlobalScope.launch(Dispatchers.Main) {
            Application.notification.cancel(identifier, typeID)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun triggerNativeCrash() {
        GlobalScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(200)
            throw RuntimeException("debug native crash")
        }
    }

    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message!!)
    }

    override fun connectSSHAgent(): Int = -1
}
