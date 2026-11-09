package com.offgrid.messenger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** OGM4 envelope and 160-byte OGC4 fragment format, compatible with web/protocol.js. */
object OgmCodec {
 private val random=SecureRandom()
 const val TEXT=1;const val IMAGE=2;const val DELIVERED=3;const val READ=4;const val VOICE=7;const val PLAYED=8
 data class Frame(val id:String,val index:Int,val count:Int,val total:Int,val bytes:ByteArray,val bulk:Boolean=false)
 data class Envelope(val kind:Int,val id:String,val text:ByteArray,val timestamp:Long,val width:Int=0,val height:Int=0)
 fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02X".format(it.toInt() and 255)}
 fun unhex(value:String):ByteArray {
  require(value.length%2==0&&value.matches(Regex("[0-9a-fA-F]*")))
  return ByteArray(value.length/2){value.substring(it*2,it*2+2).toInt(16).toByte()}
 }
 fun key(value:String):ByteArray {
  require(value.matches(Regex("[A-Za-z0-9_-]{43}")))
  return Base64.getUrlDecoder().decode(value).also{require(it.size==32)}
 }
 fun newId()=hex(ByteArray(8).also(random::nextBytes))
 fun proof(key:ByteArray,hexChallenge:String):String {
  val nonce=unhex(hexChallenge);require(key.size==32&&nonce.size==16)
  val hmac=Mac.getInstance("HmacSHA256");hmac.init(SecretKeySpec(key,"HmacSHA256"))
  return hex(hmac.doFinal("OffGridBLE2".toByteArray()+nonce))
 }
 fun seal(key:ByteArray,kind:Int,messageId:String,body:ByteArray,width:Int=0,height:Int=0):List<ByteArray> {
  require(width in 0..65535 && height in 0..65535)
  require(key.size==32&&unhex(messageId).size==8&&body.size<=385*1024)
  val plain=ByteBuffer.allocate(24+body.size).order(ByteOrder.BIG_ENDIAN)
  plain.put(79.toByte());plain.put(71.toByte());plain.put(1.toByte());plain.put(kind.toByte());plain.put(unhex(messageId))
  plain.putInt((System.currentTimeMillis()/1000).toInt());plain.putShort(width.toShort());plain.putShort(height.toShort());plain.putInt(body.size);plain.put(body)
  val id=ByteArray(8).also(random::nextBytes);val iv=ByteArray(12).also(random::nextBytes)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));cipher.updateAAD("OGM4".toByteArray()+id)
  val sealed=iv+cipher.doFinal(plain.array());val count=(sealed.size+159)/160
  require(count in 1..65535)
  return List(count){i->
   val part=sealed.copyOfRange(i*160,minOf(sealed.size,(i+1)*160))
   ByteBuffer.allocate(20+part.size).order(ByteOrder.BIG_ENDIAN).apply {
    put("OGC4".toByteArray());put(id);putShort(i.toShort());putShort(count.toShort());putInt(sealed.size);put(part)
   }.array()
  }
 }
 fun parseFrame(bytes:ByteArray):Frame? {
  if(bytes.size !in 20..180) return null
  val magic=bytes.copyOfRange(0,4).toString(Charsets.US_ASCII)
  // Browser 1.6.1 sends bulk media using OGB5 with selective ACK. We can
  // safely reply with one APPACK per received radio packet (no firmware change).
  if(magic!="OGC4" && magic!="OGB5")return null
  val b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);b.position(4)
  val id=ByteArray(8);b.get(id);val index=b.short.toInt() and 65535;val count=b.short.toInt() and 65535;val total=b.int
  if(count<1||index>=count||total<28||total>395000||count!=(total+159)/160)return null
  if(bytes.size!=20+(if(index==count-1)total-index*160 else 160))return null
  return Frame(hex(id),index,count,total,bytes.copyOfRange(20,bytes.size),magic=="OGB5")
 }
 fun decrypt(key:ByteArray,transfer:String,encrypted:ByteArray):Envelope {
  require(key.size==32&&encrypted.size>=28)
  val c=Cipher.getInstance("AES/GCM/NoPadding")
  c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,encrypted.copyOfRange(0,12)))
  c.updateAAD("OGM4".toByteArray()+unhex(transfer))
  val plain=c.doFinal(encrypted.copyOfRange(12,encrypted.size));require(plain.size>=24)
  val b=ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN)
  require(b.get().toInt()==79&&b.get().toInt()==71&&b.get().toInt()==1)
  val kind=b.get().toInt() and 255;val id=ByteArray(8);b.get(id)
  val ts=(b.int.toLong() and 0xffffffffL)*1000;val width=b.short.toInt() and 65535;val height=b.short.toInt() and 65535;val size=b.int
  require(size==plain.size-24)
  require(kind in 1..8)
  return Envelope(kind,hex(id),plain.copyOfRange(24,plain.size),ts,width,height)
 }
}
class OgmAssembler(private val key:()->ByteArray?){
 private data class Batch(val count:Int,val total:Int,val parts:Array<ByteArray?>,var updated:Long)
 private val pending=linkedMapOf<String,Batch>()
 private val complete=linkedSetOf<String>()
 fun accept(peer:String,session:String,bytes:ByteArray):OgmCodec.Envelope?{
  val f=OgmCodec.parseFrame(bytes)?:return null
  val label="$peer:$session:${f.id}";if(label in complete)return null
  pending.entries.removeAll{System.currentTimeMillis()-it.value.updated>120000}
  if(pending.size>5&&label !in pending)pending.remove(pending.keys.first())
  val b=pending.getOrPut(label){Batch(f.count,f.total,arrayOfNulls(f.count),System.currentTimeMillis())}
  if(b.count!=f.count||b.total!=f.total)return null
  b.parts[f.index]=f.bytes;b.updated=System.currentTimeMillis()
  if(b.parts.any{it==null})return null
  pending.remove(label)
  val joined=ByteArray(b.total);var at=0
  for(part in b.parts){val p=part!!;if(at+p.size>joined.size)return null;p.copyInto(joined,at);at+=p.size}
  if(at!=joined.size)return null
  val secret=key()?:return null
  return try{
   OgmCodec.decrypt(secret,f.id,joined).also{complete.add(label);while(complete.size>256)complete.remove(complete.first())}
  }finally{secret.fill(0)}
 }
}
