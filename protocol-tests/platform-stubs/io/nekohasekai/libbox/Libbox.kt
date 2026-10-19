package io.nekohasekai.libbox
/** JNI is injected in platform-adapter tests; these do not claim native format validation. */
object Libbox {
    var check: (String) -> Unit = {}
    fun checkConfig(content: String) = check(content)
    fun newHTTPClient() = HTTPClient()
}
fun interface InterfaceUpdateListener {
    fun updateDefaultInterface(name: String, index: Int, expensive: Boolean, constrained: Boolean)
}

/** Deterministic JNI substitute: wire cancellation/stream bounds are tested separately in Go. */
class HTTPClient {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val calls = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.CompletableFuture<*>>()
    private var timeout = 30
    fun modernTLS() {}
    fun setTimeout(seconds: Int) { timeout = seconds }
    fun newRequest() = HTTPRequest(this)
    fun close() { closed.set(true); calls.forEach { it.cancel(true) } }
    internal fun execute(url: String, limit: Long): HTTPResponse {
        check(!closed.get())
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI(url))
            .timeout(java.time.Duration.ofSeconds(timeout.toLong())).build()
        val future = java.net.http.HttpClient.newHttpClient().sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
        calls.add(future)
        if (closed.get()) future.cancel(true)
        val result = future.get()
        check(result.statusCode() == 200)
        return HTTPResponse(result.body(), limit)
    }
}
class HTTPRequest(private val client: HTTPClient) {
    private var url = ""
    private var limit = 0L
    fun setURL(url: String) { this.url = url }
    fun setUserAgent(value: String) {}
    fun setResponseLimit(bytes: Long) { limit = bytes }
    fun execute() = client.execute(url, limit)
}
class HTTPResponse(private val bytes: ByteArray, private val limit: Long) {
    fun writeTo(path: String) {
        check(limit <= 0 || bytes.size <= limit) { "HTTP_RESPONSE_TOO_LARGE" }
        java.io.File(path).writeBytes(bytes)
    }
}
class Notification
interface ExchangeContext { fun success(value: String) }
interface LocalDNSTransport {
    fun raw(): Boolean
    fun exchange(ctx: ExchangeContext, message: ByteArray)
    fun lookup(ctx: ExchangeContext, network: String, domain: String)
}
