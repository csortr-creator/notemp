package io.nekohasekai.sfa.compose.screen.profile

import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.CommitOutcome
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.ImportOperationOutcome
import io.nekohasekai.sfa.utils.ImportResultFormatter
import io.nekohasekai.sfa.utils.ManualPrepareResult
import io.nekohasekai.sfa.utils.ManualSubscriptionUpdate
import io.nekohasekai.sfa.utils.NetworkErrorKind
import io.nekohasekai.sfa.utils.NetworkErrorPresentation
import io.nekohasekai.sfa.utils.PendingImportHolder
import io.nekohasekai.sfa.utils.ProfileConfigCommit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EditProfileUiState(
    val profile: Profile? = null,
    val name: String = "",
    val icon: String? = null,
    val profileType: TypedProfile.Type? = null,
    val remoteUrl: String = "",
    val autoUpdate: Boolean = false,
    val autoUpdateInterval: Int = 60,
    val lastUpdated: Date? = null,
    // Original values for change detection
    val originalName: String = "",
    val originalIcon: String? = null,
    val originalRemoteUrl: String = "",
    val originalAutoUpdate: Boolean = false,
    val originalAutoUpdateInterval: Int = 60,
    // State flags
    val hasChanges: Boolean = false,
    val isLoading: Boolean = true,
    val isUpdating: Boolean = false,
    val showUpdateSuccess: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val autoUpdateIntervalError: String? = null,
    val showIconDialog: Boolean = false,
    /** In-memory partial-import candidate (B3-3c); never persisted. */
    val pendingImport: PendingImportHolder? = null,
    val showPartialImportDialog: Boolean = false,
)

class EditProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(EditProfileUiState())
    val uiState: StateFlow<EditProfileUiState> = _uiState.asStateFlow()

    // Store the content to export when user selects a file location
    var pendingExportContent: String? = null
    var pendingExportFileName: String? = null

    fun loadProfile(profileId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = ProfileManager.get(profileId)
                if (profile == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Profile not found",
                        )
                    }
                    return@launch
                }

                val typedProfile = profile.typed
                _uiState.update {
                    it.copy(
                        profile = profile,
                        name = profile.name,
                        originalName = profile.name,
                        icon = profile.icon,
                        originalIcon = profile.icon,
                        profileType = typedProfile.type,
                        remoteUrl = typedProfile.remoteURL,
                        originalRemoteUrl = typedProfile.remoteURL,
                        autoUpdate = typedProfile.autoUpdate,
                        originalAutoUpdate = typedProfile.autoUpdate,
                        autoUpdateInterval = typedProfile.autoUpdateInterval,
                        originalAutoUpdateInterval = typedProfile.autoUpdateInterval,
                        lastUpdated = typedProfile.lastUpdated,
                        isLoading = false,
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load profile",
                    )
                }
            }
        }
    }

    fun updateName(name: String) {
        _uiState.update { state ->
            state.copy(
                name = name,
                hasChanges =
                checkHasChanges(
                    state.copy(name = name),
                ),
            )
        }
    }

    fun updateIcon(icon: String?) {
        _uiState.update { state ->
            state.copy(
                icon = icon,
                hasChanges =
                checkHasChanges(
                    state.copy(icon = icon),
                ),
            )
        }
    }

    fun showIconDialog() {
        _uiState.update { it.copy(showIconDialog = true) }
    }

    fun hideIconDialog() {
        _uiState.update { it.copy(showIconDialog = false) }
    }

    fun updateRemoteUrl(url: String) {
        _uiState.update { state ->
            state.copy(
                remoteUrl = url,
                hasChanges =
                checkHasChanges(
                    state.copy(remoteUrl = url),
                ),
            )
        }
    }

    fun updateAutoUpdate(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(
                autoUpdate = enabled,
                hasChanges =
                checkHasChanges(
                    state.copy(autoUpdate = enabled),
                ),
            )
        }
    }

    fun updateAutoUpdateInterval(interval: String) {
        val intValue = interval.toIntOrNull() ?: 60
        val error =
            when {
                interval.isBlank() -> getApplication<Application>().getString(R.string.profile_input_required)
                intValue < 15 -> getApplication<Application>().getString(R.string.profile_auto_update_interval_minimum_hint)
                else -> null
            }

        _uiState.update { state ->
            state.copy(
                autoUpdateInterval = intValue,
                autoUpdateIntervalError = error,
                hasChanges =
                if (error == null) {
                    checkHasChanges(state.copy(autoUpdateInterval = intValue))
                } else {
                    state.hasChanges
                },
            )
        }
    }

    private fun checkHasChanges(state: EditProfileUiState): Boolean = state.name != state.originalName ||
        state.icon != state.originalIcon ||
        state.remoteUrl != state.originalRemoteUrl ||
        state.autoUpdate != state.originalAutoUpdate ||
        state.autoUpdateInterval != state.originalAutoUpdateInterval

    fun saveChanges() {
        val state = _uiState.value
        val profile = state.profile ?: return

        if (state.autoUpdateIntervalError != null) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isSaving = true) }

            try {
                val savedProfile = ProfileConfigCommit.withProfileLock(profile.id) {
                    val current = ProfileManager.get(profile.id) ?: error("PROFILE_UNAVAILABLE")
                    if (current.typed.remoteURL != state.remoteUrl) {
                        ProfileConfigCommit.invalidate(profile.id)
                        current.typed.lastUpdated = Date(0)
                    }
                    current.name = state.name
                    current.icon = state.icon
                    current.typed.remoteURL = state.remoteUrl
                    current.typed.autoUpdate = state.autoUpdate
                    current.typed.autoUpdateInterval = state.autoUpdateInterval
                    check(ProfileManager.update(current) == 1) { "PROFILE_UNAVAILABLE" }
                    current
                }
                // Disabling updates or changing their interval also changes the worker schedule.
                UpdateProfileWork.reconfigureUpdater()

                // Update UI state with new original values
                _uiState.update {
                    it.copy(
                        profile = savedProfile,
                        lastUpdated = savedProfile.typed.lastUpdated,
                        originalName = state.name,
                        originalIcon = state.icon,
                        originalRemoteUrl = state.remoteUrl,
                        originalAutoUpdate = state.autoUpdate,
                        originalAutoUpdateInterval = state.autoUpdateInterval,
                        hasChanges = false,
                        isSaving = false,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "Failed to save profile",
                    )
                }
            }
        }
    }

    fun updateRemoteProfile() {
        val state = _uiState.value
        val profile = state.profile ?: return
        if (profile.typed.type != TypedProfile.Type.Remote) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isUpdating = true, errorMessage = null) }
            when (val result = ManualSubscriptionUpdate.prepare(profile)) {
                is ManualPrepareResult.Applied -> {
                    io.nekohasekai.sfa.compose.base.ImportReportNotifier.show(profile, result)
                    _uiState.update {
                        it.copy(
                            lastUpdated = profile.typed.lastUpdated,
                            isUpdating = false,
                            showUpdateSuccess =
                            result.outcome == ImportOperationOutcome.APPLIED ||
                                result.outcome == ImportOperationOutcome.PARTIAL_APPLIED ||
                                result.outcome == ImportOperationOutcome.APPLIED_UNCHANGED,
                            pendingImport = null,
                            showPartialImportDialog = false,
                        )
                    }
                    if (result.replaced && profile.id == Settings.selectedProfile) {
                        runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
                    }
                }
                is ManualPrepareResult.AwaitConfirmation -> {
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            pendingImport = result.pending,
                            showPartialImportDialog = true,
                        )
                    }
                }
                is ManualPrepareResult.Rejected -> {
                    io.nekohasekai.sfa.compose.base.ImportReportNotifier.show(profile, result)
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            showUpdateSuccess = false,
                            pendingImport = null,
                            showPartialImportDialog = false,
                        )
                    }
                }
                is ManualPrepareResult.Failed -> {
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            errorMessage = result.safeMessage,
                            pendingImport = null,
                            showPartialImportDialog = false,
                        )
                    }
                }
            }
        }
    }

    fun confirmPartialImport() {
        val pending = _uiState.value.pendingImport ?: return
        val profile = _uiState.value.profile
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isUpdating = true, showPartialImportDialog = false) }
            when (val result = ManualSubscriptionUpdate.confirm(pending)) {
                is ManualPrepareResult.Applied -> {
                    if (profile != null) {
                        io.nekohasekai.sfa.compose.base.ImportReportNotifier.show(profile, result)
                    }
                    _uiState.update {
                        it.copy(
                            lastUpdated = profile?.typed?.lastUpdated ?: it.lastUpdated,
                            isUpdating = false,
                            showUpdateSuccess =
                            result.outcome == ImportOperationOutcome.APPLIED ||
                                result.outcome == ImportOperationOutcome.PARTIAL_APPLIED ||
                                result.outcome == ImportOperationOutcome.APPLIED_UNCHANGED,
                            pendingImport = null,
                        )
                    }
                    if (result.replaced && profile != null && profile.id == Settings.selectedProfile) {
                        runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
                    }
                }
                is ManualPrepareResult.Rejected,
                is ManualPrepareResult.Failed,
                is ManualPrepareResult.AwaitConfirmation,
                -> {
                    val message = if (profile != null) {
                        io.nekohasekai.sfa.compose.base.ImportReportNotifier.text(profile, result)
                    } else {
                        "PROFILE_UNAVAILABLE: профиль удалён или недоступен"
                    }
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            errorMessage =
                            message,
                            pendingImport = null,
                        )
                    }
                }
            }
        }
    }

    fun cancelPartialImport() {
        val pending = _uiState.value.pendingImport
        if (pending != null) ManualSubscriptionUpdate.cancel(pending)
        _uiState.update {
            it.copy(pendingImport = null, showPartialImportDialog = false)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearUpdateSuccess() {
        _uiState.update { it.copy(showUpdateSuccess = false) }
    }

    fun prepareExport(context: Context): String? {
        val state = _uiState.value
        val profile = state.profile ?: return null

        return try {
            // Read the configuration file
            val configFile = File(profile.typed.path)
            if (!configFile.exists()) {
                Toast.makeText(context, "Configuration file not found", Toast.LENGTH_SHORT).show()
                return null
            }

            val content = configFile.readText()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "${profile.name.replace(" ", "_")}_$timestamp.json"

            // Store content for later when user picks location
            pendingExportContent = content
            pendingExportFileName = fileName

            fileName
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(
                    io.nekohasekai.sfa.R.string.failed_read_configuration,
                    e.message,
                ),
                Toast.LENGTH_SHORT,
            ).show()
            null
        }
    }

    fun saveExportToUri(context: Context, uri: Uri) {
        val content = pendingExportContent ?: return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Configuration exported successfully",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                // Clear pending export data
                pendingExportContent = null
                pendingExportFileName = null
            }
        }
    }
}
