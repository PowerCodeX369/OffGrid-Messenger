package com.offgrid.messenger

fun main(args:Array<String>) {
    val key=ByteArray(32){it.toByte()}
    val target="0102030405060708"
    when(args[0]) {
        "emit" -> {
            for((kind,body) in listOf(OgmCodec.EDIT to MessageMutation.editPayload(target,"Cross-device edit ✔"),
                    OgmCodec.DELETE to MessageMutation.deletePayload(target))) {
                val frames=OgmCodec.seal(key,kind,OgmCodec.newId(),body)
                println("$kind:"+frames.joinToString(","){OgmCodec.hex(it)})
            }
        }
        "verify" -> {
            val transfer=args[1];val bytes=OgmCodec.unhex(args[2]);val kind=args[3].toInt()
            val parsed=OgmCodec.decrypt(key,transfer,bytes)
            check(parsed.kind==kind && MessageMutation.target(parsed.text)==target)
            println("PASS Kotlin decrypted web kind=$kind")
        }
        else -> error("Unknown mode")
    }
}
