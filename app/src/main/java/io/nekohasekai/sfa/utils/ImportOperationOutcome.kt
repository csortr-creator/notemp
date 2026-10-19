package io.nekohasekai.sfa.utils

import java.io.File

/**
 * B3-2 operation-level result of import / remote update.
 * Independent of per-node [ImportIssueCode] list.
 */
enum class ImportOperationOutcome {
    /** New config bytes written; metadata OK. */
    APPLIED,

    /** Commit succeeded but file bytes unchanged. */
    APPLIED_UNCHANGED,

    /** New config written; some nodes were rejected (imported < received). */
    PARTIAL_APPLIED,

    /** Previous config left in place (validate/commit/fetch failure on update). */
    KEPT_LKG,

    /** First import failed; no profile config applied. */
    REJECTED,

    /** Config file replaced; report/metadata after commit failed. */
    FILE_COMMITTED_METADATA_FAILED,
}

/**
 * Builds user-visible text from outcome + optional parse report.
 * Never includes URLs, UUIDs, keys, or raw exception messages.
 */
internal object ImportResultFormatter {
    fun partialPreview(report: SubscriptionImportReport): String = format(ImportOperationOutcome.KEPT_LKG, report).substringAfter('\n') +
        "\n\nЧасть серверов не удалось импортировать. " +
        "Применение может уменьшить список доступных серверов."

    fun format(
        outcome: ImportOperationOutcome,
        report: SubscriptionImportReport? = null,
        hadPreviousConfig: Boolean = true,
        reportIsCurrent: Boolean = true,
        reason: String? = null,
    ): String = buildString {
        append(outcomeLine(outcome, hadPreviousConfig))
        if (reason != null) append("\nПричина: $reason")
        if (report != null) {
            append("\n")
            if (!reportIsCurrent) append("Сохранённый отчёт предыдущего импорта:\n")
            append(statsLine(report))
            if (report.issues.isNotEmpty()) {
                append("\nОтклонено записей: ${report.issues.size}")
                for (issue in report.issues.take(20)) {
                    // Legacy reports and provider names can contain arbitrary text.
                    // Render bounded display metadata; keep reasons typed and URI-free.
                    val category = SubscriptionDiagnostics.issueDetail(issue) ?: when (issue.code) {
                        ImportIssueCode.FORMAT_ERROR -> "Некорректный формат записи"
                        ImportIssueCode.PARSE_ERROR -> "Не удалось разобрать параметры"
                        ImportIssueCode.UNSUPPORTED_FEATURE -> "Параметры не поддерживаются"
                        ImportIssueCode.NODE_CORE_INVALID -> "Ядро отклонило параметры сервера"
                        ImportIssueCode.UNKNOWN -> "Причина пока не определена"
                    }
                    append("\nЗапись ${issue.line} · ${SubscriptionDiagnostics.issueName(issue)}: $category [${issue.code.name}]")
                }
                if (report.issues.size > 20) append("\nЕщё отклонено: ${report.issues.size - 20}")
            }
            if (outcome == ImportOperationOutcome.PARTIAL_APPLIED &&
                report.received > 0 &&
                report.imported * 2 < report.received
            ) {
                append("\nВнимание: импортировано меньше половины полученных серверов")
            }
        }
    }

    fun creationFailure(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(32).toList()
        val creationStage = chain.filterIsInstance<ImportCreationFailure>().firstOrNull()?.stage
        val reason = when (creationStage) {
            ImportCreationStage.VALIDATE -> "CONFIG_INVALID: ядро отклонило итоговую конфигурацию"
            ImportCreationStage.WRITE -> "CONFIG_WRITE_FAILED: не удалось сохранить файл профиля"
            ImportCreationStage.REPORT -> "IMPORT_REPORT_WRITE_FAILED: не удалось сохранить отчёт импорта"
            ImportCreationStage.DATABASE -> "PROFILE_DATABASE_FAILED: не удалось сохранить профиль в базе"
            null -> if (chain.any { it is ImportValidationFailure }) {
                "CONFIG_INVALID: ядро отклонило итоговую конфигурацию"
            } else SubscriptionDiagnostics.reason(error)
        }
        return format(
            ImportOperationOutcome.REJECTED,
            SubscriptionDiagnostics.reportOf(error),
            hadPreviousConfig = false,
            reason = reason,
        )
    }

    fun commitFailureReason(error: Throwable): String = if (generateSequence(error) { it.cause }.any { it is ImportValidationFailure }) {
        "CONFIG_INVALID: ядро отклонило итоговую конфигурацию; файл не изменён"
    } else {
        "WRITE_FAILED: не удалось записать файл конфигурации"
    }

    fun fetchFailureReason(error: Throwable): String = SubscriptionDiagnostics.reason(error)

    fun outcomeLine(outcome: ImportOperationOutcome, hadPreviousConfig: Boolean): String = when (outcome) {
        ImportOperationOutcome.APPLIED ->
            "Конфигурация обновлена"
        ImportOperationOutcome.APPLIED_UNCHANGED ->
            "Подписка без изменений"
        ImportOperationOutcome.PARTIAL_APPLIED ->
            "Конфигурация обновлена, часть серверов пропущена"
        ImportOperationOutcome.KEPT_LKG ->
            if (hadPreviousConfig) {
                "Обновление не применено, прежняя конфигурация сохранена"
            } else {
                "Обновление не применено"
            }
        ImportOperationOutcome.REJECTED ->
            "Импорт отклонён"
        ImportOperationOutcome.FILE_COMMITTED_METADATA_FAILED ->
            "Файл конфигурации обновлён, но метаданные не сохранены"
    }

    fun statsLine(report: SubscriptionImportReport): String {
        val skipped =
            if (report.issues.isNotEmpty()) {
                " · Отклонено: ${report.issues.size}"
            } else {
                ""
            }
        return "Получено: ${report.received} · Импортировано: ${report.imported}$skipped"
    }

    fun fromCommit(
        replaced: Boolean,
        report: SubscriptionImportReport?,
        metadataFailed: Boolean = false,
    ): ImportOperationOutcome {
        if (metadataFailed) return ImportOperationOutcome.FILE_COMMITTED_METADATA_FAILED
        if (!replaced) return ImportOperationOutcome.APPLIED_UNCHANGED
        val partial =
            report != null && report.received > 0 && report.imported < report.received
        return if (partial) {
            ImportOperationOutcome.PARTIAL_APPLIED
        } else {
            ImportOperationOutcome.APPLIED
        }
    }

    fun configFileExists(path: String): Boolean = path.isNotBlank() && File(path).exists()
}

/** Carries the validation stage without exposing the native exception text to UI. */
internal class ImportValidationFailure(cause: Throwable) : Exception(cause)
