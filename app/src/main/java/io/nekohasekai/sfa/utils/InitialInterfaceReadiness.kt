package io.nekohasekai.sfa.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Libbox starts using the network as soon as monitor registration returns. */
internal fun <T : Any> publishInitialInterface(
    resolve: () -> T?,
    publish: (T) -> Unit,
    attempts: Int = 50,
    retryDelayMs: Long = 100L,
) = runBlocking {
    repeat(attempts) { attempt ->
        resolve()?.let {
            publish(it)
            return@runBlocking
        }
        if (attempt + 1 < attempts) delay(retryDelayMs)
    }
    error("Основной сетевой интерфейс ещё не готов; повторите подключение после восстановления сети")
}
