package io.nekohasekai.sfa.utils

import org.json.JSONException
import org.yaml.snakeyaml.error.YAMLException
import java.util.concurrent.CancellationException

internal enum class SubscriptionStage { FETCH, PARSE, METADATA }
internal class SubscriptionStageFailure(val stage: SubscriptionStage, cause: Exception) : Exception(cause)
internal enum class SubscriptionResponseProblem { HTML, TOO_LARGE, NO_USABLE_NODES }
internal class SubscriptionResponseFailure(
    val problem: SubscriptionResponseProblem,
    val report: SubscriptionImportReport? = null,
) : IllegalArgumentException(
    when (problem) {
        SubscriptionResponseProblem.HTML -> "Сервер вернул HTML вместо подписки"
        SubscriptionResponseProblem.TOO_LARGE -> "Подписка превышает допустимый размер (8 МиБ)"
        SubscriptionResponseProblem.NO_USABLE_NODES -> "Нет пригодных серверов"
    },
)

/** Stage markers remain internal; presentation emits only fixed codes/text. */
internal object SubscriptionDiagnostics {
    /** Display metadata only: never render a URI accidentally stored as a name. */
    fun issueName(issue: SubscriptionImportIssue): String {
        var inspected = issue.name
        repeat(2) {
            inspected = runCatching { ProxyLinkParser.decode(inspected) }.getOrDefault(inspected)
        }
        if (Regex("(?i)[a-z][a-z0-9+.-]*://|www\\.").containsMatchIn(inspected) ||
            inspected.contains('@') ||
            Regex("(?i)(password|passwd|token|uuid|authorization|private[_ -]?key|secret)\\s*[:=]").containsMatchIn(inspected) ||
            Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").containsMatchIn(inspected)
        ) {
            return "Название скрыто: содержит данные подключения"
        }
        return issue.name.map { c ->
            if (c.isISOControl() || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069') ' ' else c
        }.joinToString("").trim().take(120).ifBlank { "Название не указано" }
    }

    fun <T> at(stage: SubscriptionStage, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SubscriptionStageFailure(stage, e)
    }

    fun reportOf(error: Throwable): SubscriptionImportReport? = generateSequence(error) { it.cause }.filterIsInstance<SubscriptionResponseFailure>()
        .firstOrNull()?.report

    fun reason(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.filterIsInstance<SubscriptionResponseFailure>().any { it.problem == SubscriptionResponseProblem.TOO_LARGE }) {
            return "IMPORT_TOO_LARGE: ответ превышает 8 МиБ"
        }
        val stage = chain.filterIsInstance<SubscriptionStageFailure>().firstOrNull()?.stage
        return when (stage) {
            SubscriptionStage.FETCH ->
                "FETCH_${NetworkErrorPresentation.classify(error).kind.name}: не удалось загрузить ответ подписки"
            SubscriptionStage.METADATA ->
                "IMPORT_METADATA: не удалось обработать метаданные ответа подписки"
            SubscriptionStage.PARSE -> {
                val problem = chain.filterIsInstance<SubscriptionResponseFailure>().firstOrNull()?.problem
                when {
                    problem == SubscriptionResponseProblem.HTML -> "IMPORT_HTML: сервер вернул HTML вместо подписки"
                    problem == SubscriptionResponseProblem.TOO_LARGE -> "IMPORT_TOO_LARGE: ответ превышает 8 МиБ"
                    problem == SubscriptionResponseProblem.NO_USABLE_NODES -> "IMPORT_NO_USABLE_NODES: ни один сервер не удалось импортировать"
                    chain.any { it is JSONException } -> "IMPORT_JSON_INVALID: некорректный JSON подписки"
                    chain.any { it is YAMLException } -> "IMPORT_YAML_INVALID: некорректный YAML подписки"
                    else -> "IMPORT_PARSE_UNKNOWN: ответ получен, но не удалось разобрать подписку"
                }
            }
            null -> "${NetworkErrorPresentation.logCode(error)}: не удалось загрузить или разобрать ответ подписки"
        }
    }

    fun issueDetail(issue: SubscriptionImportIssue): String? {
        if (issue.code != ImportIssueCode.UNSUPPORTED_FEATURE) return null
        // Exact whitelist only: persisted reasons may contain arbitrary provider text.
        return when (issue.reason) {
            "Unsupported QUIC uTLS fingerprint" -> "uTLS fingerprint не поддерживается для QUIC этим ядром"
            "Unsupported conflicting Hysteria2 Chrome fingerprint options" -> "fp=chrome конфликтует с отключённым QUIC Chrome parrot"
            else -> ImportFeatureDiagnostics.detail(issue.reason)
        }
    }
}
