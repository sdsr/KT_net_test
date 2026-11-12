package dev.netcheck.app.network

import android.net.Network
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.netcheck.app.data.PingAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.net.InetAddress
import java.security.SecureRandom

class PingEngine {
    suspend fun run(network: Network): List<PingAttempt> = coroutineScope {
        ADDRESSES.flatMap { address ->
            (1..3).map { sequence ->
                async {
                    delay((sequence - 1) * 1_000L)
                    probe(network, address, sequence)
                }
            }
        }.awaitAll()
    }

    private suspend fun probe(network: Network, address: String, sequence: Int): PingAttempt =
        withContext(Dispatchers.IO) {
            var socket: FileDescriptor? = null
            try {
                currentCoroutineContext().ensureActive()
                // AF_INET + ping datagram socket performs actual ICMP, without a subprocess or root.
                // SOCK_NONBLOCK is public since API 29; fcntlInt would require API 30.
                val fd = Os.socket(OsConstants.AF_INET,
                    OsConstants.SOCK_DGRAM or OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC,
                    OsConstants.IPPROTO_ICMP)
                socket = fd
                network.bindSocket(fd)
                val destination = InetAddress.getByAddress(address.split('.').map { it.toInt().toByte() }.toByteArray())
                // Connecting the ICMP socket filters packets from other source addresses.
                Os.connect(fd, destination, 0)
                val nonce = ByteArray(56).also { random.nextBytes(it) }
                val request = IcmpPacket.request(sequence, nonce)
                val started = System.nanoTime()
                val written = Os.write(fd, request, 0, request.size)
                check(written == request.size) { "Incomplete ICMP write" }
                val buffer = ByteArray(512)
                val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
                while (System.nanoTime() - started < TIMEOUT_NS) {
                    currentCoroutineContext().ensureActive()
                    // Short, nonblocking polls ensure cancellation closes the descriptor within ~100 ms.
                    val remainingMs = ((TIMEOUT_NS - (System.nanoTime() - started)) / 1_000_000L).coerceAtLeast(1)
                    val ready = Os.poll(arrayOf(poll), minOf(100, remainingMs.toInt()))
                    if (ready == 0) continue
                    if (poll.revents.toInt() and OsConstants.POLLNVAL != 0) {
                        return@withContext PingAttempt(address, sequence, status = "NETWORK_ERROR", error = "ICMP socket unavailable")
                    }
                    try {
                        val received = Os.read(fd, buffer, 0, buffer.size)
                        if (IcmpPacket.isReply(buffer, received, sequence, nonce)) {
                            return@withContext PingAttempt(address, sequence, (System.nanoTime() - started) / 1_000_000.0, "SUCCESS")
                        }
                    } catch (error: ErrnoException) {
                        if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                    }
                }
                PingAttempt(address, sequence, status = "TIMEOUT")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                PingAttempt(address, sequence, status = "UNSUPPORTED", error = error.message?.take(160))
            } catch (error: ErrnoException) {
                val status = when (error.errno) {
                    OsConstants.EACCES, OsConstants.EPERM, OsConstants.EPROTONOSUPPORT,
                    OsConstants.EAFNOSUPPORT, OsConstants.ENOPROTOOPT, OsConstants.EOPNOTSUPP,
                    OsConstants.ENOSYS -> "UNSUPPORTED"
                    OsConstants.ENETUNREACH, OsConstants.ENETDOWN, OsConstants.EHOSTUNREACH,
                    OsConstants.ECONNREFUSED -> "NETWORK_ERROR"
                    else -> "IO_ERROR"
                }
                PingAttempt(address, sequence, status = status, error = "errno=${error.errno}: ${error.message?.take(120)}")
            } catch (error: Exception) {
                PingAttempt(address, sequence, status = "IO_ERROR", error = error.message?.take(160))
            } finally {
                socket?.let { try { Os.close(it) } catch (_: Exception) { } }
            }
        }

    private companion object {
        val ADDRESSES = listOf("8.8.8.8", "1.1.1.1")
        const val TIMEOUT_NS = 2_000_000_000L
        val random = SecureRandom()
    }
}
