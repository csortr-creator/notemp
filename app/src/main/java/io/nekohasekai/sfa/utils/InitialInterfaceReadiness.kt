package io.nekohasekai.sfa.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Libbox starts using the network as soon as monitor registration returns. */
internal fun <T : Any> publishInitialInterface(
    resolve: () -> T?,
    publish: (T) -> Boolean,
    attempts: Int = 50,
    retryDelayMs: Long = 100L,
    isCurrent: () -> Boolean = { true },
) = runBlocking {
    repeat(attempts) { attempt ->
        if (!isCurrent()) throw CancellationException("Interface registration was replaced")
        resolve()?.let {
            if (!isCurrent()) throw CancellationException("Interface registration was replaced")
            if (publish(it)) return@runBlocking
        }
        if (attempt + 1 < attempts) delay(retryDelayMs)
    }
    error("Основной сетевой интерфейс ещё не готов; повторите подключение после восстановления сети")
}
