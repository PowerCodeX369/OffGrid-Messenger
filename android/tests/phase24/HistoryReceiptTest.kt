package com.offgrid.messenger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
fun main() {
    val voice=ChatItem("8ac320d", "Voice note",true,1700000000000,"read",7,ByteArray(50000) { (it%251).toByte() },"audio/ogg",durationMs=8000)
    val text=ChatItem("83d203df", "Hello, private world",false,1700000000100,"delivered")
    val data=HistoryCodec.encode(listOf(voice,text))
    val decoded=HistoryCodec.decode(data)
    check(decoded.size==2 && decoded[0].bytes!!.contentEquals(voice.bytes))
    check(decoded[1].text==text.text && decoded[1].status=="delivered")
    check(ReceiptStates.visual("sent",1)==ReceiptVisual.SENT)
    check(ReceiptStates.visual("delivered",1)==ReceiptVisual.DELIVERED)
    check(ReceiptStates.visual("read",1)==ReceiptVisual.READ)
    check(ReceiptStates.visual("read",7)==ReceiptVisual.READ)
    check(ReceiptStates.visual("played",7)==ReceiptVisual.READ)
    check(ReceiptStates.visual("delivered",7)==ReceiptVisual.DELIVERED)
    // READ and PLAYED are distinct events: the tick is blue on READ; the mic
    // separately changes on PLAYED. Playback never manufactures a READ receipt.
    check(ReceiptStates.advance("read","played")=="played")
    check(ReceiptStates.advance("played","read")=="played")
    check(ReceiptStates.advance("read","sent")=="read")
    check(ReceiptStates.advance("delivered","failed")=="delivered")
    check(ReceiptStates.advance("sending","sent")=="sent")
    val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey()
    val iv=ByteArray(12).also(SecureRandom()::nextBytes)
    val aad="OffGridMessenger/history/2.4".toByteArray()
    val cipher=Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,iv));cipher.updateAAD(aad)
    val ciphertext=cipher.doFinal(data)
    check(!ciphertext.contentEquals(data))
    val decrypt=Cipher.getInstance("AES/GCM/NoPadding")
    decrypt.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,iv));decrypt.updateAAD(aad)
    check(HistoryCodec.decode(decrypt.doFinal(ciphertext))[0].bytes!!.contentEquals(voice.bytes))
    val modified=ciphertext.copyOf().also {it[20]=(it[20].toInt() xor 1).toByte()}
    val tampered=Cipher.getInstance("AES/GCM/NoPadding")
    tampered.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,iv));tampered.updateAAD(aad)
    check(runCatching{tampered.doFinal(modified)}.isFailure)
    val bigAudio=voice.copy(id="larger",bytes=ByteArray(95000){(it%101).toByte()})
    check(HistoryCodec.decode(HistoryCodec.encode(listOf(bigAudio)))[0].bytes!!.size==95000)
    val lots=(1..600).map{ChatItem("id$it","x",false,it.toLong())}
    check(HistoryCodec.decode(HistoryCodec.encode(lots)).size==400)
    println("PASS Phase 2.4 history codec, retention, AES-GCM tamper rejection and truthful message/voice receipts")
}
