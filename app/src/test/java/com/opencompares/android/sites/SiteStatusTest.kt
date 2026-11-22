package com.opencompares.android.sites

import com.opencompares.android.data.DeliveryPlace
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteStatusTest {

    @Test
    fun statusParsesWhatTheScriptReturns() {
        // Blinkit on the Note10 Lite: logged out, guessing the Gurugram default city.
        val s = SiteStatus.parse(
            JSONObject("""{"login":"out","location":"guessed","label":"HR-NCR","lat":28.413333,"lon":77.072833,"loginWall":false}""")
        )
        assertEquals(LoginState.Out, s.login)
        assertEquals(LocationState.Guessed, s.location)
        assertEquals("HR-NCR", s.label)
        assertEquals(28.413333, s.lat!!, 1e-9)
    }

    @Test
    fun missingFieldsMeanUnknown() {
        val s = SiteStatus.parse(JSONObject("""{"login":"maybe","label":null,"lat":null}"""))
        assertEquals(LoginState.Unknown, s.login)
        assertEquals(LocationState.Unknown, s.location)
        assertNull(s.label)
        assertNull(s.lat)
        assertFalse(s.loginWall)
    }

    @Test
    fun distanceFlagsASiteSetToAnotherArea() {
        // Blinkit's Gurugram default vs Instamart's Vasant Kunj address: about 14 km.
        val blinkit = SiteStatus(LoginState.Out, LocationState.Guessed, "HR-NCR", 28.413333, 77.072833, false)
        val km = blinkit.distanceKmFrom(28.5095867, 77.1665878)!!
        assertEquals(14.1, km, 0.3)
        assertTrue(km > FAR_AWAY_KM)
        assertNull(SiteStatus.UNKNOWN.distanceKmFrom(28.5, 77.1))
    }

    @Test
    fun deliveryPlaceSurvivesSaving() {
        val place = DeliveryPlace("Vasant Kunj, New Delhi, 110070", 28.5095867, 77.1665878)
        assertEquals(place, DeliveryPlace.decode(place.encode()))
        // A label may contain the separator; only the first two fields are numbers.
        val odd = DeliveryPlace("Block A | Sector 5", 12.9, 77.6)
        assertEquals(odd, DeliveryPlace.decode(odd.encode()))
        assertNull(DeliveryPlace.decode("not|a|place"))
        assertNull(DeliveryPlace.decode("95|10|out of range"))
        assertNull(DeliveryPlace.decode(null))
    }
}
