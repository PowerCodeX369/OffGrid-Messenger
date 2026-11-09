package com.offgrid.messenger

fun main(){
    val key=ByteArray(32){it.toByte()}
    val imageBytes=ByteArray(2048){(it*7).toByte()}
    val imageFrames=OgmCodec.seal(key,OgmCodec.IMAGE,"1122334455667788",MediaPayload.photoBody("image/jpeg",imageBytes,1000,800),1000,800)
    println("JPEG:"+imageFrames.joinToString(","){OgmCodec.hex(it)})
    val voiceBytes=ByteArray(4024){(it*11+23).toByte()}
    val ms=78002;val (w,h)=MediaPayload.durationParts(ms)
    val voiceFrames=OgmCodec.seal(key,OgmCodec.VOICE,"AABBCCDDEEFF0099",MediaPayload.voiceBody("audio/mp4",voiceBytes,ms),w,h)
    println("VOICE:"+voiceFrames.joinToString(","){OgmCodec.hex(it)})
}
