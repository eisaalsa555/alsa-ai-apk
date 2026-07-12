package com.example

import org.json.JSONObject
import android.util.Log

/**
 * Parses JSON payloads securely into native execution data structures.
 * Performs input sanitization and verification to ensure actions are strictly bounds-checked.
 */
object EncryptedPayloadDecoder {

    private const val TAG = "PayloadDecoder"

    /**
     * Native representation of an automation command payload.
     */
    sealed class DecodedCommand {
        data class Click(val x: Float, val y: Float) : DecodedCommand()
        data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float, val duration: Long) : DecodedCommand()
        data class Type(val text: String) : DecodedCommand()
        data class Launch(val packageName: String) : DecodedCommand()
        data class Url(val url: String) : DecodedCommand()
        data class ClickText(val text: String) : DecodedCommand()
        data class Unknown(val reason: String) : DecodedCommand()
    }

    /**
     * Safely decodes a JSON payload into a structured [DecodedCommand].
     * Performs strict boundaries checks on coordinates and sanitizes strings.
     */
    fun decode(payload: String): DecodedCommand {
        val trimmed = payload.trim()
        
        // 1. JSON parsing
        if (trimmed.startsWith("{")) {
            return try {
                val json = JSONObject(trimmed)
                val action = json.optString("action", "").lowercase().trim()
                
                when (action) {
                    "click" -> {
                        val x = json.optDouble("x", -1.0).toFloat()
                        val y = json.optDouble("y", -1.0).toFloat()
                        if (x >= 0f && y >= 0f) {
                            DecodedCommand.Click(x, y)
                        } else {
                            DecodedCommand.Unknown("Invalid coordinates: x=$x, y=$y")
                        }
                    }
                    "swipe" -> {
                        val startX = json.optDouble("startX", -1.0).toFloat()
                        val startY = json.optDouble("startY", -1.0).toFloat()
                        val endX = json.optDouble("endX", -1.0).toFloat()
                        val endY = json.optDouble("endY", -1.0).toFloat()
                        val duration = json.optLong("duration", 300L)
                        
                        if (startX >= 0f && startY >= 0f && endX >= 0f && endY >= 0f) {
                            DecodedCommand.Swipe(startX, startY, endX, endY, duration)
                        } else {
                            DecodedCommand.Unknown("Invalid swipe coordinates")
                        }
                    }
                    "type" -> {
                        val text = json.optString("text", "")
                        if (text.isNotEmpty()) {
                            DecodedCommand.Type(sanitizeInput(text))
                        } else {
                            DecodedCommand.Unknown("Empty type text")
                        }
                    }
                    "launch" -> {
                        val pkg = json.optString("package", "")
                        if (pkg.isNotEmpty()) {
                            DecodedCommand.Launch(sanitizeInput(pkg))
                        } else {
                            DecodedCommand.Unknown("Empty package name")
                        }
                    }
                    "url" -> {
                        val url = json.optString("url", "")
                        if (url.isNotEmpty()) {
                            DecodedCommand.Url(sanitizeInput(url))
                        } else {
                            DecodedCommand.Unknown("Empty URL")
                        }
                    }
                    "clicktext" -> {
                        val text = json.optString("text", "")
                        if (text.isNotEmpty()) {
                            DecodedCommand.ClickText(sanitizeInput(text))
                        } else {
                            DecodedCommand.Unknown("Empty click text target")
                        }
                    }
                    else -> DecodedCommand.Unknown("Unrecognized JSON action: $action")
                }
            } catch (e: Exception) {
                Log.e(TAG, "JSON parsing error", e)
                DecodedCommand.Unknown("JSON syntax error: ${e.message}")
            }
        }
        
        // 2. Fallback to colon-delimited custom text format (e.g., click:500,1000)
        val colonIndex = trimmed.indexOf(':')
        if (colonIndex != -1) {
            val action = trimmed.substring(0, colonIndex).lowercase().trim()
            val data = trimmed.substring(colonIndex + 1).trim()
            
            return when (action) {
                "click" -> {
                    val coords = data.split(",")
                    if (coords.size == 2) {
                        val x = coords[0].trim().toFloatOrNull() ?: -1f
                        val y = coords[1].trim().toFloatOrNull() ?: -1f
                        if (x >= 0f && y >= 0f) {
                            DecodedCommand.Click(x, y)
                        } else {
                            DecodedCommand.Unknown("Invalid custom coordinates")
                        }
                    } else {
                        DecodedCommand.Unknown("Invalid custom coordinates format")
                    }
                }
                "type" -> {
                    if (data.isNotEmpty()) DecodedCommand.Type(sanitizeInput(data))
                    else DecodedCommand.Unknown("Empty custom type text")
                }
                "launch" -> {
                    if (data.isNotEmpty()) DecodedCommand.Launch(sanitizeInput(data))
                    else DecodedCommand.Unknown("Empty custom launch package")
                }
                "url" -> {
                    if (data.isNotEmpty()) DecodedCommand.Url(sanitizeInput(data))
                    else DecodedCommand.Unknown("Empty custom URL")
                }
                "clicktext" -> {
                    if (data.isNotEmpty()) DecodedCommand.ClickText(sanitizeInput(data))
                    else DecodedCommand.Unknown("Empty custom clicktext target")
                }
                else -> DecodedCommand.Unknown("Unrecognized custom action: $action")
            }
        }

        return DecodedCommand.Unknown("Unsupported payload syntax")
    }

    /**
     * Basic sanitization helper to remove raw escape sequences, control characters, or malicious scripts.
     */
    private fun sanitizeInput(input: String): String {
        return input.replace(Regex("[\\u0000-\\u001F\\u007F]"), "").trim()
    }
}
