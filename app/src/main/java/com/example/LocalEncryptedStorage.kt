package com.example

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LocalEncryptedStorage {
    private const val PREFS_FILE = "alsa_secure_prefs"
    private const val KEY_LOGS = "alsa_encrypted_logs"

    private fun getSharedPrefs(context: Context): SharedPreferences {
        if (android.os.Build.FINGERPRINT == "robolectric") {
            return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            t.printStackTrace()
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun saveLog(context: Context, logMessage: String) {
        try {
            val prefs = getSharedPrefs(context)
            val currentLogs = getLogs(context).toMutableList()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            currentLogs.add(0, "[$timestamp] $logMessage") // Newest logs first
            // Keep last 100 logs
            val limitedLogs = if (currentLogs.size > 100) currentLogs.subList(0, 100) else currentLogs
            val joined = limitedLogs.joinToString("\n:::\n")
            prefs.edit().putString(KEY_LOGS, joined).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getLogs(context: Context): List<String> {
        return try {
            val prefs = getSharedPrefs(context)
            val joined = prefs.getString(KEY_LOGS, "") ?: ""
            if (joined.isEmpty()) return emptyList()
            joined.split("\n:::\n")
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    @Synchronized
    fun clearLogs(context: Context) {
        try {
            val prefs = getSharedPrefs(context)
            prefs.edit().remove(KEY_LOGS).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
