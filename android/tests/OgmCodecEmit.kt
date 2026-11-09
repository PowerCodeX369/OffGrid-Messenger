package com.offgrid.messenger
fun main(){
 val key=ByteArray(32){it.toByte()};val id=OgmCodec.newId();val text="Kotlin → Web encrypted text سلام";
 val frames=OgmCodec.seal(key,OgmCodec.TEXT,id,text.toByteArray(Charsets.UTF_8));
 println(frames.joinToString(",") { OgmCodec.hex(it) });println(text)
}
