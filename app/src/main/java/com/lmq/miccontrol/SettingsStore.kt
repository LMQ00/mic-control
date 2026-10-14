package com.lmq.miccontrol

import android.content.Context

/**
 * 开关状态的读写。
 *
 * 界面用这个类改状态，hook 侧也会读同一份存储。
 * 用应用自己的私有存储：hook 以目标应用的进程运行，但读的是模块自己的
 * 配置，这条路径由 Xposed 框架保证可访问。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(Prefs.FILE_NAME, Context.MODE_PRIVATE)

    /** 总开关当前是否打开。 */
    var enabled: Boolean
        get() = prefs.getBoolean(Prefs.KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(Prefs.KEY_ENABLED, value).apply()

    /** 当前选中的音乐应用包名，未选则为空。 */
    var musicPackage: String?
        get() = prefs.getString(Prefs.KEY_MUSIC_PACKAGE, null)
        set(value) = prefs.edit().putString(Prefs.KEY_MUSIC_PACKAGE, value).apply()
}
