package dev.netcheck.app.network

/** ICMPv4 echo codec. Datagram ping sockets may replace the identifier, so match the nonce too. */
internal object IcmpPacket {
    const val HEADER_BYTES = 8

    fun request(sequence: Int, payload: ByteArray, identifier: Int = 0): ByteArray {
        require(sequence in 0..65535)
        val packet = ByteArray(HEADER_BYTES + payload.size)
        packet[0] = 8
        put16(packet, 4, identifier)
        put16(packet, 6, sequence)
        payload.copyInto(packet, HEADER_BYTES)
        put16(packet, 2, checksum(packet))
        return packet
    }

    fun isReply(packet: ByteArray, length: Int, sequence: Int, payload: ByteArray): Boolean {
        if (length !in HEADER_BYTES..packet.size) return false
        // SOCK_DGRAM returns ICMP directly. Accommodate a complete IPv4 packet for codec tests.
        val offset = if ((packet[0].toInt() and 0xf0) == 0x40) {
            val headerSize = (packet[0].toInt() and 15) * 4
            if (headerSize < 20 || length < headerSize + HEADER_BYTES || packet[9].toInt() != 1) return false
            headerSize
        } else 0
        if (length - offset != HEADER_BYTES + payload.size) return false
        if (packet[offset].toInt() != 0 || packet[offset + 1].toInt() != 0) return false
        if (get16(packet, offset + 6) != sequence) return false
        if (checksum(packet, offset, length - offset) != 0) return false
        return payload.indices.all { packet[offset + HEADER_BYTES + it] == payload[it] }
    }

    fun checksum(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += get16(bytes, index)
            index += 2
        }
        if (index < end) sum += (bytes[index].toInt() and 255) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 65535) + (sum ushr 16)
        return sum.inv().toInt() and 65535
    }

    private fun get16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
