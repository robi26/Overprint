package net.roz.connectstats.data.remote.garmin

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.roz.connectstats.data.parse.FitParser
import net.roz.connectstats.domain.model.Activity
import net.roz.connectstats.domain.model.ActivityDetail
import net.roz.connectstats.domain.model.ActivityType
import net.roz.connectstats.domain.model.DataSource
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Garmin Connect website client, matching the iOS SSO path in GCGarminLoginSSO:
 * GET sso/signin → POST username/password → GET connect.garmin.com/modern
 * (cookie jar), then activity list + FIT download.
 */
class GarminApiException(
    val httpCode: Int,
    val requestUrl: String,
    detail: String,
) : RuntimeException(userMessage(httpCode, requestUrl, detail)) {
    companion object {
        private fun userMessage(code: Int, url: String, detail: String): String = when (code) {
            401, 403 -> "Garmin session expired or access denied (HTTP $code). Sign in again."
            404 -> when {
                url.contains("download-service") ->
                    "Garmin could not find that activity file (HTTP 404)."
                url.contains("activitylist") ->
                    "Garmin activity list was not found (HTTP 404). Sign in again, then retry."
                else -> "Garmin returned HTTP 404."
            }
            429 -> "Garmin rate-limited the request. Wait a minute and retry."
            in 500..599 -> "Garmin is temporarily unavailable (HTTP $code). Try again later."
            else -> {
                val extra = detail.trim().takeIf { it.isNotEmpty() }?.let { ": ${it.take(120)}" }.orEmpty()
                "Garmin request failed (HTTP $code)$extra"
            }
        }
    }
}

class GarminClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val cookieJar = MemoryCookieJar()
    private val htmlClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        // Never let a redirect downgrade an authenticated request to http://.
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private var listUrlTemplate: String? = null
    private var fitUrlTemplate: String? = null
    private var lastRequestedUrl: String = ""
    private var accessToken: String = ""
    var lastListDiagnostic: String = ""
        private set

    /** Bearer token issued by [login], to be stored encrypted so the password can stay unused. */
    val sessionToken: String get() = accessToken

    /** Reuse a previously issued bearer token instead of replaying the password. */
    fun resumeSession(token: String) {
        accessToken = token
    }

    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isBlank()) {
            error("Enter your Garmin Connect email and password in Settings.")
        }
        cookieJar.clear()
        val ssoUrl = ssoSignInUrl()
        val signInPage = getHtml(ssoUrl, referer = "https://connectstats.app")
        val form = FormBody.Builder()
        hiddenInputs(signInPage).forEach { (name, value) -> form.add(name, value) }
        form.add("username", username)
            .add("password", password)
            .add("_eventId", "submit")
            .add("embed", "true")
        val html = postForm(ssoUrl, form.build(), origin = "https://sso.garmin.com")
        when {
            html.contains(">sendEvent('FAIL')") -> error("Garmin login failed. Check email and password.")
            html.contains(">sendEvent('ACCOUNT_LOCK')") -> error("Garmin account is locked.")
            html.contains("renewPassword") ->
                error("Garmin requires a password reset. Sign in on garmin.com in a browser first.")
            html.contains("temporarily unavailable") -> error("Garmin SSO is temporarily unavailable.")
            html.contains("twoStep", ignoreCase = true) ||
                html.contains("EnterSecurityCode") ||
                html.contains("mfa", ignoreCase = true) && html.contains("verification", ignoreCase = true) ->
                error("Garmin asked for extra verification. Complete that on garmin.com, then try again.")
        }
        val ticket = extractTicket(html)
        if (ticket == null) {
            if (html.contains("name=\"password\"") && !html.contains("AUTH_SUCCESS")) {
                error("Garmin login failed. Check email and password.")
            }
            error("Garmin SSO did not return a service ticket. Extra verification may be required on garmin.com.")
        }
        accessToken = exchangeServiceTicket(ticket).orEmpty()
        if (accessToken.isBlank()) {
            runCatching { getHtml("https://connect.garmin.com/modern?ticket=$ticket") }
            accessToken = websiteTokenExchange().orEmpty()
        }
        if (accessToken.isBlank()) {
            error(
                "Garmin login succeeded but no API token was issued. " +
                    (lastListDiagnostic.takeIf { it.isNotBlank() } ?: "Garmin now requires an OAuth token for activity download."),
            )
        }
        Log.i(TAG, "Garmin SSO login ok, token length=${accessToken.length}")
    }

    suspend fun listActivities(start: Int = 0, limit: Int = 20): List<Activity> = withContext(Dispatchers.IO) {
        if (start == 0 && listUrlTemplate == null) {
            return@withContext firstNonEmptyList(limit)
        }
        val body = getJson(listUrl(listUrlTemplate ?: LIST_URLS.first(), start, limit))
        parseActivityList(body)
    }

    private fun firstNonEmptyList(limit: Int): List<Activity> {
        val attempts = mutableListOf<String>()
        for (template in LIST_URLS) {
            val url = listUrl(template, 0, limit)
            try {
                val body = getJson(url)
                val parsed = runCatching { parseActivityList(body) }
                if (parsed.isFailure) {
                    attempts += "$url -> not JSON (${parsed.exceptionOrNull()?.message})"
                    continue
                }
                val activities = parsed.getOrThrow()
                lastListDiagnostic = listDiagnostic(body, activities.size)
                Log.i(TAG, lastListDiagnostic)
                if (activities.isNotEmpty()) {
                    listUrlTemplate = template
                    return activities
                }
                attempts += "$url -> JSON with 0 activities (${listDiagnostic(body, 0)})"
            } catch (err: Exception) {
                attempts += "$url -> ${err.message}"
                Log.i(TAG, "list URL failed $url: ${err.message}")
            }
        }
        lastListDiagnostic = attempts.joinToString(" | ")
        error("Garmin activity list failed. ${attempts.joinToString(" ")}")
    }

    suspend fun downloadFit(externalId: String): ActivityDetail = withContext(Dispatchers.IO) {
        val bytes = getFirst(fitUrlTemplate, FIT_URLS, { fitUrlTemplate = it }) { template ->
            getBytes(template.format(externalId))
        }
        val fit = if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            unzipFirstFit(bytes)
        } else bytes
        FitParser.parse(fit, "garmin-$externalId", "Garmin $externalId").let { detail ->
            detail.copy(
                activity = detail.activity.copy(
                    source = DataSource.GARMIN,
                    externalId = externalId,
                    id = "garmin-$externalId",
                ),
            )
        }
    }

    private fun listDiagnostic(body: String, parsedCount: Int): String {
        val keys = runCatching {
            val root = json.parseToJsonElement(body)
            when (root) {
                is JsonObject -> "object keys=${root.keys.joinToString()}"
                is JsonArray -> "array size=${root.size}"
                else -> root::class.simpleName ?: "json"
            }
        }.getOrElse { "not JSON (${body.take(80)})" }
        val snippet = body.replace("\n", " ").take(80)
        return "list $keys, parsed=$parsedCount, url=$lastRequestedUrl, body=$snippet"
    }

    private fun parseActivityList(body: String): List<Activity> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrElse {
            val snippet = body.trim().replace("\n", " ").take(100)
            error("Garmin activity list was not JSON from $lastRequestedUrl ($snippet)")
        }
        return activityArray(root).mapNotNull { el ->
            val o = el.jsonObject
            val id = o.anyId("activityId", "activityIdPk", "id") ?: return@mapNotNull null
            val typeKey = o["activityType"]?.jsonObject?.string("typeKey")
                ?: o.string("activityType")
            val startIso = o.string("startTimeGMT") ?: o.string("startTimeLocal")
            Activity(
                id = "garmin-$id",
                externalId = id,
                source = DataSource.GARMIN,
                name = o.string("activityName") ?: o.string("name") ?: "Garmin activity",
                type = ActivityType.fromKey(typeKey),
                startTimeMillis = parseGmt(startIso) ?: System.currentTimeMillis(),
                location = o.string("locationName"),
                distanceMeters = o.double("distance") ?: 0.0,
                durationSeconds = o.double("elapsedDuration") ?: o.double("duration") ?: 0.0,
                movingSeconds = o.double("movingDuration") ?: o.double("duration") ?: 0.0,
                elevationGainMeters = o.double("elevationGain"),
                calories = o.double("calories"),
                avgHeartRate = o.double("averageHR"),
                maxHeartRate = o.double("maxHR"),
                avgSpeedMps = o.double("averageSpeed"),
                maxSpeedMps = o.double("maxSpeed"),
                avgCadence = o.double("averageRunningCadenceInStepsPerMinute")
                    ?: o.double("averageBikingCadenceInRevPerMinute"),
                avgPower = o.double("avgPower"),
                maxPower = o.double("maxPower"),
                avgGrade = o.double("avgGrade"),
                startLatitude = o.double("startLatitude"),
                startLongitude = o.double("startLongitude"),
                deviceName = o.string("deviceName") ?: "Garmin",
                hasTrack = false,
            )
        }
    }

    private fun activityArray(root: JsonElement): JsonArray {
        when (root) {
            is JsonArray -> return root
            is JsonObject -> {
                for (key in listOf(
                    "activityList", "activities", "activityListDTOs", "results", "entries", "data",
                )) {
                    val child = root[key] ?: continue
                    val found = activityArray(child)
                    if (found.isNotEmpty() || child is JsonArray) return found
                }
                root.values.forEach { value ->
                    if (value is JsonArray && looksLikeActivities(value)) return value
                }
            }
            else -> Unit
        }
        return JsonArray(emptyList())
    }

    private fun looksLikeActivities(arr: JsonArray): Boolean {
        val first = arr.firstOrNull() as? JsonObject ?: return false
        return first.containsKey("activityId") || first.containsKey("activityIdPk") ||
            first.containsKey("activityName")
    }

    private fun <T> getFirst(
        cached: String?,
        templates: List<String>,
        remember: (String) -> Unit,
        call: (String) -> T,
    ): T {
        if (cached != null) return call(cached)
        var lastError: Exception? = null
        for (template in templates) {
            try {
                val result = call(template)
                remember(template)
                return result
            } catch (err: GarminApiException) {
                lastError = err
            }
        }
        throw lastError ?: error("Garmin request failed")
    }

    private fun ssoSignInUrl(): String = HttpUrl.Builder()
        .scheme("https")
        .host("sso.garmin.com")
        .addPathSegments("sso/signin")
        .addQueryParameter("service", "https://connect.garmin.com/modern")
        .addQueryParameter("clientId", "GarminConnect")
        .addQueryParameter("gauthHost", "https://sso.garmin.com/sso")
        .addQueryParameter("consumeServiceTicket", "false")
        .build()
        .toString()

    private fun extractTicket(html: String): String? {
        val match = Regex("""[?&]ticket=([^"'&\s<]+)""").find(html) ?: return null
        return match.groupValues[1].takeIf { it.startsWith("ST-") || it.isNotBlank() }
    }

    private fun exchangeServiceTicket(ticket: String): String? {
        val attempts = mutableListOf<String>()
        for (clientId in DI_CLIENT_IDS) {
            for (serviceUrl in DI_SERVICE_URLS) {
                val body = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("service_ticket", ticket)
                    .add("grant_type", DI_GRANT_TYPE)
                    .add("service_url", serviceUrl)
                    .build()
                val basic = "Basic " + Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP)
                val req = Request.Builder()
                    .url(DI_TOKEN_URL)
                    .post(body)
                    .header("Authorization", basic)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "GCM-Android-5.23")
                    .header("nk", "NT")
                    .build()
                val (code, text) = executeRaw(req)
                if (code in 200..299) {
                    parseAccessToken(text)?.let { token ->
                        Log.i(TAG, "DI token ok via $clientId / $serviceUrl")
                        return token
                    }
                    attempts += "$clientId: HTTP $code but no access_token"
                } else {
                    attempts += "$clientId: HTTP $code ${text.take(80)}"
                }
            }
        }
        Log.i(TAG, "DI ticket exchange failed: ${attempts.joinToString(" | ")}")
        lastListDiagnostic = attempts.joinToString(" | ")
        return null
    }

    private fun websiteTokenExchange(): String? {
        for (url in WEBSITE_TOKEN_URLS) {
            val body = runCatching { getJson(url) }.getOrNull() ?: continue
            parseAccessToken(body)?.let { return it }
        }
        return null
    }

    private fun parseAccessToken(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val nested = root["data"] as? JsonObject
        return root.string("access_token")
            ?: root.string("accessToken")
            ?: nested?.string("access_token")
            ?: nested?.string("accessToken")
    }

    private fun hiddenInputs(html: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        val tagRe = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)
        val nameRe = Regex("""\bname=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val valueRe = Regex("""\bvalue=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val typeRe = Regex("""\btype=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        tagRe.findAll(html).forEach { match ->
            val tag = match.value
            val type = typeRe.find(tag)?.groupValues?.get(1)?.lowercase() ?: "text"
            if (type != "hidden") return@forEach
            val name = nameRe.find(tag)?.groupValues?.get(1) ?: return@forEach
            map[name] = valueRe.find(tag)?.groupValues?.get(1).orEmpty()
        }
        return map
    }

    private fun getHtml(url: String, referer: String? = null): String =
        execute(
            Request.Builder()
                .url(url)
                .get()
                .apply { referer?.let { header("Referer", it) } }
                .htmlHeaders()
                .build(),
            expectJson = false,
        ).toString(Charsets.UTF_8)

    private fun postForm(url: String, body: FormBody, origin: String): String =
        execute(
            Request.Builder()
                .url(url)
                .post(body)
                .header("Origin", origin)
                .htmlHeaders()
                .build(),
            expectJson = false,
        ).toString(Charsets.UTF_8)

    private fun getJson(url: String): String = execute(
        Request.Builder()
            .url(url)
            .get()
            .jsonHeaders()
            .build(),
        expectJson = true,
    ).toString(Charsets.UTF_8)

    private fun getBytes(url: String): ByteArray = execute(
        Request.Builder().url(url).get().jsonHeaders().build(),
        expectJson = false,
    )

    private fun Request.Builder.htmlHeaders(): Request.Builder =
        header("nk", "NT")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("User-Agent", USER_AGENT)

    private fun Request.Builder.jsonHeaders(): Request.Builder {
        header("nk", "NT")
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://connect.garmin.com")
            .header("Referer", "https://connect.garmin.com/modern/activities")
            .header("Di-Backend", "connectapi.garmin.com")
            .header("User-Agent", if (accessToken.isNotBlank()) "GCM-Android-5.23" else USER_AGENT)
        if (accessToken.isNotBlank()) {
            header("Authorization", "Bearer $accessToken")
        }
        return this
    }

    private fun executeRaw(req: Request): Pair<Int, String> {
        lastRequestedUrl = req.url.toString()
        htmlClient.newCall(req).execute().use { resp ->
            lastRequestedUrl = resp.request.url.toString()
            val text = resp.body?.string().orEmpty()
            return resp.code to text
        }
    }

    private fun execute(req: Request, expectJson: Boolean): ByteArray {
        lastRequestedUrl = req.url.toString()
        htmlClient.newCall(req).execute().use { resp ->
            lastRequestedUrl = resp.request.url.toString()
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                throw GarminApiException(resp.code, lastRequestedUrl, bytes.toString(Charsets.UTF_8).take(200))
            }
            if (bytes.isEmpty()) error("Empty Garmin response from $lastRequestedUrl")
            if (expectJson) {
                val trimmed = bytes.toString(Charsets.UTF_8).trimStart()
                if (trimmed.startsWith("<") || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
                    throw GarminApiException(
                        401,
                        lastRequestedUrl,
                        "Garmin returned a web page instead of JSON (${trimmed.take(80)})",
                    )
                }
            }
            return bytes
        }
    }

    private fun unzipFirstFit(zipBytes: ByteArray): ByteArray {
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.lowercase().endsWith(".fit")) {
                    return zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return zipBytes
    }

    private fun JsonObject.string(key: String) =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
    private fun JsonObject.double(key: String) =
        runCatching { this[key]?.jsonPrimitive?.doubleOrNull }.getOrNull()
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    private fun JsonObject.anyId(vararg keys: String): String? {
        keys.forEach { key ->
            val p = this[key]?.jsonPrimitive ?: return@forEach
            p.longOrNull?.toString()?.let { return it }
            p.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        }
        return null
    }

    private fun parseGmt(iso: String?): Long? {
        iso ?: return null
        val fmts = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss")
        fmts.forEach { p ->
            runCatching {
                return SimpleDateFormat(p, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(iso)?.time
            }
        }
        return iso.toLongOrNull()?.times(1000)
    }

    private class MemoryCookieJar : CookieJar {
        private val lock = Any()
        private val stored = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(lock) {
                cookies.forEach { incoming ->
                    stored.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                    stored += incoming
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
            stored.filter { it.matches(url) }
        }

        fun forUrl(url: HttpUrl): List<Cookie> = loadForRequest(url)

        fun clear() = synchronized(lock) { stored.clear() }
    }

    companion object {
        const val TAG = "GarminClient"
        const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        private const val DI_TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"
        private const val DI_GRANT_TYPE =
            "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"
        private val DI_CLIENT_IDS = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2026Q3",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2026Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2026Q1",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
        )
        private val DI_SERVICE_URLS = listOf(
            "https://connect.garmin.com/modern",
            "https://connect.garmin.com/app",
            "https://mobile.integration.garmin.com/gcm/android",
        )
        private val WEBSITE_TOKEN_URLS = listOf(
            "https://connect.garmin.com/modern/di-oauth/exchange",
            "https://connect.garmin.com/services/auth/token/exchange",
            "https://connect.garmin.com/di-oauth/exchange",
        )

        private fun listUrl(template: String, start: Int, limit: Int): String =
            template.replace("{start}", start.toString()).replace("{limit}", limit.toString())

        private val LIST_URLS = listOf(
            "https://connectapi.garmin.com/activitylist-service/activities/search/activities?limit={limit}&start={start}",
            "https://connect.garmin.com/gc-api/activitylist-service/activities/search/activities?limit={limit}&start={start}",
            "https://connect.garmin.com/app/proxy/activitylist-service/activities?start={start}&limit={limit}",
            "https://connect.garmin.com/app/proxy/activitylist-service/activities/search/activities?start={start}&limit={limit}",
        )
        private val FIT_URLS = listOf(
            "https://connectapi.garmin.com/download-service/files/activity/%s",
            "https://connect.garmin.com/gc-api/download-service/files/activity/%s",
            "https://connect.garmin.com/app/proxy/download-service/files/activity/%s",
            "https://connect.garmin.com/modern/proxy/download-service/files/activity/%s",
        )
    }
}
