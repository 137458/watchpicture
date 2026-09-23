package com.watchpicture.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

val Context.passwordBookDataStore: DataStore<Preferences> by preferencesDataStore(name = "watchpicture_password_book")

/**
 * DataStore-backed repository managing saved user passwords for quick unlocking of archives.
 *
 * Passwords are stored encrypted with an Android Keystore-backed AES-GCM key so that
 * plaintext credentials are never written to disk (otherwise extractable by root/backup).
 * A compact "v1" envelope (base64(iv || ciphertext)) is stored; legacy plaintext JSON
 * written by earlier builds is transparently read and migrated on the next mutation.
 */
class PasswordBookRepository(private val context: Context) {

    companion object {
        private val KEY_SAVED_PASSWORDS = stringPreferencesKey("saved_passwords_json")
        private val KEY_AUTO_SAVE_PASSWORD = booleanPreferencesKey("auto_save_password")
        private val json = Json { ignoreUnknownKeys = true }

        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "watchpicture_password_book_aes_key"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_LENGTH = 12
        /** New payload prefix marker; absence indicates legacy plaintext JSON. */
        private const val ENVELOPE_PREFIX = "v1:"
    }

    /**
     * Lazily retrieves (or creates) the Keystore-backed AES-GCM symmetric key.
     */
    private fun getOrCreateKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts a string, returning the "v1:<base64(iv||ciphertext)>" envelope.
     */
    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val payload = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ciphertext, 0, payload, iv.size, ciphertext.size)
        return ENVELOPE_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /**
     * Decrypts an envelope produced by [encrypt]. Returns null on any failure
     * (corrupt data, key invalidation, etc.).
     */
    private fun decrypt(envelope: String): String? {
        if (!envelope.startsWith(ENVELOPE_PREFIX)) return null
        return try {
            val payload = Base64.decode(envelope.removePrefix(ENVELOPE_PREFIX), Base64.NO_WRAP)
            if (payload.size < GCM_IV_LENGTH) return null
            val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePasswordList(raw: String): List<String> {
        // Prefer the encrypted envelope.
        decrypt(raw)?.let { decrypted ->
            return runCatching { json.decodeFromString<List<String>>(decrypted) }
                .getOrDefault(emptyList())
        }
        // Fall back to reading legacy plaintext JSON (as a decryption-absent case)
        // so pre-encryption data remains visible until the next write migrates it.
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun encodePasswordList(list: List<String>): String = encrypt(json.encodeToString(list))

    val passwordsFlow: Flow<List<String>> = context.passwordBookDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_SAVED_PASSWORDS]?.let { decodePasswordList(it) } ?: emptyList()
        }

    val autoSavePasswordFlow: Flow<Boolean> = context.passwordBookDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[KEY_AUTO_SAVE_PASSWORD] ?: true
        }

    suspend fun addPassword(password: String) {
        val trimmed = password.trim()
        if (trimmed.isEmpty()) return

        context.passwordBookDataStore.edit { preferences ->
            val raw = preferences[KEY_SAVED_PASSWORDS]
            val currentList = raw?.let { decodePasswordList(it) }?.toMutableList() ?: mutableListOf()

            // Remove if existing, insert at the front (most recent)
            currentList.remove(trimmed)
            currentList.add(0, trimmed)

            preferences[KEY_SAVED_PASSWORDS] = encodePasswordList(currentList)
        }
    }

    suspend fun removePassword(password: String) {
        context.passwordBookDataStore.edit { preferences ->
            val raw = preferences[KEY_SAVED_PASSWORDS]
            val currentList = raw?.let { decodePasswordList(it) }?.toMutableList() ?: mutableListOf()

            currentList.remove(password)
            preferences[KEY_SAVED_PASSWORDS] = encodePasswordList(currentList)
        }
    }

    suspend fun clearAll() {
        context.passwordBookDataStore.edit { preferences ->
            preferences[KEY_SAVED_PASSWORDS] = encodePasswordList(emptyList())
        }
    }

    suspend fun setAutoSavePassword(enabled: Boolean) {
        context.passwordBookDataStore.edit { preferences ->
            preferences[KEY_AUTO_SAVE_PASSWORD] = enabled
        }
    }
}