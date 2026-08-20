package net.roz.connectstats

import net.roz.connectstats.data.remote.garmin.GarminSession
import net.roz.connectstats.data.remote.garmin.garminListMeansDeadSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminSessionTest {
    @Test
    fun decodeLegacyAccessToken() {
        val session = GarminSession.decode("plain-access-token")
        assertEquals("plain-access-token", session.accessToken)
        assertEquals("", session.refreshToken)
        assertEquals("plain-access-token", session.encode())
    }

    @Test
    fun roundTripRefreshBundle() {
        val original = GarminSession("access-abc", "refresh-xyz", "GARMIN_CONNECT_MOBILE_ANDROID_DI")
        val restored = GarminSession.decode(original.encode())
        assertEquals(original, restored)
    }

    @Test
    fun parseTokenResponseKeepsRefreshAndClientId() {
        val body = """{"access_token":"a1","refresh_token":"r1","expires_in":3600}"""
        val session = GarminSession.fromTokenResponse(body, "GARMIN_CONNECT_MOBILE_ANDROID_DI_2026Q3")
        assertEquals("a1", session?.accessToken)
        assertEquals("r1", session?.refreshToken)
        assertEquals("GARMIN_CONNECT_MOBILE_ANDROID_DI_2026Q3", session?.clientId)
    }

    @Test
    fun clientIdFromJwtPayload() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"client_id":"GARMIN_CONNECT_MOBILE_ANDROID_DI"}""".toByteArray(),
        )
        val jwt = "header.$payload.sig"
        assertEquals("GARMIN_CONNECT_MOBILE_ANDROID_DI", GarminSession.clientIdFromJwt(jwt))
    }

    @Test
    fun mixedAuthAnd404IsADeadSession() {
        assertTrue(garminListMeansDeadSession(false, false, true))
        assertFalse(garminListMeansDeadSession(true, false, true))
        assertFalse(garminListMeansDeadSession(false, true, true))
        assertFalse(garminListMeansDeadSession(false, false, false))
    }
}
