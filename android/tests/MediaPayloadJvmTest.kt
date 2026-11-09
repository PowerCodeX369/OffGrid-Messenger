package com.offgrid.messenger
import java.util.Arrays

fun main() {
    val secret=ByteArray(32){(it*37+19).toByte()}
    val sampleJpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
    val photo=MediaPayload.photoBody("image/jpeg",sampleJpeg,600,440)
    check(photo[0].toInt()==2)
    check(MediaPayload.unpackPhoto(photo,600,440).bytes.contentEquals(sampleJpeg))
    val imageId="1122334455667788"
    val imageFrames=OgmCodec.seal(secret,OgmCodec.IMAGE,imageId,photo,width=600,height=440)
    val imageAssembler=OgmAssembler { secret.clone() }
    val env=imageFrames.mapNotNull { imageAssembler.accept("peer","session",it) }.single()
    check(env.kind==2 && env.id==imageId && env.width==600 && env.height==440)
    check(MediaPayload.unpackPhoto(env.text,env.width,env.height).mime=="image/jpeg")
    check(imageFrames.all { OgmCodec.parseFrame(it)!=null })
    val bulkFrames=imageFrames.map { it.clone().also { b -> "OGB5".toByteArray().copyInto(b,0) } }
    check(bulkFrames.all { OgmCodec.parseFrame(it)?.bulk==true })
    val receiver=OgmAssembler { secret.clone() }
    val bulkDecoded=bulkFrames.mapNotNull { receiver.accept("peer","batch",it) }.single()
    check(bulkDecoded.kind==OgmCodec.IMAGE)
    println("PASS: JPEG image OGM4 encryption and OGC4 reassembly")

    val vbytes=ByteArray(2048){(it*13+7).toByte()}
    val voice=MediaPayload.voiceBody("audio/mp4",vbytes,23019)
    val (w,h)=MediaPayload.durationParts(23019)
    val voiceFrames=OgmCodec.seal(secret,OgmCodec.VOICE,"1234567890ABCDEF",voice,w,h)
    val voiceAssembler=OgmAssembler { secret.clone() }
    val voiceEnv=voiceFrames.mapNotNull { voiceAssembler.accept("peer","session",it) }.single()
    val result=MediaPayload.unpackVoice(voiceEnv.text,MediaPayload.durationFromParts(voiceEnv.width,voiceEnv.height))
    check(result.mime=="audio/mp4" && result.durationMs==23019 && result.bytes.contentEquals(vbytes))
    println("PASS: AAC MP4 audio codec tag, duration, encrypt/reassembly")

    val maxDuration=120000
    val (a,b)=MediaPayload.durationParts(maxDuration)
    check(MediaPayload.durationFromParts(a,b)==maxDuration)
    check(runCatching { MediaPayload.photoBody("image/gif",sampleJpeg,10,10) }.isFailure)
    check(runCatching { MediaPayload.unpackPhoto(byteArrayOf(9,1),100,100) }.isFailure)
    check(runCatching { MediaPayload.unpackVoice(byteArrayOf(2,1),500) }.isFailure)
    check(runCatching { MediaPayload.unpackPhoto(photo,65535,65535) }.isFailure)
    println("PASS: rejects malformed codecs, sizes and decompression dimensions")
    println("MEDIA_VECTOR_IMAGE|" + OgmCodec.hex(imageFrames[0]))
    println("MEDIA_VECTOR_VOICE|" + OgmCodec.hex(voiceFrames[0]))
}
