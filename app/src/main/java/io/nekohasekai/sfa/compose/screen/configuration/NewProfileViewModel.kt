package io.nekohasekai.sfa.compose.screen.configuration

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.ProfileContentDecoder
import io.nekohasekai.sfa.utils.ProxyLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Date

data class NewProfileUiState(
    val name: String = "",
    val profileType: ProfileType = ProfileType.Local,
    val profileSource: ProfileSource = ProfileSource.CreateNew,
    // Remote profile fields
    val remoteUrl: String = "",
    val autoUpdate: Boolean = true,
    val autoUpdateInterval: Int = 60,
    // File import
    val importUri: Uri? = null,
    val importFileName: String? = null,
    // QRS import
    val qrsData: ByteArray? = null,
    // State
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false,
    val createdProfile: Profile? = null,
    // Field errors
    val nameError: String? = null,
    val remoteUrlError: String? = null,
    val importError: String? = null,
)

enum class ProfileType {
    Local,
    Remote,
}

enum class ProfileSource {
    CreateNew,
    Import,
}

class NewProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(NewProfileUiState())
    val uiState: StateFlow<NewProfileUiState> = _uiState.asStateFlow()

    fun initializeFromQRImport(name: String?, url: String?) {
        if (name != null && url != null) {
            _uiState.update {
                it.copy(
                    name = name,
                    profileType = ProfileType.Remote,
                    remoteUrl = url,
                )
            }
        }
    }

    fun initializeFromQRSImport(name: String?, qrsData: ByteArray) {
        _uiState.update {
            it.copy(
                name = name ?: "",
                profileType = ProfileType.Local,
                profileSource = ProfileSource.Import,
                qrsData = qrsData,
            )
        }
    }

    fun updateName(name: String) {
        _uiState.update {
            it.copy(
                name = name,
                nameError = if (name.isNotBlank()) null else it.nameError,
            )
        }
    }

    fun updateProfileType(type: ProfileType) {
        _uiState.update { it.copy(profileType = type) }
    }

    fun updateProfileSource(source: ProfileSource) {
        _uiState.update {
            it.copy(
                profileSource = source,
                importError = null, // Clear import error when changing source
            )
        }
    }

    fun updateRemoteUrl(url: String) {
        _uiState.update {
            it.copy(
                remoteUrl = url,
                remoteUrlError = if (url.isNotBlank()) null else it.remoteUrlError,
            )
        }
    }

    fun updateAutoUpdate(enabled: Boolean) {
        _uiState.update { it.copy(autoUpdate = enabled) }
    }

    fun updateAutoUpdateInterval(interval: String) {
        val intValue = interval.toIntOrNull() ?: 60
        _uiState.update { it.copy(autoUpdateInterval = intValue.coerceAtLeast(15)) }
    }

    fun setImportUri(uri: Uri, fileName: String?) {
        _uiState.update {
            it.copy(
                importUri = uri,
                importFileName = fileName,
                importError = null, // Clear error when file is selected
                name =
                if (it.name.isEmpty()) {
                    fileName?.substringBeforeLast(".") ?: "Imported Profile"
                } else {
                    it.name
                },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun validateAndCreateProfile(): Boolean {
        val state = _uiState.value
        val context = getApplication<Application>()

        // Clear previous errors
        _uiState.update {
            it.copy(
                nameError = null,
                remoteUrlError = null,
                importError = null,
            )
        }

        var hasError = false

        // Validate name
        if (state.name.isBlank()) {
            _uiState.update { it.copy(nameError = context.getString(R.string.profile_input_required)) }
            hasError = true
        }

        // Validate based on profile type
        when (state.profileType) {
            ProfileType.Local -> {
                if (state.profileSource == ProfileSource.Import && state.importUri == null && state.qrsData == null) {
                    _uiState.update { it.copy(importError = context.getString(R.string.profile_input_required)) }
                    hasError = true
                }
            }
            ProfileType.Remote -> {
                if (state.remoteUrl.isBlank()) {
                    _uiState.update { it.copy(remoteUrlError = context.getString(R.string.profile_input_required)) }
                    hasError = true
                }
            }
        }

        if (hasError) {
            return false
        }

        if (state.isSaving || state.isSuccess) return false
        // Reserve synchronously; a second click cannot enqueue another import.
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        createProfile()
        return true
    }

    private fun createProfile() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            try {
                val profile =
                    withContext(Dispatchers.IO) {
                        when (state.profileType) {
                            ProfileType.Local -> createLocalProfile(state)
                            ProfileType.Remote -> createRemoteProfile(state)
                        }
                    }

                if (state.profileType == ProfileType.Remote || state.profileSource == ProfileSource.Import) {
                    try {
                        val report = withContext(Dispatchers.IO) {
                            io.nekohasekai.sfa.utils.SubscriptionImportReport.read(profile.typed.path)
                        }
                        val op =
                            when {
                                report == null ->
                                    io.nekohasekai.sfa.utils.ImportOperationOutcome.APPLIED
                                report.received > 0 && report.imported < report.received ->
                                    io.nekohasekai.sfa.utils.ImportOperationOutcome.PARTIAL_APPLIED
                                report.imported == 0 && report.received > 0 ->
                                    io.nekohasekai.sfa.utils.ImportOperationOutcome.REJECTED
                                else ->
                                    io.nekohasekai.sfa.utils.ImportOperationOutcome.APPLIED
                            }
                        io.nekohasekai.sfa.compose.base.ImportReportNotifier.show(profile, op)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        android.util.Log.w("ProfileImport", "IMPORT_REPORT_PRESENTATION_FAILED")
                    }
                }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        isSuccess = true,
                        createdProfile = profile,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isSaving = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e),
                    )
                }
            }
        }
    }

    private suspend fun createLocalProfile(state: NewProfileUiState): Profile {
        val context = getApplication<Application>()
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Local
            }

        val profile =
            Profile(name = state.name, typed = typedProfile)

        var importReport: io.nekohasekai.sfa.utils.SubscriptionImportReport? = null
        // Get config content
        val configContent =
            when (state.profileSource) {
                ProfileSource.CreateNew -> "{}"
                ProfileSource.Import -> {
                    if (state.qrsData != null) {
                        val decoded = ProfileContentDecoder.decodeWithReport(state.qrsData)
                        importReport = decoded.report
                        decoded.content.config
                    } else {
                        state.importUri?.let { uri ->
                            val sourceURL = uri.toString()
                            when {
                                sourceURL.startsWith("content://") -> {
                                    val inputStream = context.contentResolver.openInputStream(uri) as InputStream
                                    inputStream.use { io.nekohasekai.sfa.utils.ImportInput.read(it).toString(Charsets.UTF_8) }
                                }
                                sourceURL.startsWith("file://") -> {
                                    File(Uri.parse(sourceURL).path!!).inputStream().use { io.nekohasekai.sfa.utils.ImportInput.read(it).toString(Charsets.UTF_8) }
                                }
                                sourceURL.startsWith("http://") || sourceURL.startsWith("https://") -> {
                                    HTTPClient().use { it.fetchSubscription(sourceURL) }.also { importReport = it.report }.config
                                }
                                ProxyLinkParser.isShareLink(sourceURL) -> {
                                    ProfileContentDecoder.decodeWithReport(sourceURL.toByteArray(Charsets.UTF_8)).also { importReport = it.report }.content.config
                                }
                                else -> throw Exception("Unsupported source: $sourceURL")
                            }
                        } ?: "{}"
                    }
                }
            }

        // Files may contain share links just like QR/clipboard imports.
        val resolvedConfig = if (importReport == null && io.nekohasekai.sfa.utils.SubscriptionContentParser.supports(configContent)) {
            ProfileContentDecoder.decodeWithReport(configContent.toByteArray(Charsets.UTF_8)).also { importReport = it.report }.content.config
        } else {
            configContent
        }
        ProfileManager.createImported(profile, resolvedConfig, importReport)

        return profile
    }

    private suspend fun createRemoteProfile(state: NewProfileUiState): Profile {
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Remote
                remoteURL = state.remoteUrl
                autoUpdate = state.autoUpdate
                autoUpdateInterval = state.autoUpdateInterval
                lastUpdated = Date()
            }

        val profile =
            Profile(name = state.name, typed = typedProfile)

        // Fetch initial config - this MUST succeed for remote profiles
        val result = HTTPClient().use { it.fetchSubscription(state.remoteUrl) }
        ProfileManager.createImported(profile, result.config, result.report)
        if (state.autoUpdate) {
            runCatching { UpdateProfileWork.reconfigureUpdater() }.onFailure {
                android.util.Log.w("ProfileImport", "IMPORT_UPDATER_SCHEDULING_FAILED")
            }
        }

        return profile
    }
}
