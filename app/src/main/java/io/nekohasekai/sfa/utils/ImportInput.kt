package io.nekohasekai.sfa.utils

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Same allocation bound for files, content providers and network subscription bodies. */
internal object ImportInput {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun checkSize(size: Int) {
        if (size > MAX_BYTES) throw SubscriptionResponseFailure(SubscriptionResponseProblem.TOO_LARGE)
    }
    fun read(input: InputStream, checkActive: () -> Unit = {}): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            checkSize(output.size() + count)
            output.write(buffer, 0, count)
        }
    }
}
