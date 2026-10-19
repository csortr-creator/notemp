package io.nekohasekai.sfa.compose.screen.dashboard.groups

import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceDataStore
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundProbe
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.ScreenEvent
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.OfflineProbePlatform
import io.nekohasekai.sfa.utils.OutboundProfileState
import io.nekohasekai.sfa.utils.ProbeOutcome
import io.nekohasekai.sfa.utils.ProbeStatus
import io.nekohasekai.sfa.utils.ProfileLatencyCache
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

data class GroupsUiState(
    val groups: List<Group> = emptyList(),
    val profileId: Long = -1L,
    val subscriptionName: String = "",
    val isLoading: Boolean = false,
    val expandedGroups: Set<String> = emptySet(),
    val testingGroups: Set<String> = emptySet(),
    val showCloseConnectionsSnackbar: Boolean = false,
    val sortByPing: Boolean = true,
)

sealed class GroupsEvent : ScreenEvent {
    data class GroupSelected(val groupTag: String, val itemTag: String) : GroupsEvent()
}

class GroupsViewModel(private val sharedCommandClient: CommandClient? = null) :
    BaseViewModel<GroupsUiState, GroupsEvent>(),
    CommandClient.Handler,
    OnPreferenceDataStoreChangeListener {
    private val commandClient: CommandClient
    private val isUsingSharedClient: Boolean

    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus = _serviceStatus.asStateFlow()
    private var lastServiceStatus: Status = Status.Stopped
    private val testInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val probeMutex = Mutex()
    private val loadMutex = Mutex()
    private var loadedContent: String? = null

    @Volatile
    private var loadedFingerprints: Triple<Long, Map<String, String>, Long> =
        Triple(-1L, emptyMap(), 0L)

    private fun rememberLatency(
        profileId: Long,
        tag: String,
        fingerprint: String?,
        outcome: ProbeOutcome,
        time: Long,
    ) {
        if (fingerprint == null) return
        synchronized(latencyCache) {
            val next =
                ProfileLatencyCache.Result(
                    outcome.delayMs,
                    time,
                    outcome.status,
                )
            if (latencyCache.get(profileId, tag, fingerprint) == next) return
            latencyCache.put(
                profileId,
                tag,
                fingerprint,
                outcome.delayMs,
                time,
                outcome.status,
                outcome.detail,
            )
            Settings.outboundLatencyCache = latencyCache.encode()
        }
    }

    private fun logProbe(
        event: String,
        tag: String,
        path: String,
        outcome: ProbeOutcome? = null,
        durationMs: Long? = null,
        correlation: String,
    ) {
        val parts =
            buildList {
                add("event=$event")
                add("corr=$correlation")
                add("path=$path")
                add("tag=$tag")
                add("url=${ProbeOutcome.DEFAULT_PROBE_URL}")
                add("timeoutMs=${pingTimeoutMs()}")
                if (durationMs != null) add("durationMs=$durationMs")
                if (outcome != null) {
                    add("status=${outcome.status}")
                    add("delayMs=${outcome.delayMs}")
                    if (outcome.detail.isNotBlank()) add("detail=${outcome.detail.take(120)}")
                }
            }
        Log.i(TAG_PROBE, parts.joinToString(" "))
    }

    private var probeJob: Job? = null

    @Volatile private var probe: OutboundProbe? = null
    private val profileCallback: () -> Unit = { refreshSelectedProfile(force = true) }

    private fun canUseCommandServer() = RemoteControlManager.remoteServer.value != null || _serviceStatus.value == Status.Started

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == SettingsKey.SELECTED_PROFILE) refreshSelectedProfile(force = true)
        if (key == "outbound_sort_by_ping") updateState { copy(sortByPing = Settings.outboundSortByPing) }
    }

    private fun stopProbe() {
        probeJob?.cancel()
        probe?.let { runCatching { it.close() } }
        probe = null
    }

    private fun refreshSelectedProfile(force: Boolean = false) {
        if (RemoteControlManager.remoteServer.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            if (force || _serviceStatus.value != Status.Started) {
                if (probe != null) {
                    val selected = ProfileManager.get(Settings.selectedProfile)
                    val content = selected?.let { runCatching { File(it.typed.path).readText() }.getOrNull() }
                    if (Settings.selectedProfile == loadedFingerprints.first && content == loadedContent) return@launch
                    stopProbe()
                }
                loadGroupsFromSelectedProfile(force)
            }
        }
    }

    private fun saveSelection(profileId: Long, group: String, tag: String) {
        synchronized(Settings) {
            val all = JSONObject(Settings.outboundSelections)
            val profile = all.optJSONObject(profileId.toString()) ?: JSONObject()
            profile.put(group, tag)
            all.put(profileId.toString(), profile)
            Settings.outboundSelections = all.toString()
        }
    }

    init {
        Settings.dataStore.registerChangeListener(this)
        ProfileManager.registerCallback(profileCallback)
        refreshSelectedProfile(force = true)
        if (sharedCommandClient != null) {
            commandClient = sharedCommandClient
            isUsingSharedClient = true
            commandClient.addHandler(this)
        } else {
            commandClient = CommandClient(viewModelScope, CommandClient.ConnectionType.Groups, this)
            isUsingSharedClient = false
        }

        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isUiActive,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
                _serviceStatus,
            ) { foreground, remoteServer, remoteConnected, status ->
                SessionTarget(
                    connect =
                    foreground &&
                        if (remoteServer != null) {
                            remoteConnected
                        } else {
                            status == Status.Started
                        },
                    remoteServerId = remoteServer?.id,
                )
            }
                .distinctUntilChanged()
                .collect { target ->
                    if (target.connect) {
                        if (isUsingSharedClient) {
                            commandClient.addHandler(this@GroupsViewModel)
                        } else {
                            updateState { copy(isLoading = true) }
                            commandClient.connect()
                        }
                    } else {
                        if (isUsingSharedClient) {
                            commandClient.removeHandler(this@GroupsViewModel)
                        } else {
                            commandClient.disconnect()
                        }
                    }
                }
        }
    }

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

    override fun createInitialState() = GroupsUiState(sortByPing = Settings.outboundSortByPing)

    override fun onCleared() {
        stopProbe()
        Settings.dataStore.unregisterChangeListener(this)
        ProfileManager.unregisterCallback(profileCallback)
        super.onCleared()
        if (isUsingSharedClient) {
            commandClient.removeHandler(this)
        } else {
            commandClient.disconnect()
        }
    }

    private fun handleServiceStatusChange(status: Status) {
        if (RemoteControlManager.remoteServer.value != null) {
            return
        }
        if (status != Status.Started) refreshSelectedProfile()
    }

    fun updateServiceStatus(status: Status) {
        val statusChanged = status != lastServiceStatus
        lastServiceStatus = status
        if (statusChanged && status != Status.Stopped) stopProbe()
        viewModelScope.launch {
            if (statusChanged) {
                _serviceStatus.emit(status)
            }
            handleServiceStatusChange(status)
        }
    }

    fun toggleGroupExpand(groupTag: String) {
        val newExpanded = !uiState.value.expandedGroups.contains(groupTag)
        updateState {
            val newExpandedGroups =
                if (newExpanded) {
                    expandedGroups + groupTag
                } else {
                    expandedGroups - groupTag
                }
            copy(expandedGroups = newExpandedGroups)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (canUseCommandServer()) {
                    CommandTarget.standaloneClient().setGroupExpand(groupTag, newExpanded)
                }
            }
        }
    }

    fun toggleAllGroups() {
        val groups = uiState.value.groups
        val allCollapsed = uiState.value.expandedGroups.isEmpty()
        val newExpanded = allCollapsed

        updateState {
            if (allCollapsed) {
                copy(expandedGroups = groups.map { it.tag }.toSet())
            } else {
                copy(expandedGroups = emptySet())
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            groups.forEach { group ->
                runCatching {
                    if (canUseCommandServer()) {
                        CommandTarget.standaloneClient().setGroupExpand(group.tag, newExpanded)
                    }
                }
            }
        }
    }

    fun toggleSortByPing() {
        val enabled = !uiState.value.sortByPing
        Settings.outboundSortByPing = enabled
        updateState { copy(sortByPing = enabled) }
    }

    fun selectGroupItem(groupTag: String, itemTag: String) {
        // Check if this is actually a different selection
        val currentGroup = uiState.value.groups.find { it.tag == groupTag }
        if (!canUseCommandServer() && _serviceStatus.value != Status.Stopped) return
        if (
            currentGroup == null ||
            !currentGroup.selectable ||
            currentGroup.items.none { it.tag == itemTag } ||
            currentGroup.selected == itemTag
        ) {
            // Same item selected, no need to do anything
            return
        }

        val profileId = uiState.value.profileId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (uiState.value.profileId != profileId) return@launch
                if (canUseCommandServer()) {
                    CommandTarget.standaloneClient().selectOutbound(groupTag, itemTag)
                }
                if (RemoteControlManager.remoteServer.value == null) {
                    saveSelection(profileId, groupTag, itemTag)
                }
                if (uiState.value.profileId != profileId) return@launch

                // Update local state and show snackbar
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            groups =
                            groups.map { group ->
                                if (group.tag == groupTag) {
                                    group.copy(selected = itemTag)
                                } else {
                                    group
                                }
                            },
                            showCloseConnectionsSnackbar = canUseCommandServer(),
                        )
                    }
                    sendEvent(GroupsEvent.GroupSelected(groupTag, itemTag))
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun closeConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().closeConnections()
                withContext(Dispatchers.Main) { dismissCloseConnectionsSnackbar() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { dismissCloseConnectionsSnackbar() }
                sendError(e)
            }
        }
    }

    fun dismissCloseConnectionsSnackbar() {
        updateState { copy(showCloseConnectionsSnackbar = false) }
    }

    fun urlTest(outboundTag: String) = startUrlTest(outboundTag, listOf(outboundTag))

    fun urlTestGroup(groupTag: String) {
        val tags = uiState.value.groups.find { it.tag == groupTag }?.items?.map { it.tag }.orEmpty()
        startUrlTest(groupTag, tags)
    }

    private fun startUrlTest(testingTag: String, tags: List<String>) {
        val online = canUseCommandServer()
        val local = RemoteControlManager.remoteServer.value == null
        val sourceProfileId = uiState.value.profileId
        val sourceContent = loadedContent
        val sourceFingerprints = loadedFingerprints.takeIf { it.first == sourceProfileId }?.second.orEmpty()
        if (
            (!online && (_serviceStatus.value != Status.Stopped || tags.isEmpty())) ||
            !testInFlight.compareAndSet(false, true)
        ) {
            return
        }
        // Disable every test entry point, including individual node menus.
        updateState { copy(testingGroups = groups.map { it.tag }.toSet() + testingTag) }
        if (!online) {
            testOffline(tags)
            return
        }
        viewModelScope
            .launch(Dispatchers.IO) {
                try {
                    val corr = "online-${testingTag.hashCode()}-${System.currentTimeMillis()}"
                    logProbe("probe_started", testingTag, "online", correlation = corr)
                    val started = System.currentTimeMillis()
                    // Patched core RPC returns after the entire test finishes; per-tag
                    // errors land in history without structured codes to Kotlin.
                    CommandTarget.standaloneClient()
                        .urlTestWithOptions(testingTag, warmPing(), pingTimeoutMs())
                    logProbe(
                        "probe_finished",
                        testingTag,
                        "online",
                        ProbeOutcome(ProbeStatus.SUCCESS, 0, "batch completed"),
                        System.currentTimeMillis() - started,
                        corr,
                    )
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    val outcome = ProbeOutcome.fromThrowable(e)
                    if (local && tags.size == 1 && outcome.status != ProbeStatus.CANCELLED &&
                        sourceProfileId == uiState.value.profileId && sourceProfileId == Settings.selectedProfile &&
                        sourceContent == loadedContent
                    ) {
                        val measuredAt = System.currentTimeMillis()
                        val tag = tags.single()
                        rememberLatency(sourceProfileId, tag, sourceFingerprints[tag], outcome, measuredAt)
                        updateState {
                            if (profileId != sourceProfileId) this else copy(
                                groups = groups.map { group ->
                                    group.copy(
                                        items = group.items.map { item ->
                                            if (item.tag == tag) item.copy(
                                                urlTestDelay = 0,
                                                urlTestTime = measuredAt / 1000,
                                                probeStatus = outcome.status.name,
                                                probeDetail = outcome.detail,
                                            ) else item
                                        },
                                    )
                                },
                            )
                        }
                    }
                    logProbe(
                        "probe_finished",
                        testingTag,
                        "online",
                        outcome,
                        correlation = "online-err",
                    )
                    if (local && outcome.status == ProbeStatus.CANCELLED) {
                        val stopped = withTimeoutOrNull(15_000L) {
                            while (_serviceStatus.value != Status.Stopped) delay(50L)
                            true
                        } == true
                        if (!stopped) {
                            if (sourceProfileId == uiState.value.profileId &&
                                sourceProfileId == Settings.selectedProfile
                            ) {
                                sendError(e)
                            }
                        } else if (sourceContent != null && tags.isNotEmpty() &&
                            sourceProfileId == uiState.value.profileId &&
                            sourceProfileId == Settings.selectedProfile &&
                            RemoteControlManager.remoteServer.value == null
                        ) {
                            logProbe("probe_resume_offline", testingTag, "offline", correlation = "resume-$sourceProfileId")
                            // The online job retains the single-flight slot until this retry ends.
                            // The offline worker rereads the file and rejects changed content.
                            testOffline(tags, sourceProfileId, sourceContent, finishOnCompletion = false).join()
                        }
                    } else {
                        sendError(e)
                    }
                }
            }
            .invokeOnCompletion { finishUrlTest() }
    }

    private fun pingTimeoutMs(): Long = runCatching { JSONObject(Settings.coreOptionsJson).optInt("pingTimeoutSeconds", 12).coerceIn(1, 60).toLong() * 1000 }.getOrDefault(12000L)

    private fun warmPing() = runCatching { org.json.JSONObject(Settings.coreOptionsJson).optBoolean("pingWarm", false) }
        .getOrDefault(false)

    private fun finishUrlTest() {
        updateState { copy(testingGroups = emptySet()) }
        testInFlight.set(false)
    }

    private fun testOffline(
        tags: List<String>,
        profileId: Long = uiState.value.profileId,
        expectedContent: String? = null,
        finishOnCompletion: Boolean = true,
    ): Job {
        val fingerprints =
            loadedFingerprints.let { if (it.first == profileId) it.second else emptyMap() }
        return viewModelScope
            .launch(Dispatchers.IO) {
                probeMutex.withLock {
                    try {
                        val profile = ProfileManager.get(profileId) ?: return@withLock
                        val content = File(profile.typed.path).readText()
                        if (expectedContent != null &&
                            (
                                content != expectedContent || profileId != Settings.selectedProfile ||
                                    _serviceStatus.value != Status.Stopped
                                )
                        ) {
                            return@withLock
                        }
                        val config =
                            OutboundProfileState.probeConfig(
                                content,
                                tags,
                            )
                        coroutineContext.ensureActive()
                        val session = Libbox.newOutboundProbe(config, OfflineProbePlatform())
                        probe = session
                        try {
                            coroutineContext.ensureActive()
                            val warm = warmPing()
                            val limit = Semaphore(4)
                            coroutineScope {
                                tags
                                    .distinct()
                                    .map { tag ->
                                        async {
                                            limit.withPermit {
                                                ensureActive()
                                                val startedAt = System.currentTimeMillis()
                                                val correlation =
                                                    "p$profileId-${tag.hashCode()}-$startedAt"
                                                logProbe(
                                                    "probe_started",
                                                    tag,
                                                    "offline",
                                                    correlation = correlation,
                                                )
                                                val outcome =
                                                    try {
                                                        val delay =
                                                            session.urlTestWithOptions(
                                                                tag,
                                                                warm,
                                                                pingTimeoutMs(),
                                                            )
                                                        if (delay > 0) {
                                                            ProbeOutcome.success(delay)
                                                        } else {
                                                            ProbeOutcome(
                                                                ProbeStatus.PROBE_ERROR,
                                                                0,
                                                                "urltest returned 0",
                                                            )
                                                        }
                                                    } catch (e: Exception) {
                                                        if (e is kotlin.coroutines.cancellation.CancellationException) {
                                                            throw e
                                                        }
                                                        ProbeOutcome.fromThrowable(e)
                                                    }
                                                ensureActive()
                                                val measuredAt = System.currentTimeMillis()
                                                logProbe(
                                                    "probe_finished",
                                                    tag,
                                                    "offline",
                                                    outcome,
                                                    measuredAt - startedAt,
                                                    correlation,
                                                )
                                                rememberLatency(
                                                    profileId,
                                                    tag,
                                                    fingerprints[tag],
                                                    outcome,
                                                    measuredAt,
                                                )
                                                if (
                                                    uiState.value.profileId == profileId &&
                                                    _serviceStatus.value == Status.Stopped
                                                ) {
                                                    updateState {
                                                        copy(
                                                            groups =
                                                            groups.map { group ->
                                                                group.copy(
                                                                    items =
                                                                    group.items.map { item ->
                                                                        if (item.tag == tag) {
                                                                            item.copy(
                                                                                urlTestDelay =
                                                                                outcome.delayMs,
                                                                                urlTestTime =
                                                                                measuredAt,
                                                                                probeStatus =
                                                                                outcome.status.name,
                                                                                probeDetail =
                                                                                outcome.detail,
                                                                            )
                                                                        } else {
                                                                            item
                                                                        }
                                                                    },
                                                                )
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .awaitAll()
                            }
                        } finally {
                            session.close()
                            if (probe === session) probe = null
                        }
                    } catch (e: Exception) {
                        coroutineContext.ensureActive()
                        sendError(e)
                    }
                }
            }
            .also {
                probeJob = it
                if (finishOnCompletion) it.invokeOnCompletion { finishUrlTest() }
            }
    }

    // CommandClient.Handler implementation
    override fun onConnected() {
        viewModelScope.launch(Dispatchers.Main) {
            // Connection established, waiting for groups
        }
    }

    override fun onDisconnected() {
        // Do not clear groups — keep last known list and ping results offline.
        viewModelScope.launch(Dispatchers.Main) {
            updateState { copy(isLoading = false) }
            if (uiState.value.groups.isEmpty()) {
                launch(Dispatchers.IO) { loadGroupsFromSelectedProfile() }
            }
        }
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        viewModelScope.launch(Dispatchers.Default) {
            val profileId = uiState.value.profileId
            val pingContext = loadedFingerprints
            val fingerprints =
                if (pingContext.first == profileId) pingContext.second else emptyMap()
            val local = RemoteControlManager.remoteServer.value == null
            val currentGroups = uiState.value.groups
            val previousPing = currentGroups.flatMap { g -> g.items.map { it.tag to it } }.toMap()
            val currentByTag = currentGroups.associateBy { it.tag }
            val mergedGroups =
                newGroups.map { goGroup ->
                    val incoming = Group(goGroup)
                    val converted =
                        if (!local) {
                            incoming
                        } else {
                            incoming.copy(
                                items =
                                incoming.items.map { item ->
                                    // The command stream can briefly replay the previous
                                    // subscription after reload.
                                    if (item.urlTestTime < pingContext.third) {
                                        item.copy(urlTestTime = 0L, urlTestDelay = 0)
                                    } else {
                                        item
                                    }
                                },
                            )
                        }
                    val existing = currentByTag[converted.tag]
                    val items =
                        converted.items.map { item ->
                            if (item.urlTestDelay > 0 || item.urlTestTime > 0L) {
                                val cached = if (local) fingerprints[item.tag]?.let {
                                    latencyCache.get(profileId, item.tag, it)
                                } else null
                                val outcome = ProbeOutcome.fromOnlineHistory(item.urlTestDelay, item.urlTestTime, cached)
                                if (local) {
                                    rememberLatency(
                                        profileId,
                                        item.tag,
                                        fingerprints[item.tag],
                                        outcome,
                                        item.urlTestTime.takeIf { it > 0 }
                                            ?: System.currentTimeMillis(),
                                    )
                                }
                                item.copy(probeStatus = outcome.status.name, probeDetail = outcome.detail)
                            } else {
                                val cached =
                                    if (local) {
                                        fingerprints[item.tag]?.let {
                                            latencyCache.get(profileId, item.tag, it)
                                        }
                                    } else {
                                        null
                                    }
                                val prev = previousPing[item.tag]
                                if (cached != null) {
                                    item.copy(
                                        urlTestDelay = cached.delay,
                                        urlTestTime = cached.time,
                                        probeStatus = cached.status.name,
                                        probeDetail = cached.detail,
                                    )
                                } else if (
                                    prev != null && (prev.urlTestDelay > 0 || prev.urlTestTime > 0L)
                                ) {
                                    item.copy(
                                        urlTestDelay = prev.urlTestDelay,
                                        urlTestTime = prev.urlTestTime,
                                        probeStatus = prev.probeStatus,
                                        probeDetail = prev.probeDetail,
                                    )
                                } else {
                                    item
                                }
                            }
                        }
                    val withPing = converted.copy(items = items)
                    if (existing == withPing) existing else withPing
                }

            withContext(Dispatchers.Main) {
                if (local && uiState.value.profileId != profileId) return@withContext
                updateState {
                    val initialExpandedGroups =
                        if (expandedGroups.isEmpty() && currentGroups.isEmpty()) {
                            mergedGroups.filter { it.isExpand }.map { it.tag }.toSet()
                        } else {
                            expandedGroups
                        }
                    copy(
                        groups = if (mergedGroups == groups) groups else mergedGroups,
                        expandedGroups = initialExpandedGroups,
                        isLoading = false,
                        subscriptionName =
                        if (RemoteControlManager.remoteServer.value == null) {
                            subscriptionName
                        } else {
                            ""
                        },
                    )
                }
            }
        }
    }

    private suspend fun loadGroupsFromSelectedProfile(force: Boolean = false) = loadMutex.withLock {
        val profileId = Settings.selectedProfile
        val profile = ProfileManager.get(profileId)
        val selections =
            JSONObject(Settings.outboundSelections)
                .optJSONObject(profileId.toString())
                ?.toString()
                .orEmpty()
        val content =
            profile
                ?.typed
                ?.path
                ?.takeIf { it.isNotBlank() }
                ?.let { path -> runCatching { File(path).readText() }.getOrNull() }
        val groups =
            content
                ?.let {
                    runCatching {
                        parseGroupsFromConfig(
                            OutboundProfileState.withSelections(it, selections),
                        )
                    }
                        .getOrDefault(emptyList())
                }
                .orEmpty()
        if (Settings.selectedProfile != profileId) return@withLock
        val sameProfile = uiState.value.profileId == profileId && loadedContent == content
        loadedContent = content
        val fingerprints = if (sameProfile && loadedFingerprints.first == profileId) {
            loadedFingerprints.second
        } else {
            content?.let { runCatching { ProfileLatencyCache.fingerprints(it) }.getOrDefault(emptyMap()) }.orEmpty()
        }
        val sinceSeconds =
            if (sameProfile) loadedFingerprints.third else System.currentTimeMillis() / 1000
        loadedFingerprints = Triple(profileId, fingerprints, sinceSeconds)
        synchronized(latencyCache) {
            if (latencyCache.retain(profileId, fingerprints)) {
                Settings.outboundLatencyCache = latencyCache.encode()
            }
        }
        updateState {
            val previous =
                if (sameProfile) {
                    this.groups.flatMap { it.items }.associateBy { it.tag }
                } else {
                    emptyMap()
                }
            val refreshed =
                groups.map { group ->
                    group.copy(
                        items =
                        group.items.map { item ->
                            val cached =
                                fingerprints[item.tag]?.let {
                                    latencyCache.get(profileId, item.tag, it)
                                }
                            if (cached != null) {
                                item.copy(
                                    urlTestDelay = cached.delay,
                                    urlTestTime = cached.time,
                                    probeStatus = cached.status.name,
                                    probeDetail = cached.detail,
                                )
                            } else {
                                previous[item.tag]?.let {
                                    item.copy(
                                        urlTestDelay = it.urlTestDelay,
                                        urlTestTime = it.urlTestTime,
                                        probeStatus = it.probeStatus,
                                        probeDetail = it.probeDetail,
                                    )
                                } ?: item
                            }
                        },
                    )
                }
            copy(
                profileId = profileId,
                subscriptionName = profile?.name.orEmpty(),
                groups =
                if (sameProfile && !force && this.groups.isNotEmpty()) {
                    this.groups
                } else {
                    refreshed
                },
                expandedGroups =
                if (sameProfile && this.groups.isNotEmpty()) {
                    expandedGroups
                } else {
                    groups.map { it.tag }.toSet()
                },
                isLoading = false,
            )
        }
    }

    companion object {
        private const val TAG_PROBE = "SFAxtndProbe"

        private val latencyCache by lazy { ProfileLatencyCache(Settings.outboundLatencyCache) }

        fun parseGroupsFromConfig(jsonStr: String): List<Group> {
            return try {
                val root = JSONObject(jsonStr.trim())
                val outbounds = root.optJSONArray("outbounds") ?: return emptyList()
                val byTag = mutableMapOf<String, JSONObject>()
                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i) ?: continue
                    val tag = ob.optString("tag")
                    if (tag.isNotBlank()) byTag[tag] = ob
                }
                val groups = mutableListOf<Group>()
                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i) ?: continue
                    val type = ob.optString("type")
                    if (type != "selector" && type != "urltest") continue
                    val tag = ob.optString("tag").ifBlank { type }
                    val members = ob.optJSONArray("outbounds") ?: JSONArray()
                    val selected =
                        ob.optString("default").ifBlank {
                            if (members.length() > 0) members.optString(0) else ""
                        }
                    val items = mutableListOf<GroupItem>()
                    for (j in 0 until members.length()) {
                        val memberTag = members.optString(j)
                        if (memberTag.isBlank()) continue
                        val member = byTag[memberTag]
                        val memberType = member?.optString("type") ?: "unknown"
                        items.add(
                            GroupItem(
                                tag = memberTag,
                                type = memberType,
                                displayType = memberType.uppercase(),
                                urlTestTime = 0L,
                                urlTestDelay = 0,
                            ),
                        )
                    }
                    if (items.isEmpty()) continue
                    groups.add(
                        Group(
                            tag = tag,
                            type = type,
                            displayType = type.replaceFirstChar { it.uppercase() },
                            selectable = type == "selector",
                            selected = selected,
                            isExpand = true,
                            items = items,
                        ),
                    )
                }
                groups
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
