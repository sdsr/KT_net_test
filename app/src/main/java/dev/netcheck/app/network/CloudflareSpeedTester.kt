package dev.netcheck.app.network

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import dev.netcheck.app.data.SpeedProgress
import dev.netcheck.app.data.SpeedResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded HTTP payload measurement, not a reproduction of Cloudflare's browser test engine. */
class CloudflareSpeedTester {
    suspend fun run(
        network: Network,
        limitBytes: Long = DEFAULT_LIMIT_BYTES,
        onProgress: (SpeedProgress) -> Unit = {},
    ): SpeedResult {
        require(limitBytes in 1..MAX_LIMIT_BYTES)
        onProgress(SpeedProgress("PREPARING"))
        val dnsStarted = System.nanoTime()
        val addresses = try {
            withTimeoutOrNull(3_000) { resolve(network, HOST) }
                ?: return failedPreparation("DNS_TIMEOUT")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failedPreparation("DNS_ERROR: ${error.message?.take(160)}")
        }
        val dnsMs = (System.nanoTime() - dnsStarted) / 1_000_000.0
        val client = configuredClient().newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == HOST) addresses else throw IOException("Unexpected host")
            })
            .build()
        return try {
            runWithClient(client, BASE_URL, limitBytes, onProgress, dnsMs = dnsMs)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    internal suspend fun runWithClient(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        limitBytes: Long,
        onProgress: (SpeedProgress) -> Unit = {},
        directionDurationNanos: Long = DIRECTION_NANOS,
        dnsMs: Double? = null,
    ): SpeedResult {
        require(limitBytes in 1..MAX_LIMIT_BYTES)
        require(directionDurationNanos > 0)
        val progressGate = ProgressGate(onProgress)
        return try {
            runPhases(client, baseUrl, limitBytes, progressGate::emit, directionDurationNanos, dnsMs)
        } finally {
            // Cancellation may resume this coroutine before OkHttp's streaming callback exits.
            // Closing under the callback lock waits for in-flight emissions and rejects later ones.
            progressGate.close()
        }
    }

    private suspend fun runPhases(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        limitBytes: Long,
        onProgress: (SpeedProgress) -> Unit,
        directionDurationNanos: Long,
        dnsMs: Double?,
    ): SpeedResult {
        val down = direction(client, baseUrl, "DOWNLOAD", limitBytes, directionDurationNanos, onProgress)
        currentCoroutineContext().ensureActive()
        val up = direction(client, baseUrl, "UPLOAD", limitBytes, directionDurationNanos, onProgress)
        val errors = listOfNotNull(down.error, up.error)
        val hasResult = down.mbps != null || up.mbps != null
        val status = when {
            !hasResult -> "FAILED"
            down.error != null || up.error != null || down.mbps == null || up.mbps == null -> "PARTIAL"
            down.limited || up.limited -> "LIMITED"
            else -> "SUCCESS"
        }
        val details = JSONObject()
            .put("method", "cloudflare_http_payload_v1")
            .put("description", "HTTP payload goodput; not the official Cloudflare browser engine")
            .put("limitBytesPerDirection", limitBytes)
            .put("maxDirectionMs", directionDurationNanos / 1_000_000L)
            .put("dnsMs", dnsMs ?: JSONObject.NULL)
            .put("download", down.json())
            .put("upload", up.json())
            .put("networkOverheadIncluded", false)
            .put("uploadBytesMeaning", "bytes written to request stream; only successful HTTP exchanges contribute to upload Mbps")
        return SpeedResult(down.mbps, up.mbps, down.observedBytes, up.observedBytes, status,
            errors.takeIf { it.isNotEmpty() }?.joinToString("; "), details.toString())
    }

    private suspend fun direction(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        phase: String,
        limit: Long,
        durationNanos: Long,
        onProgress: (SpeedProgress) -> Unit,
    ): DirectionResult {
        val started = System.nanoTime()
        val result = DirectionResult(phase)
        val download = phase == "DOWNLOAD"
        onProgress(SpeedProgress(phase))
        var index = 0
        while (result.observedBytes < limit) {
            currentCoroutineContext().ensureActive()
            val remainingNs = durationNanos - (System.nanoTime() - started)
            if (remainingNs <= 0) { result.reason = "TIME_LIMIT"; break }
            val size = SpeedMath.nextChunk(limit - result.observedBytes, index++)
            val observed = AtomicLong()
            val trace = TransferTrace()
            val previousBytes = result.observedBytes
            val transferStarted = System.nanoTime()
            var lastProgress = transferStarted
            fun progress() {
                val now = System.nanoTime()
                if (now - lastProgress < 200_000_000L) return
                lastProgress = now
                val bytes = previousBytes + observed.get()
                // Upload buffering is not evidence of delivered throughput.
                onProgress(SpeedProgress(phase, bytes,
                    if (download) SpeedMath.mbps(bytes, result.measurementNanos + now - transferStarted) else null))
            }
            val url = baseUrl.newBuilder().addPathSegment(if (download) "__down" else "__up")
                .addQueryParameter("bytes", size.toString()).build()
            val request = Request.Builder().url(url).tag(TransferTrace::class.java, trace)
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-store")
                .header("User-Agent", "NetCheck-Android/0.1 HTTP-Payload-Measurement")
                .apply { if (!download) post(SyntheticBody(size, observed, ::progress)) }
                .build()
            val call = client.newCall(request)
            call.timeout().timeout(remainingNs.coerceAtLeast(1), TimeUnit.NANOSECONDS)
            var succeeded = false
            var sampleError: String? = null
            try {
                consume(call) { response ->
                    trace.httpStatus = response.code
                    if (!response.isSuccessful) throw HttpStatusException(response.code)
                    if (download) {
                        val encoding = response.header("Content-Encoding")
                        if (encoding != null && !encoding.equals("identity", true)) throw ProtocolException("Unexpected compressed response")
                        val body = response.body ?: throw ProtocolException("Missing response body")
                        if (body.contentLength() > size) throw ProtocolException("Response exceeds requested bytes")
                        val buffer = ByteArray(16 * 1024)
                        body.byteStream().use { stream ->
                            while (true) {
                                val allowed = minOf(buffer.size.toLong(), size - observed.get() + 1).toInt()
                                val count = stream.read(buffer, 0, allowed)
                                if (count < 0) break
                                observed.addAndGet(count.toLong())
                                if (observed.get() > size) throw ProtocolException("Response exceeds requested bytes")
                                progress()
                            }
                        }
                        if (observed.get() != size) throw ProtocolException("Truncated response")
                    } else {
                        // The response is checked after the full request body was sent.
                        if (observed.get() != size) throw ProtocolException("Incomplete upload")
                        response.body?.byteStream()?.use { stream ->
                            val buffer = ByteArray(4096)
                            var read = 0
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                read += count
                                if (read > 65_536) throw ProtocolException("Upload acknowledgement too large")
                            }
                        }
                    }
                }
                succeeded = true
            } catch (cancelled: CancellationException) {
                // Deliver the final count even when the regular 200 ms progress tick has not fired.
                onProgress(SpeedProgress(phase, previousBytes + observed.get(), result.mbps))
                throw cancelled
            } catch (error: Exception) {
                val timedOut = System.nanoTime() - started >= durationNanos - 10_000_000L
                result.reason = if (timedOut) "TIME_LIMIT" else "ERROR"
                sampleError = when (error) {
                    is HttpStatusException -> "HTTP_${error.code}"
                    is javax.net.ssl.SSLException -> "TLS_ERROR"
                    is java.net.SocketTimeoutException -> "NETWORK_TIMEOUT"
                    is ProtocolException -> "INVALID_RESPONSE: ${error.message}"
                    else -> "IO_ERROR: ${error.message?.take(120)}"
                }
                if (!timedOut) result.error = "$phase $sampleError"
            }
            val elapsed = (System.nanoTime() - transferStarted).coerceAtLeast(1)
            result.observedBytes += observed.get()
            if (download && observed.get() > 0) {
                result.measurementBytes += observed.get()
                result.measurementNanos += elapsed
            } else if (!download && succeeded) {
                result.measurementBytes += size
                result.measurementNanos += elapsed
            }
            if (succeeded) result.completedBytes += size
            result.samples.put(trace.json().put("requestedBytes", size)
                .put("observedBytes", observed.get()).put("elapsedMs", elapsed / 1_000_000.0)
                .put("complete", succeeded).put("error", sampleError ?: JSONObject.NULL))
            onProgress(SpeedProgress(phase, result.observedBytes, result.mbps))
            if (!succeeded) break
        }
        if (result.observedBytes >= limit && result.reason != "ERROR") result.reason = "DATA_LIMIT"
        result.elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        return result
    }

    private suspend fun resolve(network: Network, host: String): List<InetAddress> = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY, Executor { it.run() }, cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    if (!continuation.isActive) return
                    if (rcode == 0 && answer.isNotEmpty()) continuation.resume(answer)
                    else continuation.resumeWithException(IOException("DNS rcode=$rcode, empty=${answer.isEmpty()}"))
                }
                override fun onError(error: DnsResolver.DnsException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            })
    }

    /** Retains cancellation registration through streaming, rather than only until headers arrive. */
    private suspend fun consume(call: Call, block: (Response) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { if (continuation.isActive) block(it) }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private class SyntheticBody(
        private val size: Long,
        private val observed: AtomicLong,
        private val progress: () -> Unit,
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = size
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            // Generated bytes only; never uploads files or any collected location/cell history.
            val chunk = ByteArray(16 * 1024) { ((it * 31 + 17) and 255).toByte() }
            while (observed.get() < size) {
                val count = minOf(chunk.size.toLong(), size - observed.get()).toInt()
                sink.write(chunk, 0, count)
                observed.addAndGet(count.toLong())
                progress()
            }
        }
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    private class ProgressGate(private val callback: (SpeedProgress) -> Unit) {
        private val lock = Any()
        private var closed = false

        fun emit(progress: SpeedProgress) = synchronized(lock) {
            if (!closed) callback(progress)
        }

        fun close() = synchronized(lock) {
            closed = true
        }
    }

    private class DirectionResult(val phase: String) {
        var observedBytes = 0L
        var completedBytes = 0L
        var measurementBytes = 0L
        var measurementNanos = 0L
        var elapsedMs = 0.0
        var reason = "COMPLETE"
        var error: String? = null
        val samples = JSONArray()
        val mbps get() = SpeedMath.mbps(measurementBytes, measurementNanos)
        val limited get() = reason == "TIME_LIMIT" || reason == "DATA_LIMIT"
        fun json() = JSONObject().put("phase", phase).put("observedPayloadBytes", observedBytes)
            .put("completedPayloadBytes", completedBytes).put("measurementBytes", measurementBytes)
            .put("measurementMs", measurementNanos / 1_000_000.0).put("elapsedMs", elapsedMs)
            .put("endReason", reason).put("incomplete", reason != "COMPLETE")
            .put("mbps", mbps ?: JSONObject.NULL).put("samples", samples)
    }

    private class TransferTrace : EventListener() {
        private var connectStart = 0L
        private var tlsStart = 0L
        private var requestStart = 0L
        var connectionSetupMs: Double? = null
        var tlsMs: Double? = null
        var responseWaitMs: Double? = null
        var httpStatus: Int? = null
        var remoteAddress: String? = null
        var protocol: String? = null
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStart = System.nanoTime()
            remoteAddress = inetSocketAddress.address.hostAddress
        }
        override fun secureConnectStart(call: Call) { tlsStart = System.nanoTime() }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            tlsMs = (System.nanoTime() - tlsStart) / 1_000_000.0
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            connectionSetupMs = (System.nanoTime() - connectStart) / 1_000_000.0
            this.protocol = protocol?.toString()
        }
        override fun requestHeadersStart(call: Call) { requestStart = System.nanoTime() }
        override fun responseHeadersStart(call: Call) {
            responseWaitMs = (System.nanoTime() - requestStart) / 1_000_000.0
        }
        fun json() = JSONObject().put("connectionSetupIncludingTlsMs", connectionSetupMs ?: JSONObject.NULL)
            .put("tlsMs", tlsMs ?: JSONObject.NULL).put("requestToResponseHeadersMs", responseWaitMs ?: JSONObject.NULL)
            .put("httpStatus", httpStatus ?: JSONObject.NULL).put("remoteAddress", remoteAddress ?: JSONObject.NULL)
            .put("protocol", protocol ?: JSONObject.NULL)
    }

    private fun failedPreparation(error: String) = SpeedResult(null, null, 0, 0, "FAILED", error,
        JSONObject().put("method", "cloudflare_http_payload_v1").put("stage", "DNS").put("incomplete", true).toString())

    companion object {
        const val DEFAULT_LIMIT_BYTES = 20 * SpeedMath.MIB
        const val MAX_LIMIT_BYTES = 100 * SpeedMath.MIB
        private const val DIRECTION_NANOS = 10_000_000_000L
        private const val HOST = "speed.cloudflare.com"
        private val BASE_URL = "https://$HOST/".toHttpUrl()

        internal fun configuredClient() = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY).cache(null).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).writeTimeout(3, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES))
            .eventListenerFactory { call -> call.request().tag(TransferTrace::class.java) ?: EventListener.NONE }
            .build()
    }
}
