package com.offgrid.messenger

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun main() {
    val ids=linkedSetOf("0123456789abcdef", "eeaa11bb22cc33dd")
    check(HiddenMessageIds.decode(HiddenMessageIds.encode(ids))==ids)
    check(HiddenMessageIds.decode(HiddenMessageIds.encode(emptySet())).isEmpty())
    check(HiddenMessageIds.decode(HiddenMessageIds.encode(setOf("bad"))).isEmpty())
    runCatching { HiddenMessageIds.decode(byteArrayOf(1,2,3)) }.onSuccess { error("Must reject corrupt data") }
    // The ledger is encrypted with a device-held AES-GCM key in production.
    val key=SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes),"AES")
    val cipher=Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE,key)
    cipher.updateAAD("OffGridMessenger/hidden-ids/1".toByteArray())
    val sealed=cipher.doFinal(HiddenMessageIds.encode(ids))
    val other=Cipher.getInstance("AES/GCM/NoPadding")
    other.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,cipher.iv))
    other.updateAAD("OffGridMessenger/hidden-ids/1".toByteArray())
    check(HiddenMessageIds.decode(other.doFinal(sealed))==ids)
    sealed[sealed.lastIndex]=(sealed.last().toInt() xor 1).toByte()
    val tampered=Cipher.getInstance("AES/GCM/NoPadding")
    tampered.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,cipher.iv))
    tampered.updateAAD("OffGridMessenger/hidden-ids/1".toByteArray())
    check(runCatching {tampered.doFinal(sealed)}.isFailure)

    check(VoiceGestureRules.classify(0f,-75f,false,false)==VoiceGestureRules.Action.LOCK)
    check(VoiceGestureRules.classify(-85f,0f,false,false)==VoiceGestureRules.Action.CANCEL)
    check(VoiceGestureRules.classify(-15f,-25f,false,false)==VoiceGestureRules.Action.NONE)
    check(VoiceGestureRules.classify(-100f,-90f,true,false)==VoiceGestureRules.Action.NONE)
    check(VoiceGestureRules.classify(0f,-100f,false,true)==VoiceGestureRules.Action.NONE)
    println("PASS encrypted deletion ledger + tamper rejection + gesture decision cases")
}
