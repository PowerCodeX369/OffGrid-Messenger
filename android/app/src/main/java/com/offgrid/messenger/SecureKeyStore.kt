package com.offgrid.messenger

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore-wrapped shared OffGrid key; never store raw AES bytes in preferences. */
class SecureKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("offgrid_android_v2", Context.MODE_PRIVATE)
    private val alias = "offgrid_pairing_wrap_v2"

    private fun wrapKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun savePairingKey(raw: ByteArray) {
        require(raw.size == 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val encrypted = cipher.iv + cipher.doFinal(raw)
        prefs.edit().putString("wrapped_key", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun readPairingKey(): ByteArray? {
        val stored = prefs.getString("wrapped_key", null) ?: return null
        return try {
            val sealed = Base64.decode(stored, Base64.DEFAULT)
            if (sealed.size < 29) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            cipher.doFinal(sealed.copyOfRange(12, sealed.size)).takeIf { it.size == 32 }
        } catch (_: Exception) { null }
    }

    fun saveNodeAddress(mac: String) { prefs.edit().putString("ble_node", mac).apply() }
    fun nodeAddress(): String? = prefs.getString("ble_node", null)
    fun clearNodeAddress() { prefs.edit().remove("ble_node").apply() }
    fun clear() { prefs.edit().clear().apply() }
}
