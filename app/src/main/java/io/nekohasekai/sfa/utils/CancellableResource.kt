package io.nekohasekai.sfa.utils

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Close must be idempotent and unblock an active native operation. No Thread.interrupt assumption. */
internal suspend fun <T> withCancellationClose(close: () -> Unit, block: suspend () -> T): T = coroutineScope {
    val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { close() }
    }
    try { withContext(Dispatchers.IO) { block() } }
    finally { cancellation.cancel(); close() }
}
