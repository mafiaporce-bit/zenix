package com.jpsums.zenix

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object SecretStore {
    private const val alias = "zenix_credentials_v1"
    private val allowed = setOf("apikey", "orkey", "dgkey", "elkey")
    @Synchronized private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }
    fun put(c: Context, name: String, value: String): Boolean {
        if (name !in allowed || value.length > 4096) return false
        return try {
            val p = c.getSharedPreferences("zenix_secrets", Context.MODE_PRIVATE)
            if (value.isEmpty()) p.edit().remove(name).commit() else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray(Charsets.UTF_8)) }
                val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                p.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
            }
        } catch (_: Exception) { false }
    }
    fun get(c: Context, name: String): String {
        if (name !in allowed) return ""
        return try {
            val raw = c.getSharedPreferences("zenix_secrets", Context.MODE_PRIVATE).getString(name, null) ?: return ""
            val blob = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0,12))); updateAAD(name.toByteArray(Charsets.UTF_8)) }
            String(cipher.doFinal(blob.copyOfRange(12,blob.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }
}
