package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher

/**
 * DiagnosticAppInterface provides decoupled helper methods and intent routing to allow
 * the user to easily toggle standard local authorization settings (such as local file directory 
 * permissions, accessibility nodes, and system notifications for UI diagnostics) during manual onboarding.
 */
object DiagnosticAppInterface {

    /**
     * Build and trigger navigation intent to the system Accessibility Settings screen
     * to enable accessibility nodes scraper for UI diagnostics.
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalEncryptedStorage.saveLog(context, "DiagnosticAppInterface: Redirected user to Accessibility Settings")
        } catch (e: Exception) {
            LocalEncryptedStorage.saveLog(context, "DiagnosticAppInterface Error: Failed to launch Accessibility: ${e.message}")
        }
    }

    /**
     * Build and trigger navigation intent to the Notification Listener Settings screen.
     */
    fun openNotificationListenerSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalEncryptedStorage.saveLog(context, "DiagnosticAppInterface: Redirected user to Notification Listener Settings")
        } catch (e: Exception) {
            LocalEncryptedStorage.saveLog(context, "DiagnosticAppInterface Error: Failed to launch Notification Listener: ${e.message}")
        }
    }

    /**
     * Build and trigger navigation intent to manage the All Files Access permission
     * on Android 11+ or launch standard storage runtime prompt for manual onboarding.
     */
    fun requestStoragePermission(context: Context, launcher: ActivityResultLauncher<String>? = null) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                LocalEncryptedStorage.saveLog(context, "DiagnosticAppInterface: Redirected user to All Files Storage Settings")
            } else {
                launcher?.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    ?: LocalEncryptedStorage.saveLog(context, "DiagnosticAppInterface Warning: Launcher context not available for legacy storage request")
            }
        } catch (e: Exception) {
            LocalEncryptedStorage.saveLog(context, "DiagnosticAppInterface Error: Failed to launch Storage Settings: ${e.message}")
        }
    }

    /**
     * Prompt the user to whitelist the application from battery optimization policies (Doze Mode).
     */
    @SuppressLint("BatteryLife")
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            LocalEncryptedStorage.saveLog(context, "DiagnosticAppInterface: Prompted user to whitelist application from power restrictions")
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (ex: Exception) {
                LocalEncryptedStorage.saveLog(context, "DiagnosticAppInterface Error: Failed to open Power/Battery Settings: ${ex.message}")
            }
        }
    }
}
