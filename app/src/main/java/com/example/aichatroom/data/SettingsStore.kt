package com.example.aichatroom.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.aichatroom.domain.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Only ciphertext reaches SharedPreferences; AES key never leaves Android Keystore.
// No keys in BuildConfig, source code, URLs, logs, Room, or saved Compose state.
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    // Separate NVIDIA slot: never reuse or transmit a saved OpenAI credential.
    private val vault = context.getSharedPreferences("encrypted_keys", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("ai_chatroom_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ai_chatroom_v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun readKey(speaker: AgentProfile): String {
        return readSecret(speaker.id)
    }
    @Synchronized fun readSecret(slot: String): String {
        val raw = vault.getString(slot, null) ?: return ""
        val pieces = raw.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    @Synchronized fun saveKey(speaker: AgentProfile, value: String) {
        saveSecret(speaker.id, value)
    }
    @Synchronized fun saveSecret(slot: String, value: String) {
        if (value.isBlank()) {
            check(vault.edit().remove(slot).commit()); return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(vault.edit().putString(slot, encoded).commit())
    }
    fun hasKey(speaker: AgentProfile) = vault.contains(speaker.name)
    // A one-time import preserves old mute/model choices without overwriting future profile edits.
    fun agentsImported() = prefs.getBoolean("agents_imported", false)
    fun markAgentsImported() { check(prefs.edit().putBoolean("agents_imported", true).commit()) }
    fun githubRepo() = prefs.getString("github_repo", "").orEmpty()
    fun saveGithubRepo(repo: String) { check(prefs.edit().putString("github_repo", repo).commit()) }
    fun read() = Preferences(
        mode = runCatching { Mode.valueOf(prefs.getString("mode", "FRIENDLY")!!) }.getOrDefault(Mode.FRIENDLY),
        nvidiaEnabled = prefs.getBoolean("nvidia_enabled", true), geminiEnabled = prefs.getBoolean("gemini", true),
        nvidiaModel = prefs.getString("nvidia_model", "nvidia/nemotron-3-super-120b-a12b")!!,
        geminiModel = prefs.getString("gemini_model_v2", "gemini-3.6-flash")!!,
        quickReplies = prefs.getBoolean("quick_replies", true),
        nvidiaFallbackModel = prefs.getString("nvidia_fallback", "qwen/qwen3.5-397b-a17b")!!)
    fun save(value: Preferences) {
        check(prefs.edit().putString("mode", value.mode.name)
            .putBoolean("quick_replies", value.quickReplies)
            .putBoolean("nvidia_enabled", value.nvidiaEnabled).putBoolean("gemini", value.geminiEnabled)
            .putString("nvidia_fallback", value.nvidiaFallbackModel).putString("nvidia_model", value.nvidiaModel).putString("gemini_model_v2", value.geminiModel).commit())
    }
}
