package com.offgrid.messenger

/** Phase 2.3 authenticated BLE transport envelope. The bytes following the
 * header are the unchanged OGC4/OGB5 *encrypted* radio fragments.
 * Correlation token is per GATT WRITE, never a cryptographic nonce. */
object BleBinaryCodec {
    const val VERSION = 1
    const val HEADER_SIZE = 5
    const val MAX_FRAME = 180
    const val MIN_MTU = HEADER_SIZE + MAX_FRAME + 3
    fun pack(frame: ByteArray, token: Int): ByteArray {
        require(frame.isNotEmpty() && frame.size <= MAX_FRAME)
        require(token in 1..65535)
        return byteArrayOf(0xB7.toByte(), 0x47, VERSION.toByte(),
            (token and 255).toByte(), (token ushr 8).toByte()) + frame
    }
    data class Queued(val token: Int, val session: String, val packetId: String)
    fun queued(line: String): Queued? {
        val parts=line.split('|')
        if(parts.size!=4 || parts[0]!="BQUEUED")return null
        val token=parts[1].toIntOrNull() ?: return null
        if(token !in 1..65535 || parts[2].toULongOrNull()==null || parts[3].toULongOrNull()==null)return null
        return Queued(token,parts[2],parts[3])
    }
}
