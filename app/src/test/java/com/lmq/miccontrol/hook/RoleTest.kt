package com.lmq.miccontrol.hook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 角色判定的单元测试。
 *
 * 这段判定决定"某个应用该不该被插手"，一旦错了表现很隐蔽：
 * 要么该截的声音没截（队友听到的还是环境声），要么在不该插手的地方插手
 * （白白给目标应用的录音加上等待，拖慢它却没有任何效果）。
 *
 * 这些组合靠手工点界面很难穷尽，所以用测试逐条固定住。
 */
class RoleTest {

    private val music = "com.netease.cloudmusic"
    private val game = "com.tencent.tmgp.sgame"

    @Test
    fun `开关没打开时任何应用都不处理`() {
        assertEquals(
            Role.NONE,
            decideRole(enabled = false, selectedPackage = music, currentPackage = game),
        )
        assertEquals(
            Role.NONE,
            decideRole(enabled = false, selectedPackage = music, currentPackage = music),
        )
    }

    @Test
    fun `还没选音乐软件时任何应用都不处理`() {
        assertEquals(
            Role.NONE,
            decideRole(enabled = true, selectedPackage = null, currentPackage = game),
        )
        assertEquals(
            Role.NONE,
            decideRole(enabled = true, selectedPackage = "", currentPackage = game),
        )
        assertEquals(
            Role.NONE,
            decideRole(enabled = true, selectedPackage = "   ", currentPackage = game),
        )
    }

    @Test
    fun `选中的音乐软件负责提供声音`() {
        assertEquals(
            Role.CAPTURE_MUSIC,
            decideRole(enabled = true, selectedPackage = music, currentPackage = music),
        )
    }

    @Test
    fun `选了音乐软件之后其它应用替换麦克风`() {
        assertEquals(
            Role.REPLACE_MIC,
            decideRole(enabled = true, selectedPackage = music, currentPackage = game),
        )
    }

    @Test
    fun `还没选音乐软件时不进入替换分支`() {
        // 这一条最容易写错：没有声音来源可送的时候去替换，只会白白给
        // 目标应用的录音加上等待时间，却没有任何效果。
        val role = decideRole(enabled = true, selectedPackage = null, currentPackage = game)
        assertEquals(Role.NONE, role)
    }

    @Test
    fun `音乐软件自己不会被替换麦克风`() {
        // 音乐软件可能也用麦克风（比如听歌识曲）。它是声音来源方，
        // 不该被替换，否则会形成自我循环。
        val role = decideRole(enabled = true, selectedPackage = music, currentPackage = music)
        assertEquals(Role.CAPTURE_MUSIC, role)
    }

    @Test
    fun `换成另一个音乐软件后角色随之改变`() {
        val another = "com.tencent.qqmusic"

        // 原来的音乐软件不再是来源方，变成普通应用
        assertEquals(
            Role.REPLACE_MIC,
            decideRole(enabled = true, selectedPackage = another, currentPackage = music),
        )
        // 新选中的那个成为来源方
        assertEquals(
            Role.CAPTURE_MUSIC,
            decideRole(enabled = true, selectedPackage = another, currentPackage = another),
        )
    }

    @Test
    fun `包名比较是精确匹配而不是包含`() {
        // 防止把 com.netease.cloudmusic 误判成 com.netease.cloudmusic.hd
        assertEquals(
            Role.REPLACE_MIC,
            decideRole(
                enabled = true,
                selectedPackage = music,
                currentPackage = "$music.hd",
            ),
        )
    }
}
