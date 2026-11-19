package com.lmq.miccontrol

import kotlin.test.Test
import kotlin.test.assertEquals

class AppScannerTest {

    private val apps = listOf(
        MusicApp("com.netease.cloudmusic", "网易云音乐"),
        MusicApp("com.tencent.qqmusic", "QQ音乐"),
        MusicApp("com.google.android.apps.youtube.music", "YouTube Music"),
        MusicApp("com.spotify.music", "Spotify"),
    )

    @Test
    fun blankQueryReturnsAllApps() {
        assertEquals(apps, AppScanner.filterApps(apps, ""))
        assertEquals(apps, AppScanner.filterApps(apps, "   "))
    }

    @Test
    fun matchesByLabel() {
        assertEquals(listOf(apps[1]), AppScanner.filterApps(apps, "QQ"))
        assertEquals(listOf(apps[0]), AppScanner.filterApps(apps, "云音乐"))
    }

    @Test
    fun matchesByPackageName() {
        assertEquals(listOf(apps[3]), AppScanner.filterApps(apps, "spotify"))
        assertEquals(listOf(apps[2]), AppScanner.filterApps(apps, "youtube"))
    }

    @Test
    fun ignoresCaseAndSurroundingSpaces() {
        assertEquals(listOf(apps[3]), AppScanner.filterApps(apps, "  SPOTIFY  "))
    }

    @Test
    fun noMatchReturnsEmptyList() {
        assertEquals(emptyList(), AppScanner.filterApps(apps, "不存在的应用"))
    }

    @Test
    fun keepsOriginalOrder() {
        val labels = AppScanner.filterApps(apps, "music").map { it.label }
        assertEquals(listOf("网易云音乐", "QQ音乐", "YouTube Music", "Spotify"), labels)
    }
}
