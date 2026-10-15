package com.lmq.miccontrol

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 开关状态的读写。
 *
 * 界面负责写，hook 负责读——注意这两者跑在**不同的进程**里，所以不能简单
 * 用私有存储：hook 进程属于别的应用，读不到本应用的私有文件。
 *
 * ## 为什么必须用 MODE_WORLD_READABLE
 *
 * 这不是随便选的模式。按照框架的约定，模块只有以「可被其他应用读取」的模式
 * 打开设置文件，框架才会把它放到 hook 能读到的位置。实测中若用私有模式，
 * 读取方不会报错，只会**静默拿到默认值**——表现为「钩子都挂上了，但开关
 * 永远像是关着的」，非常难查。
 *
 * 用这个模式不会真的把文件暴露给所有应用：框架会接管这个请求并重定向到它
 * 自己管理的位置，只有本模块的 hook 能读到。
 *
 * ## 兼容处理
 *
 * 个别环境可能不支持这种模式（会抛异常）。此时退回普通模式：界面功能完全
 * 正常，只是 hook 侧读不到设置。这样至少不会连界面都打不开。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = openPrefs(context)

    private fun openPrefs(context: Context): SharedPreferences {
        return try {
            @Suppress("DEPRECATION")
            context.getSharedPreferences(Prefs.FILE_NAME, Context.MODE_WORLD_READABLE)
        } catch (t: Throwable) {
            // 不支持可读模式时退回私有模式。界面照常能用，但 hook 侧读不到设置，
            // 所以这里必须留下记录，否则问题会以"开关没反应"的形式出现、难以定位。
            Log.w(
                "MicControl",
                "打开可共享的设置失败，将退回私有存储（hook 可能读不到设置）：${t.message}"
            )
            context.getSharedPreferences(Prefs.FILE_NAME, Context.MODE_PRIVATE)
        }
    }

    /** 总开关当前是否打开。 */
    var enabled: Boolean
        get() = prefs.getBoolean(Prefs.KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(Prefs.KEY_ENABLED, value).apply()

    /** 当前选中的音乐应用包名，未选则为空。 */
    var musicPackage: String?
        get() = prefs.getString(Prefs.KEY_MUSIC_PACKAGE, null)
        set(value) = prefs.edit().putString(Prefs.KEY_MUSIC_PACKAGE, value).apply()
}
