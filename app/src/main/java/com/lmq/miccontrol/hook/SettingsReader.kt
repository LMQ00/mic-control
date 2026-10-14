package com.lmq.miccontrol.hook

import de.robv.android.xposed.XSharedPreferences

/**
 * hook 进程里读取界面上的设置。
 *
 * 界面和 hook 跑在两个不同的进程里，不能共享内存变量，所以设置通过一份
 * 共享的配置文件传递：界面负责写，这里负责读。
 *
 * 关键点是**每次用的时候都重新读**，而不是启动时读一次就缓存住。
 * 这样你在界面上改了设置，已经启动的游戏/音乐应用也能立刻跟上，
 * 不需要重启它们。
 */
object SettingsReader {

    /** 界面所在应用的包名，必须和界面应用一致。 */
    private const val PACKAGE_NAME = "com.lmq.miccontrol"

    /** 配置文件名字，和界面侧用的是同一个。 */
    private const val FILE_NAME = "mic_control"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_MUSIC_PACKAGE = "music_package"

    private var prefs: XSharedPreferences? = null

    /** 读取用户选定的音乐软件包名，没选则为空。 */
    fun readMusicPackage(): String? {
        return try {
            val p = obtain() ?: return null
            // 只有配置文件变了才重新加载，避免频繁读磁盘
            if (p.hasFileChanged()) p.reload()
            p.getString(KEY_MUSIC_PACKAGE, null)
        } catch (t: Throwable) {
            null
        }
    }

    private fun obtain(): XSharedPreferences? {
        prefs?.let { return it }
        return try {
            XSharedPreferences(PACKAGE_NAME, FILE_NAME).also {
                // 尝试让配置文件可被本进程读取。不同系统版本上效果不一致，
                // 读不到时上层会走兜底逻辑，不会导致崩溃。
                runCatching { it.makeWorldReadable() }
                prefs = it
            }
        } catch (t: Throwable) {
            null
        }
    }
}
