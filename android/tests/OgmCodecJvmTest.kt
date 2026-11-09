package com.offgrid.messenger
import java.util.Base64
import javax.crypto.AEADBadTagException

fun main(args:Array<String>){
 val key=ByteArray(32){it.toByte()}
 val text="OffGrid Android ↔ Chromium OGM4 test — سلام"
 val id=OgmCodec.newId()
 val frames=OgmCodec.seal(key,OgmCodec.TEXT,id,text.toByteArray(Charsets.UTF_8))
 val assembler=OgmAssembler { key.clone() }
 var got:OgmCodec.Envelope?=null
 for (frame in frames.reversed()) got=assembler.accept("REMOTE","77",frame) ?: got
 check(got!=null && got.kind==OgmCodec.TEXT && got.id==id && got.text.toString(Charsets.UTF_8)==text)
 check(OgmCodec.key(Base64.getUrlEncoder().withoutPadding().encodeToString(key)).contentEquals(key))
 check(OgmCodec.proof(key,"000102030405060708090A0B0C0D0E0F").length==64)
 val bad=frames.map{it.clone()}.toMutableList();bad.last()[bad.last().lastIndex]=(bad.last().last().toInt() xor 1).toByte()
 var rejected=false
 try{val inspect=OgmAssembler {key.clone()};for(f in bad)inspect.accept("REMOTE","8",f)}catch(_:Exception){rejected=true}
 check(rejected)
 if(args.isNotEmpty()){
  val input=java.io.File(args[0]).readLines()
  val transfer=input[0]
  val hexPayload=input[1]
  val expected=input[2]
  val env=OgmCodec.decrypt(key,transfer,OgmCodec.unhex(hexPayload))
  check(env.kind==OgmCodec.TEXT && env.text.toString(Charsets.UTF_8)==expected)
 }
 println("PASS native Kotlin OGM4 AES-GCM fragment and tamper checks")
}
