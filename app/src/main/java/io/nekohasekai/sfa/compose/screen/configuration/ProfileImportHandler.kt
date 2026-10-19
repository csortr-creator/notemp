package io.nekohasekai.sfa.compose.screen.configuration

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.ProfileContentDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Date

class ProfileImportHandler(private val context: Context) {
    sealed class ImportResult {
        data class Success(val profile: Profile) : ImportResult()

        data class Error(val message: String) : ImportResult()
    }

    sealed class QRCodeParseResult {
        data class RemoteProfile(val name: String, val host: String, val url: String) : QRCodeParseResult()

        data class LocalProfile(val name: String) : QRCodeParseResult()

        data class Error(val message: String) : QRCodeParseResult()
    }

    sealed class QRSParseResult {
        data class Success(val name: String) : QRSParseResult()

        data class Error(val message: String) : QRSParseResult()
    }

    sealed class UriParseResult {
        data class Success(val name: String) : UriParseResult()

        data class Error(val message: String) : UriParseResult()
    }

    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val data =
                context.contentResolver.openInputStream(uri)?.use { io.nekohasekai.sfa.utils.ImportInput.read(it) }
                    ?: return@withContext ImportResult.Error(context.getString(R.string.error_empty_file))

            // Get the filename from the URI
            val filename = getFileNameFromUri(uri)

            // Try to detect if it's a JSON configuration file
            val dataString = String(data)
            if (isJsonConfiguration(dataString)) {
                // It's a JSON configuration, import it directly as a local profile
                return@withContext importJsonConfiguration(dataString, filename)
            }

            // Try to decode as ProfileContent (the old way)
            val content =
                try {
                    ProfileContentDecoder.decodeWithReport(data)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // If it fails, try one more time as JSON
                    if (dataString.trimStart().startsWith("{") || dataString.trimStart().startsWith("[")) {
                        return@withContext importJsonConfiguration(dataString, filename)
                    }
                    return@withContext ImportResult.Error(
                        io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e),
                    )
                }

            importProfile(content)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Error(io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e))
        }
    }

    suspend fun parseUri(uri: Uri): UriParseResult = withContext(Dispatchers.IO) {
        try {
            val data =
                context.contentResolver.openInputStream(uri)?.use { io.nekohasekai.sfa.utils.ImportInput.read(it) }
                    ?: return@withContext UriParseResult.Error(context.getString(R.string.error_empty_file))

            val filename = getFileNameFromUri(uri)
            val dataString = String(data)

            if (isJsonConfiguration(dataString)) {
                return@withContext UriParseResult.Success(name = filename)
            }

            val content =
                try {
                    ProfileContentDecoder.decode(data)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (dataString.trimStart().startsWith("{") || dataString.trimStart().startsWith("[")) {
                        return@withContext UriParseResult.Success(name = filename)
                    }
                    return@withContext UriParseResult.Error(
                        io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e),
                    )
                }

            UriParseResult.Success(name = content.name)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            UriParseResult.Error(io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e))
        }
    }

    suspend fun parseQRCode(rawData: String): QRCodeParseResult = withContext(Dispatchers.IO) {
        val data = rawData.trim()
        try {
            // Check if it's a sing-box remote profile import link
            if (data.startsWith("sing-box://import-remote-profile")) {
                try {
                    val profileInfo = Libbox.parseRemoteProfileImportLink(data)
                    return@withContext QRCodeParseResult.RemoteProfile(
                        name = profileInfo.name,
                        host = profileInfo.host,
                        url = profileInfo.url,
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return@withContext QRCodeParseResult.Error(
                        io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e),
                    )
                }
            }

            // Check if it's a direct URL
            if (data.startsWith("http://") || data.startsWith("https://")) {
                val profileName = extractProfileNameFromUrl(data)
                return@withContext QRCodeParseResult.RemoteProfile(
                    name = profileName,
                    host = extractHostFromUrl(data),
                    url = data,
                )
            }

            // Try to decode as profile content
            val content =
                try {
                    ProfileContentDecoder.decode(data.toByteArray())
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return@withContext QRCodeParseResult.Error(
                        io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e),
                    )
                }

            return@withContext QRCodeParseResult.LocalProfile(name = content.name)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            QRCodeParseResult.Error(io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e))
        }
    }

    suspend fun importFromQRCode(rawData: String): ImportResult = withContext(Dispatchers.IO) {
        val data = rawData.trim()
        try {
            // Check if it's a sing-box remote profile import link
            if (data.startsWith("sing-box://import-remote-profile")) {
                try {
                    val profileInfo = Libbox.parseRemoteProfileImportLink(data)
                    return@withContext importRemoteProfile(profileInfo.name, profileInfo.url)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    return@withContext ImportResult.Error(
                        io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e),
                    )
                }
            }

            // Check if it's a URL or direct profile content
            if (data.startsWith("http://") || data.startsWith("https://")) {
                // Handle remote profile URL
                val profileName = extractProfileNameFromUrl(data)
                importRemoteProfile(profileName, data)
            } else {
                // Try to decode as profile content
                val content =
                    try {
                        ProfileContentDecoder.decodeWithReport(data.toByteArray())
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        return@withContext ImportResult.Error(
                            io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e),
                        )
                    }
                importProfile(content)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Error(io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e))
        }
    }

    suspend fun parseQRSData(data: ByteArray): QRSParseResult = withContext(Dispatchers.IO) {
        try {
            val content = try {
                ProfileContentDecoder.decode(data)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@withContext QRSParseResult.Error(
                    io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e),
                )
            }
            QRSParseResult.Success(name = content.name)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            QRSParseResult.Error(io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e))
        }
    }

    suspend fun importFromQRSData(data: ByteArray): ImportResult = withContext(Dispatchers.IO) {
        try {
            val content = try {
                ProfileContentDecoder.decodeWithReport(data)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@withContext ImportResult.Error(
                    io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e),
                )
            }
            importProfile(content)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Error(io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e))
        }
    }

    private suspend fun importProfile(decoded: io.nekohasekai.sfa.utils.DecodedProfileContent): ImportResult {
        val content = decoded.content
        val typedProfile = TypedProfile()
        val profile = Profile(name = content.name, typed = typedProfile)

        when (content.type) {
            Libbox.ProfileTypeLocal -> {
                typedProfile.type = TypedProfile.Type.Local
            }
            Libbox.ProfileTypeiCloud -> {
                return ImportResult.Error(context.getString(R.string.icloud_profile_unsupported))
            }
            Libbox.ProfileTypeRemote -> {
                typedProfile.type = TypedProfile.Type.Remote
                typedProfile.remoteURL = content.remotePath
                typedProfile.autoUpdate = content.autoUpdate
                typedProfile.autoUpdateInterval = content.autoUpdateInterval
                typedProfile.lastUpdated = Date(content.lastUpdated)
            }
        }

        ProfileManager.createImported(profile, content.config, decoded.report)
        afterSaved(profile)
        return ImportResult.Success(profile)
    }

    private suspend fun importRemoteProfile(name: String, url: String): ImportResult {
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Remote
                remoteURL = url
                autoUpdate = true
                autoUpdateInterval = 60
                lastUpdated = Date()
            }

        val profile =
            Profile(name = name, typed = typedProfile)

        val result = io.nekohasekai.sfa.utils.HTTPClient().use { it.fetchSubscription(url) }
        profile.name = result.profileName ?: name
        result.updateIntervalMinutes?.let { typedProfile.autoUpdateInterval = it }
        ProfileManager.createImported(profile, result.config, result.report)
        afterSaved(profile)
        return ImportResult.Success(profile)
    }

    private suspend fun afterSaved(profile: Profile) {
        // A notification or scheduler failure must not turn a committed import into
        // an apparent creation failure, which otherwise encourages duplicate retry.
        if (profile.typed.type == TypedProfile.Type.Remote && profile.typed.autoUpdate) {
            runCatching { io.nekohasekai.sfa.bg.UpdateProfileWork.reconfigureUpdater() }.onFailure {
                android.util.Log.w("ProfileImport", "IMPORT_UPDATER_SCHEDULING_FAILED")
            }
        }
        try {
            io.nekohasekai.sfa.compose.base.ImportReportNotifier.show(profile)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            android.util.Log.w("ProfileImport", "IMPORT_REPORT_PRESENTATION_FAILED")
        }
    }

    private fun extractProfileNameFromUrl(url: String): String = io.nekohasekai.sfa.utils.SubscriptionMetadata.nameFromUrl(url)

    private fun extractHostFromUrl(url: String): String = try {
        val uri = Uri.parse(url)
        uri.host ?: url
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        url
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var filename = "Imported Profile"

        // Try to get filename from content resolver
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                filename = cursor.getString(nameIndex)
                    ?.substringBeforeLast(".") // Remove extension
                    ?.takeIf { it.isNotEmpty() }
                    ?: filename
            }
        }

        // Fallback to getting from URI path
        if (filename == "Imported Profile") {
            uri.lastPathSegment?.let { segment ->
                filename = segment
                    .substringBeforeLast(".")
                    .takeIf { it.isNotEmpty() }
                    ?: filename
            }
        }

        return filename
    }

    private fun isJsonConfiguration(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return false
        }

        return try {
            // Try to parse as JSON and check for sing-box configuration fields
            val json = JSONObject(content)
            // Check for common sing-box configuration fields
            json.has("inbounds") ||
                json.has("outbounds") ||
                json.has("route") ||
                json.has("dns") ||
                json.has("experimental")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // If it's an array, it might still be valid
            trimmed.startsWith("[") && trimmed.endsWith("]")
        }
    }

    private suspend fun importJsonConfiguration(jsonContent: String, profileName: String): ImportResult {
        return try {
            val converted = if (io.nekohasekai.sfa.utils.SubscriptionContentParser.supports(jsonContent)) {
                io.nekohasekai.sfa.utils.HTTPClient().use { it.parseSubscription(jsonContent) }
            } else {
                null
            }
            val config = converted?.config ?: jsonContent
            // Create a local profile with the JSON configuration
            val typedProfile =
                TypedProfile().apply {
                    type = TypedProfile.Type.Local
                }

            val profile =
                Profile(
                    name = profileName.ifEmpty { "Imported Profile" },
                    typed = typedProfile,
                )

            ProfileManager.createImported(profile, config, converted?.report)
            afterSaved(profile)
            ImportResult.Success(profile)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Error(io.nekohasekai.sfa.utils.ImportResultFormatter.creationFailure(e))
        }
    }
}
