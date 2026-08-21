package ch.steigis.overprint.data.remote.garmin

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
import ch.steigis.overprint.data.parse.FitParser
import ch.steigis.overprint.domain.model.Activity
import ch.steigis.overprint.domain.model.ActivityDetail
import ch.steigis.overprint.domain.model.ActivityType
import ch.steigis.overprint.domain.model.DataSource
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
 * Garmin Connect website client:
 * GET sso/signin → POST username/password → token exchange,
 * then activity list + FIT download.
 */
class GarminApiException(
    val httpCode: Int,
    val requestUrl: String,
    detail: String,
) : RuntimeException(userMessage(httpCode, requestUrl, detail)) {
    /** Garmin rejected the credentials, as opposed to being unreachable or broken. */
    val isAuthFailure: Boolean get() = httpCode == 401 || httpCode == 403

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
    private var session = GarminSession("")
    var lastListDiagnostic: String = ""
        private set

    /** Encoded DI OAuth session (access + refresh) stored encrypted so the password can stay unused. */
    val sessionToken: String get() = session.encode()

    /** Reuse a previously issued bearer/refresh pair instead of replaying the password. */
    fun resumeSession(token: String) {
        session = GarminSession.decode(token)
        listUrlTemplate = null
        fitUrlTemplate = null
    }

    /**
     * Checks the stored access token against the Connect API without walking fallback list URLs.
     * A 401/403/404 means the access token is spent; transport and 5xx errors are thrown so the
     * caller can keep the session instead of forcing a password login while Garmin is down.
     */
    suspend fun probeSession(): Boolean = withContext(Dispatchers.IO) {
        if (session.isBlank) return@withContext false
        val url = listUrl(LIST_URLS.first(), 0, 1)
        try {
            val body = getJson(url)
            if (runCatching { parseActivityList(body) }.isSuccess) {
                listUrlTemplate = LIST_URLS.first()
            }
            true
        } catch (err: GarminApiException) {
            if (err.isAuthFailure || err.httpCode == 404) false else throw err
        }
    }

    /** Exchanges the refresh token for a new access token. Garmin rotates the refresh token. */
    suspend fun refreshSession(): Boolean = withContext(Dispatchers.IO) {
        val refresh = session.refreshToken
        val clientId = session.clientId.ifBlank { GarminSession.clientIdFromJwt(session.accessToken).orEmpty() }
        if (refresh.isBlank() || clientId.isBlank()) return@withContext false
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refresh)
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
            val next = GarminSession.fromTokenResponse(text, clientId)
                ?: throw GarminApiException(code, DI_TOKEN_URL, "refresh response was not a token")
            session = next
            Log.i(TAG, "DI refresh ok, token length=${next.accessToken.length}")
            return@withContext true
        }
        Log.i(TAG, "DI refresh failed: HTTP $code (${bodyShape(text)})")
        // 401/403/400 (invalid_grant) mean the refresh token is spent. 429/5xx must not
        // discard it — Garmin being down is not a reason to force a password login.
        if (code == 429 || code in 500..599) {
            throw GarminApiException(code, DI_TOKEN_URL, bodyShape(text))
        }
        false
    }

    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isBlank()) {
            error("Enter your Garmin Connect email and password in Settings.")
        }
        cookieJar.clear()
        session = GarminSession("")
        getHtml(ssoEmbedUrl())
        val ssoUrl = ssoSignInUrl()
        val signInPage = getHtml(ssoUrl, referer = SSO_EMBED)
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
        val tickets = extractGarminServiceTickets(html)
        if (tickets.isEmpty()) {
            if (html.contains("name=\"password\"") && !html.contains("AUTH_SUCCESS")) {
                error("Garmin login failed. Check email and password.")
            }
            error("Garmin SSO did not return a usable service ticket. Extra verification may be required on garmin.com.")
        }
        val exchanged = exchangeServiceTickets(tickets)
        if (exchanged != null) session = exchanged
        if (session.isBlank) {
            runCatching { getHtml(ticketRedeemUrl(tickets.first())) }
            websiteTokenExchange()?.let { session = it }
        }
        if (session.isBlank) {
            Log.i(TAG, "DI ticket exchange failed: $lastListDiagnostic")
            error("Garmin login succeeded but no API token was issued. Try again in a minute.")
        }
        Log.i(TAG, "Garmin SSO login ok, token length=${session.accessToken.length}")
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
        var authFailure: GarminApiException? = null
        // A timeout or 5xx means the session cannot be blamed. A 404 on a stale proxy URL
        // does not count: unauthenticated Garmin routes often 404.
        var sawNonAuthOutcome = false
        var sawSuccessfulJson = false
        for (template in LIST_URLS) {
            val url = listUrl(template, 0, limit)
            try {
                val body = getJson(url)
                sawNonAuthOutcome = true
                val parsed = runCatching { parseActivityList(body) }
                if (parsed.isFailure) {
                    attempts += "$url -> not JSON (${parsed.exceptionOrNull()?.message})"
                    continue
                }
                sawSuccessfulJson = true
                val activities = parsed.getOrThrow()
                lastListDiagnostic = listDiagnostic(body, activities.size)
                Log.i(TAG, lastListDiagnostic)
                if (activities.isNotEmpty()) {
                    listUrlTemplate = template
                    return activities
                }
                attempts += "$url -> JSON with 0 activities (${listDiagnostic(body, 0)})"
            } catch (err: Exception) {
                when {
                    err is GarminApiException && err.isAuthFailure -> {
                        if (authFailure == null) authFailure = err
                    }
                    err is GarminApiException && err.httpCode == 404 -> {
                        // A missing proxy route is not evidence that the bearer token still works.
                    }
                    else -> sawNonAuthOutcome = true
                }
                attempts += "$url -> ${err.message}"
                Log.i(TAG, "list URL failed $url: ${err.message}")
            }
        }
        lastListDiagnostic = attempts.joinToString(" | ")
        // Surface an auth rejection as itself so callers can tell a dead session from a dead
        // endpoint; only the former justifies discarding a stored token.
        if (garminListMeansDeadSession(
                sawSuccessfulJson = sawSuccessfulJson,
                sawTransportOrServerError = sawNonAuthOutcome,
                authFailure = authFailure != null,
            )
        ) {
            throw authFailure!!
        }
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
        }.getOrElse { "not JSON" }
        return "list $keys, parsed=$parsedCount, url=$lastRequestedUrl, body=${bodyShape(body)}"
    }

    private fun parseActivityList(body: String): List<Activity> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrElse {
            error("Garmin activity list was not JSON from $lastRequestedUrl (${bodyShape(body)})")
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
                    ?: o.double("averageBikingCadenceInRevPerMinute")
                    ?: o.double("averageSwimCadenceInStrokesPerMinute"),
                avgPower = o.double("avgPower"),
                maxPower = o.double("maxPower"),
                avgGrade = o.double("avgGrade"),
                startLatitude = o.double("startLatitude"),
                startLongitude = o.double("startLongitude"),
                deviceName = o.string("deviceName") ?: o.string("manufacturer") ?: "Garmin",
                hasTrack = false,
                minHeartRate = o.double("minHR"),
                maxCadence = o.double("maxRunningCadenceInStepsPerMinute")
                    ?: o.double("maxBikingCadenceInRevPerMinute")
                    ?: o.double("maxSwimCadenceInStrokesPerMinute"),
                elevationLossMeters = o.double("elevationLoss"),
                normalizedPower = o.double("normPower") ?: o.double("normalizedPower"),
                trainingStressScore = o.double("trainingStressScore"),
                intensityFactor = o.double("intensityFactor"),
                avgTemperatureC = o.double("avgTemperature") ?: o.double("averageTemperature"),
                avgVerticalOscillationMm = o.double("avgVerticalOscillation"),
                avgStanceTimeMs = o.double("avgGroundContactTime"),
                avgVerticalRatio = o.double("avgVerticalRatio"),
                avgStepLengthMm = strideToMm(o.double("avgStrideLength")),
                avgRespirationRate = o.double("avgRespirationRate"),
                aerobicTrainingEffect = o.double("aerobicTrainingEffect"),
                anaerobicTrainingEffect = o.double("anaerobicTrainingEffect"),
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

    private fun ssoEmbedUrl(): String = HttpUrl.Builder()
        .scheme("https")
        .host("sso.garmin.com")
        .addPathSegments("sso/embed")
        .addQueryParameter("id", "gauth-widget")
        .addQueryParameter("embedWidget", "true")
        .addQueryParameter("gauthHost", SSO_BASE)
        .build()
        .toString()

    /**
     * Garmin binds the CAS ticket to this exact `service` URL. The later DI exchange
     * must send the same value as `service_url` or the ticket is rejected and spent.
     */
    private fun ssoSignInUrl(): String {
        val embed = SSO_EMBED
        return HttpUrl.Builder()
            .scheme("https")
            .host("sso.garmin.com")
            .addPathSegments("sso/signin")
            .addQueryParameter("service", embed)
            .addQueryParameter("webhost", CONNECT_MODERN)
            .addQueryParameter("source", embed)
            .addQueryParameter("redirectAfterAccountLoginUrl", embed)
            .addQueryParameter("redirectAfterAccountCreationUrl", embed)
            .addQueryParameter("gauthHost", SSO_BASE)
            .addQueryParameter("locale", "en_US")
            .addQueryParameter("id", "gauth-widget")
            .addQueryParameter("cssUrl", "https://connect.garmin.com/gauth-custom-v3.2-min.css")
            .addQueryParameter("privacyStatementUrl", "https://www.garmin.com/en-US/privacy/connect/")
            .addQueryParameter("clientId", "GarminConnect")
            .addQueryParameter("consumeServiceTicket", "false")
            .addQueryParameter("embedWidget", "true")
            .addQueryParameter("generateExtraServiceTicket", "true")
            .addQueryParameter("generateTwoExtraServiceTickets", "true")
            .addQueryParameter("generateNoServiceTicket", "false")
            .addQueryParameter("mobile", "false")
            .addQueryParameter("connectLegalTerms", "true")
            .addQueryParameter("showPassword", "true")
            .build()
            .toString()
    }

    private fun ticketRedeemUrl(ticket: String): String = HttpUrl.Builder()
        .scheme("https")
        .host("sso.garmin.com")
        .addPathSegments("sso/embed")
        .addQueryParameter("ticket", ticket)
        .build()
        .toString()

    /**
     * Service tickets are single-use. Each client ID attempt consumes one ticket; extra
     * tickets from SSO let us try the next ID if Garmin has rotated the quarterly one.
     */
    private fun exchangeServiceTickets(tickets: List<String>): GarminSession? {
        val attempts = mutableListOf<String>()
        tickets.zip(DI_CLIENT_IDS).forEach { (ticket, clientId) ->
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("service_ticket", ticket)
                .add("grant_type", DI_GRANT_TYPE)
                .add("service_url", SSO_EMBED)
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
                GarminSession.fromTokenResponse(text, clientId)?.let { token ->
                    Log.i(TAG, "DI token ok via $clientId")
                    return token
                }
                attempts += "$clientId: HTTP $code but no access_token"
            } else {
                attempts += "$clientId: HTTP $code (${bodyShape(text)})"
            }
        }
        lastListDiagnostic = attempts.joinToString(" | ")
        return null
    }

    private fun websiteTokenExchange(): GarminSession? {
        for (url in WEBSITE_TOKEN_URLS) {
            val body = runCatching { getJson(url) }.getOrNull() ?: continue
            GarminSession.fromTokenResponse(body, "")?.let { return it }
        }
        return null
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
            .header("User-Agent", if (session.accessToken.isNotBlank()) "GCM-Android-5.23" else USER_AGENT)
        if (session.accessToken.isNotBlank()) {
            header("Authorization", "Bearer ${session.accessToken}")
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
                throw GarminApiException(resp.code, lastRequestedUrl, bodyShape(bytes))
            }
            if (bytes.isEmpty()) error("Empty Garmin response from $lastRequestedUrl")
            if (expectJson) {
                val trimmed = bytes.toString(Charsets.UTF_8).trimStart()
                if (trimmed.startsWith("<") || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
                    throw GarminApiException(
                        401,
                        lastRequestedUrl,
                        "Garmin returned ${bodyShape(bytes)} instead of JSON",
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

    private fun strideToMm(value: Double?): Double? {
        value ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        return if (value in 0.4..3.5) value * 1000.0 else value
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
        private val TICKET_RE = Regex("""[?&]ticket=([^"'&\s<]+)""")

        /**
         * Picks CAS service tickets out of the SSO response. Only "ST-" values qualify: the
         * regex matches any `ticket=` parameter anywhere in the page, so without the prefix
         * check a link or tracking parameter on an error page would be accepted as a credential.
         */
        internal fun extractGarminServiceTickets(html: String): List<String> =
            TICKET_RE.findAll(html)
                .map { it.groupValues[1] }
                .filter { it.startsWith("ST-") }
                .distinct()
                .toList()

        /**
         * Describes a response body for logs and error text without reproducing it. Bodies
         * carry activity names and locations, and on the auth endpoints they may carry token
         * material, none of which belongs in logcat or on screen.
         */
        private fun bodyShape(bytes: ByteArray): String {
            val head = bytes.take(64).toByteArray().toString(Charsets.UTF_8).trimStart().firstOrNull()
            val kind = when {
                bytes.isEmpty() -> "empty response"
                head == '{' -> "a JSON object"
                head == '[' -> "a JSON array"
                head == '<' -> "an HTML/XML page"
                else -> "a non-JSON response"
            }
            return "$kind, ${bytes.size} bytes"
        }

        private fun bodyShape(body: String): String = bodyShape(body.toByteArray(Charsets.UTF_8))

        private const val SSO_EMBED = "https://sso.garmin.com/sso/embed"
        private const val SSO_BASE = "https://sso.garmin.com/sso"
        private const val CONNECT_MODERN = "https://connect.garmin.com/modern"
        private const val DI_TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"
        private const val DI_GRANT_TYPE =
            "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"
        // 2025Q2 is the last ID verified working with embed tickets. Newer quarterly IDs
        // are tried only when SSO issued extra tickets, because a miss spends the ticket.
        private val DI_CLIENT_IDS = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2026Q3",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2026Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2026Q1",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
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
