package dev.proprio.mobile.data

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dev.proprio.backend.SecretStore
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Refresh token vault: each value is AES-256-GCM encrypted by a Tink keyset that is itself wrapped by a key in the
 * Android Keystore (the key never leaves secure hardware). Tink is used instead of `androidx.security:security-crypto`
 * (EncryptedSharedPreferences), which Google deprecated. The entry name is bound as associated data, so a value cannot
 * be swapped between entries. The app disables backup (`allowBackup=false`), so ciphertext is never restored onto a
 * device that lacks the Keystore key; if decryption fails anyway, the entry reads as absent and the user signs in again.
 */
class TinkSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("proprio_secrets", Context.MODE_PRIVATE)

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "proprio_keyset", "proprio_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://proprio_secrets_master")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            String(aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), key.toByteArray()), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    override fun put(key: String, value: String) {
        val sealed = Base64.encodeToString(aead.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray()), Base64.NO_WRAP)
        // commit(), not apply(): a rotated refresh token must be on disk before the old one is forgotten.
        prefs.edit().putString(key, sealed).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }
}
