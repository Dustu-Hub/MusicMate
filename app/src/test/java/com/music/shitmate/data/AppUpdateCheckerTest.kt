package com.music.shitmate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun `isNewer compares semantic and multi-digit dotted versions correctly`() {
        assertTrue(AppUpdateChecker.isNewer("2.0.3", "2.0.2"))
        assertTrue(AppUpdateChecker.isNewer("2.0.3", "2.0.1"))
        assertTrue(AppUpdateChecker.isNewer("2.0.3", "1.10"))
        assertTrue(AppUpdateChecker.isNewer("1.10", "1.9"))
        assertFalse(AppUpdateChecker.isNewer("2.0.3", "2.0.3"))
        assertFalse(AppUpdateChecker.isNewer("2.0.2", "2.0.3"))
        assertTrue(AppUpdateChecker.isNewer("2.0.3", "2.0.3-beta1"))
        assertFalse(AppUpdateChecker.isNewer("2.0.3-beta1", "2.0.3"))
    }

    @Test
    fun `extractTagFromWebResponse handles redirected release tag URLs and HTML fallback`() {
        assertEquals(
            "v2.0.3",
            AppUpdateChecker.extractTagFromWebResponse(
                "https://github.com/Dustu-Hub/ShitMate/releases/tag/v2.0.3?ref=latest",
                "",
            ),
        )
        assertEquals(
            "v2.0.3",
            AppUpdateChecker.extractTagFromWebResponse(
                "https://github.com/Dustu-Hub/ShitMate/releases/latest",
                """<a href="/Dustu-Hub/ShitMate/releases/tag/v2.0.3">v2.0.3</a>""",
            ),
        )
        assertNull(
            AppUpdateChecker.extractTagFromWebResponse(
                "https://github.com/Dustu-Hub/ShitMate/releases",
                "<html>no release</html>",
            ),
        )
    }

    @Test
    fun `extractApkAssetsFromHtml and selectApkUrl match flavor and ABI`() {
        val html = """
            <ul>
              <li><a href="/Dustu-Hub/ShitMate/releases/download/v2.0.3/ShitMate-arm64.apk">arm64</a></li>
              <li><a href="/Dustu-Hub/ShitMate/releases/download/v2.0.3/ShitMate-armeabi-v7a.apk">v7a</a></li>
              <li><a href="/Dustu-Hub/ShitMate/releases/download/v2.0.3/ShitMate-dev-arm64.apk">dev-arm64</a></li>
              <li><a href="/Dustu-Hub/ShitMate/releases/download/v2.0.3/ShitMate-Universal.apk">universal</a></li>
              <li><a href="/Dustu-Hub/ShitMate/releases/download/v2.0.3/ShitMate.apk">default</a></li>
            </ul>
        """.trimIndent()

        val assets = AppUpdateChecker.extractApkAssetsFromHtml(html)
        assertEquals(5, assets.size)

        assertEquals(
            "https://github.com/Dustu-Hub/ShitMate/releases/download/v2.0.3/ShitMate-arm64.apk",
            AppUpdateChecker.selectApkUrl(
                assets = assets,
                isDevBuild = false,
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
                apkPrefix = "ShitMate-",
            ),
        )
        assertEquals(
            "https://github.com/Dustu-Hub/ShitMate/releases/download/v2.0.3/ShitMate-dev-arm64.apk",
            AppUpdateChecker.selectApkUrl(
                assets = assets,
                isDevBuild = true,
                supportedAbis = listOf("arm64-v8a"),
                apkPrefix = "ShitMate-",
            ),
        )
        assertEquals(
            "https://github.com/Dustu-Hub/ShitMate/releases/download/v2.0.3/ShitMate-Universal.apk",
            AppUpdateChecker.selectApkUrl(
                assets = assets,
                isDevBuild = false,
                supportedAbis = listOf("riscv64"),
                apkPrefix = "ShitMate-",
            ),
        )
    }
}
