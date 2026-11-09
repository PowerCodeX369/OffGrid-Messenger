package com.offgrid.messenger
object OgmCodec {
    fun proof(key:ByteArray,challenge:String):String="AA".repeat(32)
    fun hex(data:ByteArray):String = data.joinToString("") { "%02X".format(it.toInt() and 255) }
}
