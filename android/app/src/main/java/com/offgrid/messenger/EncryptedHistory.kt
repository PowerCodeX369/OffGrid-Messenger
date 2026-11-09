package com.offgrid.messenger

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-only AES-256-GCM history with crash-safe atomic writes.
 * Uses a dedicated Android Keystore key, not the ESP32 pairing key.
 * No backup, no cleartext history file; corrupt/tampered data fails authenticated decryption.
 */
class EncryptedHistory(context:Context) {
    companion object {private val IO_LOCK=Any()}
    private val file=AtomicFile(File(context.applicationContext.filesDir,"offgrid-history-v1.enc"))
    private val alias="offgrid_history_aes_24"
    private val aad="OffGridMessenger/history/2.4".toByteArray(Charsets.UTF_8)
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun load():List<ChatItem> = synchronized(IO_LOCK) {
        if(!file.baseFile.exists()) return@synchronized emptyList()
        val stored=file.openRead().use{it.readBytes()}
        require(stored.size in 33..12_000_000 && stored.copyOfRange(0,4).contentEquals("OGH4".toByteArray()))
            {"Invalid encrypted history file"}
        val iv=stored.copyOfRange(4,16)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv));cipher.updateAAD(aad)
        HistoryCodec.decode(cipher.doFinal(stored.copyOfRange(16,stored.size)))
    }
    fun save(snapshot:List<ChatItem>) = synchronized(IO_LOCK) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore keys require the provider to choose the encryption IV.
        // Supplying a caller-generated GCM IV would be rejected on real phones.
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val iv=cipher.iv
        require(iv.size==12) {"Unexpected Keystore GCM nonce length"}
        cipher.updateAAD(aad)
        val stored="OGH4".toByteArray(Charsets.UTF_8)+iv+cipher.doFinal(HistoryCodec.encode(snapshot))
        val output=file.startWrite()
        try {output.write(stored);file.finishWrite(output)}
        catch(error:Exception){file.failWrite(output);throw error}
    }
    fun clear()=synchronized(IO_LOCK){file.delete()}
}
