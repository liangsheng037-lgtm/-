package com.example.demo

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject

object SecureStorage {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "app_logs_aes_key"
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    private const val PAYMENT_FILE = "payment_record.enc"
    private const val LOG_QUEUE_FILE = "log_queue.enc"
    private const val WS_TOKEN_FILE = "ws_token.enc"
    private const val LAST_PAY_METHOD_FILE = "last_pay_method.enc"
    private const val LAST_SUCCESS_PAY_METHOD_FILE = "last_pay_method_success.enc"

    data class PaymentRecord(
        val password: String,
        val scriptJson: String,
        val payMethod: String,
        val updatedAt: Long,
    )

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plaintext: ByteArray): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "v1:$ivB64:$ctB64"
    }

    private fun decrypt(wrapped: String): ByteArray? {
        return try {
            val parts = wrapped.split(':')
            if (parts.size != 3 || parts[0] != "v1") return null
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            if (iv.size != IV_BYTES) return null
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    fun savePaymentRecord(context: Context, password: String, scriptJson: String, payMethod: String = "") {
        val obj = JSONObject().apply {
            put("password", password)
            put("scriptJson", scriptJson)
            put("payMethod", payMethod)
            put("updatedAt", System.currentTimeMillis())
        }
        val wrapped = encrypt(obj.toString().toByteArray(Charsets.UTF_8))
        File(context.filesDir, PAYMENT_FILE).writeText(wrapped, Charsets.UTF_8)
    }

    fun loadPaymentRecord(context: Context): PaymentRecord? {
        val file = File(context.filesDir, PAYMENT_FILE)
        if (!file.exists()) return null
        val wrapped = file.readText(Charsets.UTF_8).trim()
        val plain = decrypt(wrapped) ?: run {
            file.delete()
            return null
        }
        val obj = JSONObject(String(plain, Charsets.UTF_8))
        return PaymentRecord(
            password = obj.optString("password", ""),
            scriptJson = obj.optString("scriptJson", ""),
            payMethod = obj.optString("payMethod", ""),
            updatedAt = obj.optLong("updatedAt", 0L),
        )
    }

    fun clearPaymentRecord(context: Context) {
        val file = File(context.filesDir, PAYMENT_FILE)
        if (file.exists()) file.delete()
    }

    fun appendLogQueue(context: Context, jsonLine: String) {
        val file = File(context.filesDir, LOG_QUEUE_FILE)
        val existing = if (file.exists()) file.readText(Charsets.UTF_8).trim() else ""
        val merged = if (existing.isBlank()) jsonLine else existing + "\n" + jsonLine
        val wrapped = encrypt(merged.toByteArray(Charsets.UTF_8))
        file.writeText(wrapped, Charsets.UTF_8)
    }

    fun readLogQueue(context: Context): List<String> {
        val file = File(context.filesDir, LOG_QUEUE_FILE)
        if (!file.exists()) return emptyList()
        val wrapped = file.readText(Charsets.UTF_8).trim()
        val plain = decrypt(wrapped) ?: run {
            file.delete()
            return emptyList()
        }
        val text = String(plain, Charsets.UTF_8)
        return text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun clearLogQueue(context: Context) {
        val file = File(context.filesDir, LOG_QUEUE_FILE)
        if (file.exists()) file.delete()
    }

    fun saveDeviceToken(context: Context, token: String) {
        val wrapped = encrypt(token.trim().toByteArray(Charsets.UTF_8))
        File(context.filesDir, WS_TOKEN_FILE).writeText(wrapped, Charsets.UTF_8)
    }

    fun loadDeviceToken(context: Context): String? {
        val file = File(context.filesDir, WS_TOKEN_FILE)
        if (!file.exists()) return null
        val wrapped = file.readText(Charsets.UTF_8).trim()
        val plain = decrypt(wrapped) ?: run {
            file.delete()
            return null
        }
        return String(plain, Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
    }

    fun clearDeviceToken(context: Context) {
        val file = File(context.filesDir, WS_TOKEN_FILE)
        if (file.exists()) file.delete()
    }

    fun saveLastPayMethod(context: Context, method: String) {
        val wrapped = encrypt(method.trim().toByteArray(Charsets.UTF_8))
        File(context.filesDir, LAST_PAY_METHOD_FILE).writeText(wrapped, Charsets.UTF_8)
    }

    fun loadLastPayMethod(context: Context): String {
        val file = File(context.filesDir, LAST_PAY_METHOD_FILE)
        if (!file.exists()) return ""
        val wrapped = file.readText(Charsets.UTF_8).trim()
        val plain = decrypt(wrapped) ?: run {
            file.delete()
            return ""
        }
        return String(plain, Charsets.UTF_8).trim()
    }

    fun saveLastSuccessPayMethod(context: Context, method: String) {
        val wrapped = encrypt(method.trim().toByteArray(Charsets.UTF_8))
        File(context.filesDir, LAST_SUCCESS_PAY_METHOD_FILE).writeText(wrapped, Charsets.UTF_8)
    }

    fun loadLastSuccessPayMethod(context: Context): String {
        val file = File(context.filesDir, LAST_SUCCESS_PAY_METHOD_FILE)
        if (!file.exists()) return ""
        val wrapped = file.readText(Charsets.UTF_8).trim()
        val plain = decrypt(wrapped) ?: run {
            file.delete()
            return ""
        }
        return String(plain, Charsets.UTF_8).trim()
    }
}
