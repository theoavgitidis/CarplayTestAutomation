package com.example.adb_connection.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) : SshHostProvider {

    companion object {
        val DEBUG_MODE = booleanPreferencesKey("debug_mode")
        val AUTO_NAVIGATION = booleanPreferencesKey("auto_navigation")
        val SSH_HOST = stringPreferencesKey("ssh_host")
        val SSH_USER = stringPreferencesKey("ssh_user")
        val SSH_PORT = stringPreferencesKey("ssh_port")
        private val LEGACY_MAC_SSH_IP = stringPreferencesKey("mac_ssh_ip")
        val MAC_LAN_IP = stringPreferencesKey("mac_lan_ip")
        val DISCOVERED_MAC_LAN_IP = stringPreferencesKey("discovered_mac_lan_ip")
        val MAC_SSH_PORT = stringPreferencesKey("mac_ssh_port")
        val MAC_SSH_USER = stringPreferencesKey("mac_ssh_user")
        private const val KEYSTORE_ALIAS = "tracemate_ssh_key"
        private const val HEADUNIT_PASSWORD_KEY = "ssh_password_enc"
        private const val HEADUNIT_PASSWORD_IV_KEY = "ssh_password_iv"
        private const val MAC_PASSWORD_KEY = "mac_ssh_password_enc"
        private const val MAC_PASSWORD_IV_KEY = "mac_ssh_password_iv"
    }

    private val securePrefs: SharedPreferences =
        context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)

    private val _sshPasswordFlow = MutableStateFlow(decryptPassword(HEADUNIT_PASSWORD_KEY, HEADUNIT_PASSWORD_IV_KEY))
    private val _macSshPasswordFlow = MutableStateFlow(decryptPassword(MAC_PASSWORD_KEY, MAC_PASSWORD_IV_KEY))

    val isDebugModeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DEBUG_MODE] ?: false
    }

    val isAutoNavigationEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_NAVIGATION] ?: false
    }

    override val sshHost: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SSH_HOST] ?: ""
    }

    val sshUser: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SSH_USER] ?: ""
    }

    val sshPort: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[SSH_PORT]?.toIntOrNull() ?: 22
    }

    val sshPassword: Flow<String> = _sshPasswordFlow

    val macLanIp: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DISCOVERED_MAC_LAN_IP]
            ?: preferences[MAC_LAN_IP]
            ?: preferences[LEGACY_MAC_SSH_IP]
            ?: ""
    }

    val manualMacLanIp: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[MAC_LAN_IP] ?: preferences[LEGACY_MAC_SSH_IP] ?: ""
    }

    val macSshPort: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[MAC_SSH_PORT]?.toIntOrNull() ?: 22
    }

    val macSshUser: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[MAC_SSH_USER] ?: ""
    }

    // Settings and connection ViewModels create separate repository instances. Read the
    // persisted password for each connection instead of retaining a stale instance cache.
    val macSshPassword: Flow<String> = flow {
        emit(decryptPassword(MAC_PASSWORD_KEY, MAC_PASSWORD_IV_KEY))
    }

    /**
     * Compatibility read access for existing relay/CAPTURE_TOOL_PLACEHOLDER code. New code should use [macLanIp].
     */
    val macSshIp: Flow<String> = macLanIp

    suspend fun setDebugModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DEBUG_MODE] = enabled
        }
    }

    suspend fun setAutoNavigationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_NAVIGATION] = enabled
        }
    }

    suspend fun setSshHost(host: String) {
        context.dataStore.edit { preferences ->
            preferences[SSH_HOST] = host
        }
    }

    suspend fun setSshUser(user: String) {
        context.dataStore.edit { preferences ->
            preferences[SSH_USER] = user
        }
    }

    suspend fun setSshPort(port: Int) {
        context.dataStore.edit { preferences ->
            preferences[SSH_PORT] = port.toString()
        }
    }

    fun setSshPassword(password: String) {
        encryptPassword(password, HEADUNIT_PASSWORD_KEY, HEADUNIT_PASSWORD_IV_KEY)
        _sshPasswordFlow.value = password
    }

    suspend fun setMacLanIp(ip: String) {
        context.dataStore.edit { preferences ->
            preferences[MAC_LAN_IP] = ip
        }
    }

    suspend fun setDiscoveredMacLanIp(ip: String) {
        context.dataStore.edit { preferences ->
            preferences[DISCOVERED_MAC_LAN_IP] = ip
        }
    }

    suspend fun setMacSshPort(port: Int) {
        context.dataStore.edit { preferences ->
            preferences[MAC_SSH_PORT] = port.toString()
        }
    }

    suspend fun setMacSshUser(user: String) {
        context.dataStore.edit { preferences ->
            preferences[MAC_SSH_USER] = user
        }
    }

    fun setMacSshPassword(password: String) {
        encryptPassword(password, MAC_PASSWORD_KEY, MAC_PASSWORD_IV_KEY)
        _macSshPasswordFlow.value = password
    }

    // --- KeyStore-based encryption (no extra library needed) ---

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.getEntry(KEYSTORE_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return keyGen.generateKey()
    }

    private fun encryptPassword(plaintext: String, passwordKey: String, ivKey: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        securePrefs.edit()
            .putString(passwordKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    private fun decryptPassword(passwordKey: String, ivKey: String): String {
        val encB64 = securePrefs.getString(passwordKey, null) ?: return ""
        val ivB64 = securePrefs.getString(ivKey, null) ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
