package dev.netcheck.app.network

import org.junit.Assert.*
import org.junit.Test

class IcmpPacketTest {
    @Test fun checksumMatchesKnownVectorAndOddLength() {
        assertEquals(0x220d, IcmpPacket.checksum(byteArrayOf(0, 1, 0xf2.toByte(), 3, 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte())))
        assertEquals(0xfbfd, IcmpPacket.checksum(byteArrayOf(1, 2, 3)))
    }

    @Test fun echoRequestHasCorrectTypeSequencePayloadAndChecksum() {
        val payload = ByteArray(56) { it.toByte() }
        val packet = IcmpPacket.request(513, payload)
        assertEquals(64, packet.size)
        assertEquals(8, packet[0].toInt())
        assertEquals(2, packet[6].toInt())
        assertEquals(1, packet[7].toInt())
        assertArrayEquals(payload, packet.copyOfRange(8, packet.size))
        assertEquals(0, IcmpPacket.checksum(packet))
        assertFalse(IcmpPacket.isReply(packet, packet.size, 513, payload))
    }

    @Test fun replyAcceptsKernelAssignedIdentifierButRejectsWrongNonceOrSequence() {
        val payload = ByteArray(56) { (it + 31).toByte() }
        val reply = reply(3, payload)
        assertTrue(IcmpPacket.isReply(reply, reply.size, 3, payload))
        assertFalse(IcmpPacket.isReply(reply, reply.size, 2, payload))
        assertFalse(IcmpPacket.isReply(reply, reply.size, 3, payload.copyOf().apply { this[20] = 0 }))
        assertFalse(IcmpPacket.isReply(reply, reply.size - 1, 3, payload))
        assertFalse(IcmpPacket.isReply(reply.copyOf().apply { this[8] = (this[8].toInt() xor 1).toByte() }, reply.size, 3, payload))
    }

    @Test fun acceptsOptionalIpv4HeaderAndRejectsMalformedHeader() {
        val payload = byteArrayOf(1, 2, 3)
        val echo = reply(1, payload)
        val packet = ByteArray(20 + echo.size).apply {
            this[0] = 0x45; this[9] = 1; echo.copyInto(this, 20)
        }
        assertTrue(IcmpPacket.isReply(packet, packet.size, 1, payload))
        packet[0] = 0x41
        assertFalse(IcmpPacket.isReply(packet, packet.size, 1, payload))
    }

    private fun reply(sequence: Int, payload: ByteArray) = IcmpPacket.request(sequence, payload, 0x4321).apply {
        this[0] = 0; this[2] = 0; this[3] = 0
        val checksum = IcmpPacket.checksum(this)
        this[2] = (checksum ushr 8).toByte(); this[3] = checksum.toByte()
    }
}
