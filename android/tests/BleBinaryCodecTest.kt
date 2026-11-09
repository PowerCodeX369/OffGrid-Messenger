package com.offgrid.messenger
fun main() {
    for(n in listOf(1,2,19,20,160,180)) {
        val p=ByteArray(n) { ((it*17+7) and 255).toByte() }
        val t=BleBinaryCodec.pack(p,65535)
        check(t.size==n+5 && t[0].toInt() and 255 == 0xB7)
        check((t[3].toInt() and 255)==255 && (t[4].toInt() and 255)==255)
        check(t.copyOfRange(5,t.size).contentEquals(p))
        check(t.size<=185)
    }
    check(runCatching { BleBinaryCodec.pack(ByteArray(181),1) }.isFailure)
    check(runCatching { BleBinaryCodec.pack(ByteArray(0),1) }.isFailure)
    check(runCatching { BleBinaryCodec.pack(byteArrayOf(3),0) }.isFailure)
    check(BleBinaryCodec.queued("BQUEUED|42|123456789|987") == BleBinaryCodec.Queued(42,"123456789","987"))
    for(line in listOf("BQUEUED|0|5|3", "BQUEUED|-1|5|3","BQUEUED|12|no|4","BQUEUED|65536|5|4","BQUEUED|3|4"))
        check(BleBinaryCodec.queued(line)==null) { line }
    println("PASS binary BLE Kotlin: 1..180-byte frames, MTU accounting, correlation response validation")
}
