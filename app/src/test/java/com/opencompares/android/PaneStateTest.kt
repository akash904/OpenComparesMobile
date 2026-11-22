package com.opencompares.android

import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneStateTest {

    private val blinkit = MVP_PROVIDERS.single { it.key == "blinkit" }
    private val initial = PaneState.initial(blinkit)

    @Test
    fun initialPaneStartsAtTheHomepageWithTheProviderZoom() {
        assertEquals("https://blinkit.com/", initial.currentUrl)
        assertEquals(blinkit.defaultZoom, initial.zoom)
        assertEquals(0, initial.navId)
    }

    @Test
    fun searchingTheSameUrlTwiceStillNavigatesBothTimes() {
        // Previously a repeated search after browsing silently did nothing.
        val url = blinkit.buildUrl("milk")
        val first = initial.navigate(url).finished("https://blinkit.com/prn/some-product", canGoBack = true)
        val second = first.navigate(url)
        assertEquals(url, second.navUrl)
        assertNotEquals(first.navId, second.navId)
    }

    @Test
    fun navigatingClearsAnError() {
        val failed = initial.failed("https://blinkit.com/", WebViewClient.ERROR_HOST_LOOKUP)
        assertNull(failed.navigate("https://blinkit.com/s/?q=tea").error)
    }

    @Test
    fun connectionErrorsShowAsOffline() {
        listOf(WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT).forEach {
            assertEquals(PaneError.Kind.Offline, PaneState.errorKindFor(it))
        }
        assertEquals(PaneError.Kind.LoadFailed, PaneState.errorKindFor(WebViewClient.ERROR_UNKNOWN))
    }

    @Test
    fun retryAfterAFailedLoadNavigatesToTheFailedUrl() {
        val failed = initial.failed("https://blinkit.com/s/?q=eggs", WebViewClient.ERROR_TIMEOUT)
        val retried = failed.retry()
        assertEquals("https://blinkit.com/s/?q=eggs", retried.navUrl)
        assertEquals(failed.navId + 1, retried.navId)
        assertNull(retried.error)
    }

    @Test
    fun retryAfterACrashBuildsANewWebViewAtTheLastPage() {
        val crashed = initial.finished("https://blinkit.com/s/?q=rice", canGoBack = true).crashed()
        assertEquals(PaneError.Kind.Crashed, crashed.error?.kind)
        assertFalse(crashed.canGoBack)

        val retried = crashed.retry()
        assertNull(retried.error)
        assertEquals(crashed.instanceId + 1, retried.instanceId)
        // The new WebView starts from currentUrl, so no extra navigation is queued.
        assertEquals(crashed.navId, retried.navId)
        assertEquals("https://blinkit.com/s/?q=rice", retried.currentUrl)
    }

    @Test
    fun retryWithoutAnErrorJustReloads() {
        val retried = initial.retry()
        assertEquals(initial.reloadId + 1, retried.reloadId)
    }

    @Test
    fun desktopToggleAlsoReloads() {
        val toggled = initial.toggleDesktop()
        assertTrue(toggled.desktopMode)
        assertEquals(initial.reloadId + 1, toggled.reloadId)
    }

    @Test
    fun pageLifecycleTracksLoadingAndUrl() {
        val started = initial.started()
        assertTrue(started.loading)
        val done = started.finished("https://blinkit.com/s/?q=tea", canGoBack = true)
        assertFalse(done.loading)
        assertTrue(done.canGoBack)
        assertEquals("https://blinkit.com/s/?q=tea", done.currentUrl)
    }
}
