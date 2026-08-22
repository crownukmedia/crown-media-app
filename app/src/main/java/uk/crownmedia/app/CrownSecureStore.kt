package uk.crownmedia.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/** Small preference surface with encrypted values on every supported Android version. */
internal interface CrownSecureStore {
    fun getString(key: String, fallback: String?): String?
    fun putString(key: String, value: String?)
    fun getStringSet(key: String): Set<String>
    fun putStringSet(key: String, value: Set<String>)

    companion object {
        fun create(context: Context): CrownSecureStore =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ModernStore(context) else LegacyStore(context)
    }
}

@RequiresApi(Build.VERSION_CODES.M)
private class ModernStore(context: Context) : CrownSecureStore {
    private val preferences = EncryptedSharedPreferences.create(
        "crown_secure_store",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun getString(key: String, fallback: String?) = preferences.getString(key, fallback)
    override fun putString(key: String, value: String?) { preferences.edit().putString(key, value).apply() }
    override fun getStringSet(key: String) = preferences.getStringSet(key, emptySet()).orEmpty()
    override fun putStringSet(key: String, value: Set<String>) { preferences.edit().putStringSet(key, value).apply() }
}

@SuppressLint("ObsoleteSdkInt")
private class LegacyStore(private val context: Context) : CrownSecureStore {
    private val preferences: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val key: SecretKeySpec by lazy { SecretKeySpec(loadOrCreateKey(), "AES") }

    override fun getString(key: String, fallback: String?): String? {
        val encoded = preferences.getString(key, null) ?: return fallback
        return runCatching { decrypt(encoded) }.getOrElse { fallback }
    }

    override fun putString(key: String, value: String?) {
        if (value == null) preferences.edit().remove(key).apply()
        else preferences.edit().putString(key, encrypt(value)).apply()
    }

    override fun getStringSet(key: String): Set<String> {
        val raw = getString(key, null) ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
        }.getOrDefault(emptySet())
    }

    override fun putStringSet(key: String, value: Set<String>) {
        putString(key, JSONArray(value.toList()).toString())
    }

    private fun encrypt(value: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12) { "Invalid encrypted value" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private fun loadOrCreateKey(): ByteArray {
        ensureRsaKeyPair()
        val wrapped = preferences.getString(WRAPPED_KEY, null)
        if (wrapped != null) return rsa(Cipher.DECRYPT_MODE).doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
        val generated = ByteArray(32).also(SecureRandom()::nextBytes)
        val encrypted = rsa(Cipher.ENCRYPT_MODE).doFinal(generated)
        preferences.edit().putString(WRAPPED_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        return generated
    }

    private fun ensureRsaKeyPair() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) return
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
        val spec = KeyPairGeneratorSpec.Builder(context)
            .setAlias(KEY_ALIAS)
            .setSubject(X500Principal("CN=Crown Media local credentials"))
            .setSerialNumber(BigInteger.ONE)
            .setStartDate(start.time)
            .setEndDate(end.time)
            .build()
        KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec); generateKeyPair() }
    }

    private fun rsa(mode: Int): Cipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        val key = if (mode == Cipher.ENCRYPT_MODE) store.getCertificate(KEY_ALIAS).publicKey else store.getKey(KEY_ALIAS, null)
        cipher.init(mode, key)
        return cipher
    }

    private companion object {
        const val FILE_NAME = "crown_secure_store_legacy"
        const val KEY_ALIAS = "crown_media_credentials_v1"
        const val WRAPPED_KEY = "__wrapped_aes_key"
    }
}
