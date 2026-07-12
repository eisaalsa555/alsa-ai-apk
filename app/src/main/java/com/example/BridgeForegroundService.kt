package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The orchestration layer maintaining the continuous foreground thread via a persistent Notification.
 * It initializes the WebSocket connection pool, applies cryptographic origin filtering via [OriginValidator],
 * decodes commands with [EncryptedPayloadDecoder], and dispatches payloads to the [AlsaAccessibilityService] pipeline.
 */
class BridgeForegroundService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private var reconnectDelay = 2000L
    private val maxReconnectDelay = 64000L // Cap at 64s as requested by technical constraints
    private var isRunning = false
    
    companion object {
        private const val CHANNEL_ID = "AlsaBridgeChannel"
        private const val NOTIFICATION_ID = 1001
        
        @Volatile
        private var isServiceRunning = false
        fun isRunning(): Boolean = isServiceRunning
        
        // Broadcast actions for updating MainActivity UI
        const val ACTION_STATUS_UPDATE = "com.example.ALSA_STATUS_UPDATE"
        const val EXTRA_CONNECTED = "extra_connected"
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        val notification = createNotification("Disconnected", "Waiting to initiate secure bridge...")
        startForeground(NOTIFICATION_ID, notification)
        LocalEncryptedStorage.saveLog(this, "Alsa AI Bridge Core: Activated")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            connectWebSocket()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        isRunning = false
        job.cancel()
        disconnectWebSocket()
        LocalEncryptedStorage.saveLog(this, "Alsa AI Bridge Core: Deactivated")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectWebSocket() {
        if (!isRunning) return
        
        val secureUrl = "wss://www.alsa-ai.in/ws/device"
        val request = Request.Builder()
            .url(secureUrl)
            .build()

        // 1. Handshake Origin Validation via middleware
        if (!OriginValidator.isRequestAuthorized(request)) {
            LocalEncryptedStorage.saveLog(this, "SECURITY CHECK FAILED: Handshake target host is not authorized.")
            stopSelf()
            return
        }

        LocalEncryptedStorage.saveLog(this, "Initiating secure authenticated tunnel to: $secureUrl")
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 2. Active connection validation
                if (!OriginValidator.isWebSocketConnectionAuthorized(webSocket) || 
                    !OriginValidator.validateHandshakeResponse(response)) {
                    LocalEncryptedStorage.saveLog(this@BridgeForegroundService, "SECURITY ENFORCEMENT: Terminating unauthorized connection handshakes.")
                    webSocket.close(1008, "Unauthorized origin")
                    triggerReconnection()
                    return
                }

                reconnectDelay = 2000L // Reset backoff delay on successful connection
                updateNotification("Connected", "Secure enterprise bridge active on www.alsa-ai.in")
                LocalEncryptedStorage.saveLog(this@BridgeForegroundService, "Tunnel handshake successful. Session established with www.alsa-ai.in")
                broadcastStatus(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 3. Frame Origin Validation
                if (!OriginValidator.isWebSocketConnectionAuthorized(webSocket)) {
                    LocalEncryptedStorage.saveLog(this@BridgeForegroundService, "SECURITY BREACH: Discarded frame from non-validated connection.")
                    return
                }

                LocalEncryptedStorage.saveLog(this@BridgeForegroundService, "Inbound packet captured: $text")
                parseAndExecuteCommand(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                LocalEncryptedStorage.saveLog(this@BridgeForegroundService, "WebSocket tunnel closed gracefully: $reason (Code: $code)")
                broadcastStatus(false)
                triggerReconnection()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LocalEncryptedStorage.saveLog(this@BridgeForegroundService, "WebSocket tunnel communication error: ${t.message}")
                broadcastStatus(false)
                triggerReconnection()
            }
        })
    }

    private fun disconnectWebSocket() {
        webSocket?.close(1000, "Service Shutdown")
        webSocket = null
        broadcastStatus(false)
    }

    private fun triggerReconnection() {
        if (!isRunning) return
        updateNotification("Reconnecting", "Connection lost. Reconnecting in ${reconnectDelay / 1000}s...")
        scope.launch {
            delay(reconnectDelay)
            // Exponential backoff (Starts at 2s, doubles up to 64s)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(maxReconnectDelay)
            connectWebSocket()
        }
    }

    private fun broadcastStatus(connected: Boolean) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_CONNECTED, connected)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun parseAndExecuteCommand(payload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                // Use the modular EncryptedPayloadDecoder middleware
                val decoded = EncryptedPayloadDecoder.decode(payload)
                
                when (decoded) {
                    is EncryptedPayloadDecoder.DecodedCommand.Click -> {
                        val success = AlsaAccessibilityService.simulateClick(decoded.x, decoded.y)
                        sendCommandFeedback("click", success, "Coordinates (${decoded.x}, ${decoded.y})")
                    }
                    is EncodedCommandWrapper -> { /* Reserved for future extensions */ }
                    is EncryptedPayloadDecoder.DecodedCommand.Swipe -> {
                        val success = AlsaAccessibilityService.simulateSwipe(
                            decoded.startX, decoded.startY, decoded.endX, decoded.endY, decoded.duration
                        )
                        sendCommandFeedback("swipe", success, "From (${decoded.startX}, ${decoded.startY}) to (${decoded.endX}, ${decoded.endY})")
                    }
                    is EncryptedPayloadDecoder.DecodedCommand.Type -> {
                        val success = AlsaAccessibilityService.injectText(decoded.text)
                        sendCommandFeedback("type", success, "Text input automated")
                    }
                    is EncryptedPayloadDecoder.DecodedCommand.Launch -> {
                        val success = AlsaAccessibilityService.launchApp(decoded.packageName)
                        sendCommandFeedback("launch", success, "Package '${decoded.packageName}'")
                    }
                    is EncryptedPayloadDecoder.DecodedCommand.Url -> {
                        val success = AlsaAccessibilityService.resolveUrl(decoded.url)
                        sendCommandFeedback("url", success, "URL resolution completed")
                    }
                    is EncryptedPayloadDecoder.DecodedCommand.ClickText -> {
                        val success = AlsaAccessibilityService.clickNodeWithText(decoded.text)
                        sendCommandFeedback("clicktext", success, "Click element matching label '${decoded.text}'")
                    }
                    is EncryptedPayloadDecoder.DecodedCommand.Unknown -> {
                        LocalEncryptedStorage.saveLog(this@BridgeForegroundService, "Execution error: ${decoded.reason}")
                    }
                }
            } catch (e: Exception) {
                LocalEncryptedStorage.saveLog(this@BridgeForegroundService, "Decryption/Execution pipeline error: ${e.message}")
            }
        }
    }

    // Reserved placeholder for extra capability wrapping
    private sealed class EncodedCommandWrapper

    private fun sendCommandFeedback(action: String, success: Boolean, detail: String) {
        val statusStr = if (success) "SUCCESS" else "FAILED"
        LocalEncryptedStorage.saveLog(this, "Automation status updated: Action='$action' | Status=$statusStr | Detail=$detail")
        scope.launch {
            try {
                val feedback = JSONObject().apply {
                    put("type", "feedback")
                    put("action", action)
                    put("status", statusStr)
                    put("detail", detail)
                    put("timestamp", System.currentTimeMillis())
                }
                webSocket?.send(feedback.toString())
            } catch (e: Exception) {
                // Fail silently
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alsa Bridge Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Alsa Ai Bridge securely connected to the backend"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String, detail: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Alsa AI Bridge Status: $status")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: String, detail: String) {
        val notification = createNotification(status, detail)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
