package ru.dmitriy.workouts.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** The personal provider key is entered on the phone, never bundled or exported.
 * The encryption key stays in Android Keystore. Android backup is disabled. */
@Singleton class SessionVault @Inject constructor(@ApplicationContext context:Context) {
    private val prefs=context.getSharedPreferences("personal_ai_key",Context.MODE_PRIVATE)
    private val alias="workouts.personal-ai.v1"
    init { context.getSharedPreferences("device_session",Context.MODE_PRIVATE).edit().clear().apply() }
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(apiKey:String) {
        require(apiKey.matches(Regex("[A-Za-z0-9._-]{16,256}"))) { "Проверьте ключ DeepSeek" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val encrypted=cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .putString("data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit()) { "Не удалось сохранить ключ" }
    }
    fun read():String? = runCatching {
        val iv=prefs.getString("iv",null) ?: return null
        val data=prefs.getString("data",null) ?: return null
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP))) }
        cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)).decodeToString()
    }.getOrNull()
    fun clear() { check(prefs.edit().clear().commit()) { "Не удалось удалить ключ" } }
}
