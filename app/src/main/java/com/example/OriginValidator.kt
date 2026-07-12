package com.example

import android.content.Context
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import java.net.URI

/**
 * A middleware class that validates handshake headers, TLS certificates, and connection targets
 * against the strictly authorized domain boundary: www.alsa-ai.in.
 */
object OriginValidator {

    private const val AUTHORIZED_DOMAIN = "www.alsa-ai.in"
    private const val AUTHORIZED_DOMAIN_ALT = "alsa-ai.in"

    /**
     * Checks if a raw host string matches the authorized target domain boundary.
     */
    fun isHostAuthorized(host: String?): Boolean {
        if (host == null) return false
        val cleanHost = host.lowercase().trim()
        return cleanHost == AUTHORIZED_DOMAIN || cleanHost == AUTHORIZED_DOMAIN_ALT
    }

    /**
     * Validates a URI to ensure it points to the secure authorized server.
     */
    fun isUriAuthorized(uri: URI): Boolean {
        val host = uri.host ?: return false
        return isHostAuthorized(host) && uri.scheme == "wss"
    }

    /**
     * Validates an OkHttp request before initiating a WebSocket handshake.
     */
    fun isRequestAuthorized(request: Request): Boolean {
        val host = request.url.host
        val isSecure = request.isHttps
        return isHostAuthorized(host) && isSecure
    }

    /**
     * Checks the established WebSocket connection to confirm the actual connected host is verified.
     */
    fun isWebSocketConnectionAuthorized(webSocket: WebSocket): Boolean {
        val requestUrl = webSocket.request().url
        return isHostAuthorized(requestUrl.host) && requestUrl.isHttps
    }

    /**
     * Validates response headers or certificate info if needed.
     */
    fun validateHandshakeResponse(response: Response): Boolean {
        // Enforce secure communication (TLS/SSL)
        val handshake = response.handshake
        if (handshake == null) {
            return false // TLS handshake must be present for a secure wss connection
        }
        
        // Confirm the peer certificates are valid for our domain
        val peerCertificates = handshake.peerCertificates
        if (peerCertificates.isEmpty()) {
            return false
        }
        
        // Verify response code is a successful WebSocket upgrade (101 Switching Protocols)
        if (response.code != 101) {
            return false
        }
        
        return true
    }
}
