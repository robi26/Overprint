package net.roz.connectstats.data.remote.garmin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Garmin DI OAuth tokens. The access token is short-lived; the refresh token is what
 * actually lets a later sync skip the password. [encode] is stored encrypted in settings.
 */
internal data class GarminSession(
    val accessToken: String,
    val refreshToken: String = "",
    val clientId: String = "",
) {
    val isBlank: Boolean get() = accessToken.isBlank()

    fun encode(): String {
        if (accessToken.isBlank()) return ""
        if (refreshToken.isBlank() && clientId.isBlank()) return accessToken
        return buildJsonObject {
            put("access_token", accessToken)
            put("refresh_token", refreshToken)
            put("client_id", clientId)
        }.toString()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String): GarminSession {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return GarminSession("")
            if (!trimmed.startsWith("{")) return GarminSession(trimmed)
            val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
                ?: return GarminSession(trimmed)
            val access = root.string("access_token") ?: root.string("accessToken").orEmpty()
            if (access.isBlank()) return GarminSession("")
            return GarminSession(
                accessToken = access,
                refreshToken = root.string("refresh_token") ?: root.string("refreshToken").orEmpty(),
                clientId = root.string("client_id") ?: root.string("clientId").orEmpty(),
            )
        }

        fun fromTokenResponse(body: String, fallbackClientId: String): GarminSession? {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                ?: return null
            val nested = root["data"] as? JsonObject
            val access = root.string("access_token")
                ?: root.string("accessToken")
                ?: nested?.string("access_token")
                ?: nested?.string("accessToken")
                ?: return null
            val refresh = root.string("refresh_token")
                ?: root.string("refreshToken")
                ?: nested?.string("refresh_token")
                ?: nested?.string("refreshToken")
                ?: ""
            val clientId = fallbackClientId.ifBlank { clientIdFromJwt(access).orEmpty() }
            return GarminSession(access, refresh, clientId)
        }

        fun clientIdFromJwt(accessToken: String): String? {
            val payload = accessToken.split('.').getOrNull(1) ?: return null
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val decoded = runCatching {
                String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
            }.getOrNull() ?: return null
            val root = runCatching { json.parseToJsonElement(decoded) }.getOrNull() as? JsonObject
                ?: return null
            return root.string("client_id") ?: root.string("cid") ?: root.string("azp")
        }

        private fun JsonObject.string(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != "null" }
    }
}

/**
 * A 404 on an old proxy URL is not proof the bearer token still works. Only a JSON
 * success, or a transport/5xx error, should stop us from treating the session as dead.
 */
internal fun garminListMeansDeadSession(
    sawSuccessfulJson: Boolean,
    sawTransportOrServerError: Boolean,
    authFailure: Boolean,
): Boolean = authFailure && !sawSuccessfulJson && !sawTransportOrServerError
