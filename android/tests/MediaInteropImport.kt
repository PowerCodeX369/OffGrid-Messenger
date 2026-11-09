package com.offgrid.messenger
import java.io.File
fun main(args:Array<String>) {
 val key=ByteArray(32){it.toByte()}
 for(line in File(args.single()).readLines()) {
  val parts=line.split('|');check(parts.size==3)
  val kind=parts[0];val frames=parts[1].split(',').map {OgmCodec.unhex(it)}
  val assembler=OgmAssembler {key.clone()}
  val env=frames.mapNotNull {assembler.accept("web","1",it)}.single()
  when(kind) {
   "JPEG" -> {val image=MediaPayload.unpackPhoto(env.text,env.width,env.height)
      check(env.kind==OgmCodec.IMAGE && image.mime=="image/jpeg" && image.bytes.size==4096 && image.width==780 && image.height==520)
      check(frames.all {OgmCodec.parseFrame(it)?.bulk==true}) }
   "VOICE" -> {val voice=MediaPayload.unpackVoice(env.text,MediaPayload.durationFromParts(env.width,env.height))
      check(env.kind==OgmCodec.VOICE && voice.mime=="audio/webm" && voice.bytes.size==2018 && voice.durationMs==119000)
      check(frames.all {OgmCodec.parseFrame(it)?.bulk==true}) }
   else -> error("Unsupported test")
  }
  println("PASS Kotlin decrypts WebCrypto $kind OGB5 selective-ACK batch")
 }
}
