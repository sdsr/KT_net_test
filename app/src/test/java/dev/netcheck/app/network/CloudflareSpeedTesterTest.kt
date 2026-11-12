package dev.netcheck.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CloudflareSpeedTesterTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val tester = CloudflareSpeedTester()

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        client = CloudflareSpeedTester.configuredClient()
    }

    @After fun cleanup() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        server.shutdown()
    }

    @Test fun boundsBothDirectionsAndOnlyUsesMeasurementEndpoints() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val size = request.requestUrl!!.queryParameter("bytes")!!.toInt()
                return if (request.method == "GET") MockResponse().setBody(Buffer().write(ByteArray(size)))
                else MockResponse().setBody("ok")
            }
        }
        val result = tester.runWithClient(client, server.url("/"), 32_768)
        assertEquals("LIMITED", result.status)
        assertEquals(32_768L, result.downloadBytes)
        assertEquals(32_768L, result.uploadBytes)
        assertNotNull(result.downloadMbps)
        assertNotNull(result.uploadMbps)
        assertEquals(2, server.requestCount)
        val down = server.takeRequest()
        val up = server.takeRequest()
        assertTrue(down.path!!.startsWith("/__down?"))
        assertTrue(up.path!!.startsWith("/__up?"))
        assertEquals(32_768L, up.bodySize)
        assertEquals("identity", down.getHeader("Accept-Encoding"))
        val details = JSONObject(result.detailsJson)
        assertEquals("cloudflare_http_payload_v1", details.getString("method"))
        assertEquals("DATA_LIMIT", details.getJSONObject("download").getString("endReason"))
    }

    @Test fun unsuccessfulUploadNeverTurnsQueuedBytesIntoMbps() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(8192))))
        server.enqueue(MockResponse().setResponseCode(503))
        val result = tester.runWithClient(client, server.url("/"), 8192)
        assertNotNull(result.downloadMbps)
        assertNull(result.uploadMbps)
        assertEquals("PARTIAL", result.status)
        assertTrue(result.error!!.contains("HTTP_503"))
        assertEquals(2, server.requestCount)
    }

    @Test fun redirectIsRecordedWithoutFollowingIt() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/unexpected"))
        server.enqueue(MockResponse().setBody("ok"))
        val result = tester.runWithClient(client, server.url("/"), 1024)
        assertNull(result.downloadMbps)
        assertTrue(result.error!!.contains("HTTP_302"))
        assertEquals(2, server.requestCount)
    }

    @Test fun cancellationAbortsPendingNetworkCallAndDoesNotStartUpload() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { tester.runWithClient(client, server.url("/"), 8192) }
        val request = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
        assertNotNull(request)
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancelledStreamingRunCannotEmitProgressAfterJoin() = runBlocking {
        server.enqueue(MockResponse()
            .setBody(Buffer().write(ByteArray(1_048_576)))
            .throttleBody(16_384, 50, TimeUnit.MILLISECONDS))
        val receivedProgress = CompletableDeferred<Unit>()
        val emissions = AtomicInteger()
        val job = launch {
            tester.runWithClient(client, server.url("/"), 1_048_576, onProgress = { progress ->
                emissions.incrementAndGet()
                if (progress.phase == "DOWNLOAD" && progress.bytes > 0) receivedProgress.complete(Unit)
            })
        }
        withTimeout(3000) { receivedProgress.await() }
        withTimeout(1000) { job.cancelAndJoin() }
        val countAtJoin = emissions.get()
        delay(250)
        assertEquals(countAtJoin, emissions.get())
        assertEquals(1, server.requestCount)
    }
}
