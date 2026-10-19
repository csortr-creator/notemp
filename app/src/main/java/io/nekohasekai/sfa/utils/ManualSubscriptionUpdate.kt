package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import java.util.concurrent.atomic.AtomicReference

enum class PendingPhase {
    PENDING,
    APPLYING,
    CONSUMED,
    STALE,
    CANCELLED,
}

class PendingImportHolder(
    val profileId: Long,
    val profileName: String,
    val configPath: String,
    val remoteURL: String,
    val content: String,
    val report: SubscriptionImportReport,
    val expected: ConfigExpectedState,
    val phase: AtomicReference<PendingPhase> = AtomicReference(PendingPhase.PENDING),
) {
    val received: Int get() = report.received
    val imported: Int get() = report.imported
    val rejected: Int get() = report.issues.size
}

sealed class ManualPrepareResult {
    data class Applied(val outcome: ImportOperationOutcome, val replaced: Boolean, val report: SubscriptionImportReport? = null) : ManualPrepareResult()
    data class AwaitConfirmation(val pending: PendingImportHolder) : ManualPrepareResult()
    data class Rejected(val outcome: ImportOperationOutcome, val safeMessage: String = "Обновление не применено", val report: SubscriptionImportReport? = null) : ManualPrepareResult()
    data class Failed(val safeMessage: String, val report: SubscriptionImportReport? = null) : ManualPrepareResult()
}

object ManualSubscriptionUpdate {

    suspend fun prepare(profile: Profile): ManualPrepareResult {
        if (profile.typed.type != TypedProfile.Type.Remote) {
            return ManualPrepareResult.Failed("Профиль не является удалённой подпиской")
        }
        if (ProfileConfigCommit.isMarkedDeleted(profile.id) || ProfileManager.get(profile.id) == null) {
            return ManualPrepareResult.Rejected(ImportOperationOutcome.KEPT_LKG, "PROFILE_UNAVAILABLE: профиль удалён или недоступен")
        }
        val remoteURL = profile.typed.remoteURL
        val hadPrevious = ImportResultFormatter.configFileExists(profile.typed.path)
        return try {
            val expected = ProfileConfigCommit.beginUpdate(profile.id, profile.typed.path)
            val result = HTTPClient().use { it.fetchSubscription(remoteURL) }
            if (!ProfileConfigCommit.isCurrent(profile.id, requireNotNull(expected.operationToken))) {
                return ManualPrepareResult.Rejected(ImportOperationOutcome.KEPT_LKG, "STALE: начато более новое обновление", result.report)
            }
            val report = result.report
            val decision =
                PartialUpdatePolicy.decide(
                    PartialUpdateInput(
                        trigger = UpdateTrigger.MANUAL_UPDATE,
                        received = report.received,
                        imported = report.imported,
                        rejected = report.issues.size,
                        reliability = PartialUpdatePolicy.reliabilityOf(report),
                        hadPreviousConfig = hadPrevious,
                    ),
                )
            when (decision) {
                PolicyDecision.APPLY -> applyNow(profile, result, expected, remoteURL)
                PolicyDecision.AWAIT_CONFIRMATION -> {
                    ManualPrepareResult.AwaitConfirmation(
                        PendingImportHolder(
                            profileId = profile.id,
                            profileName = profile.name,
                            configPath = profile.typed.path,
                            remoteURL = remoteURL,
                            content = result.config,
                            report = report,
                            expected = expected,
                        ),
                    )
                }
                PolicyDecision.REJECT_KEEP_LKG,
                PolicyDecision.REJECT_FIRST,
                -> ManualPrepareResult.Rejected(ImportOperationOutcome.KEPT_LKG, "POLICY_REJECTED: нет пригодного результата для применения", report)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ManualPrepareResult.Failed(ImportResultFormatter.fetchFailureReason(e), SubscriptionDiagnostics.reportOf(e))
        }
    }

    private suspend fun applyNow(
        profile: Profile,
        result: SubscriptionImportResult,
        expected: ConfigExpectedState,
        remoteURL: String,
    ): ManualPrepareResult = when (
        val outcome =
            ProfileConfigCommit.commitIfUnchanged(
                expected = expected,
                content = result.config,
                validate = ::validateConfig,
                sourceStillMatches = { ProfileManager.subscriptionSourceMatches(profile.id, expected.configPath, remoteURL) },
                afterFileCommit = {
                    result.report.save(expected.configPath)
                    profile.typed.lastUpdated = ProfileManager.markSubscriptionUpdated(
                        profile.id, expected.configPath, remoteURL,
                    )
                },
            )
    ) {
        is CommitOutcome.Success ->
            ManualPrepareResult.Applied(
                ImportResultFormatter.fromCommit(outcome.replaced, result.report),
                outcome.replaced,
                result.report,
            )
        is CommitOutcome.FileCommittedMetadataFailed ->
            ManualPrepareResult.Applied(
                ImportOperationOutcome.FILE_COMMITTED_METADATA_FAILED,
                outcome.replaced,
                result.report,
            )
        is CommitOutcome.Stale -> ManualPrepareResult.Rejected(
            ImportOperationOutcome.KEPT_LKG,
            "STALE: профиль или конфигурация изменились",
            result.report,
        )
        is CommitOutcome.Failed -> {
            if (outcome.error is kotlinx.coroutines.CancellationException) throw outcome.error
            ManualPrepareResult.Rejected(
                ImportOperationOutcome.KEPT_LKG,
                ImportResultFormatter.commitFailureReason(outcome.error),
                result.report,
            )
        }
    }

    suspend fun confirm(pending: PendingImportHolder): ManualPrepareResult {
        if (!pending.phase.compareAndSet(PendingPhase.PENDING, PendingPhase.APPLYING)) {
            return ManualPrepareResult.Rejected(ImportOperationOutcome.KEPT_LKG, "CONFIRM_NOT_PENDING: подтверждение отменено или уже обработано", pending.report)
        }
        val profile = ProfileManager.get(pending.profileId)
        return when (
            val outcome =
                ProfileConfigCommit.commitIfUnchanged(
                    expected = pending.expected,
                    content = pending.content,
                    validate = ::validateConfig,
                    sourceStillMatches = { ProfileManager.subscriptionSourceMatches(pending.profileId, pending.configPath, pending.remoteURL) },
                    afterFileCommit = {
                        pending.report.save(pending.configPath)
                        val updatedAt = ProfileManager.markSubscriptionUpdated(
                            pending.profileId, pending.configPath, pending.remoteURL,
                        )
                        profile?.typed?.lastUpdated = updatedAt
                    },
                )
        ) {
            is CommitOutcome.Success -> {
                pending.phase.set(PendingPhase.CONSUMED)
                ManualPrepareResult.Applied(
                    ImportResultFormatter.fromCommit(outcome.replaced, pending.report),
                    outcome.replaced,
                    pending.report,
                )
            }
            is CommitOutcome.FileCommittedMetadataFailed -> {
                pending.phase.set(PendingPhase.CONSUMED)
                ManualPrepareResult.Applied(
                    ImportOperationOutcome.FILE_COMMITTED_METADATA_FAILED,
                    outcome.replaced,
                    pending.report,
                )
            }
            is CommitOutcome.Stale -> {
                pending.phase.set(PendingPhase.STALE)
                ManualPrepareResult.Rejected(ImportOperationOutcome.KEPT_LKG, "STALE: профиль или конфигурация изменились", pending.report)
            }
            is CommitOutcome.Failed -> {
                pending.phase.set(PendingPhase.STALE)
                if (outcome.error is kotlinx.coroutines.CancellationException) throw outcome.error
                ManualPrepareResult.Rejected(ImportOperationOutcome.KEPT_LKG, ImportResultFormatter.commitFailureReason(outcome.error), pending.report)
            }
        }
    }

    private fun validateConfig(content: String) {
        try {
            Libbox.checkConfig(content)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ImportValidationFailure(e)
        }
    }

    fun cancel(pending: PendingImportHolder) {
        pending.phase.compareAndSet(PendingPhase.PENDING, PendingPhase.CANCELLED)
    }
}
