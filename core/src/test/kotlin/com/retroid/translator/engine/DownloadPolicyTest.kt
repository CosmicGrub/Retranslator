package com.retroid.translator.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {

    @Test
    fun `blocks when wifi is required and the device is not on wifi`() {
        assertTrue(DownloadPolicy.shouldBlockDownload(requireWifi = true, onWifi = false))
    }

    @Test
    fun `does not block once the device is on wifi`() {
        assertFalse(DownloadPolicy.shouldBlockDownload(requireWifi = true, onWifi = true))
    }

    @Test
    fun `never blocks when the caller does not require wifi, on or off it`() {
        assertFalse(DownloadPolicy.shouldBlockDownload(requireWifi = false, onWifi = false))
        assertFalse(DownloadPolicy.shouldBlockDownload(requireWifi = false, onWifi = true))
    }

    @Test
    fun `TranslationEngine's own usage - already-ready pairs never block regardless of network`() {
        // TranslationEngine.attemptTranslate passes !bothReady as requireWifi -
        // once both language packs are already downloaded, this must never
        // block even when the device is offline, matching the "offline
        // forever after" promise that call site's own doc comment states.
        val bothReady = true
        assertFalse(DownloadPolicy.shouldBlockDownload(requireWifi = !bothReady, onWifi = false))
    }
}
