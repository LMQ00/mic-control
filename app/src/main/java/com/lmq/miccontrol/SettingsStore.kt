package com.lmq.miccontrol

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager

/**
 * 开关状态和所选应用的读写。
 *
 * 开关最终要落成"装不装钩子"这个动作，所以在存储之外还负责把包名换算成
 * 系统认的应用编号——钩子按编号过滤音轨。
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(Prefs.FILE_NAME, Context.MODE_PRIVATE)

    /** 总开关当前是否打开。 */
    var enabled: Boolean
        get() = prefs.getBoolean(Prefs.KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(Prefs.KEY_ENABLED, value).apply()

    /** 当前选中的音乐应用包名，未选则为空。 */
    var musicPackage: String?
        get() = prefs.getString(Prefs.KEY_MUSIC_PACKAGE, null)
        set(value) = prefs.edit().putString(Prefs.KEY_MUSIC_PACKAGE, value).apply()

    /**
     * 把包名换算成系统里的应用编号。
     *
     * 钩子按这个编号认音轨，编号对不上就抄不到声音。取不到时返回空，
     * 由调用方提示用户重选。
     */
    fun uidOf(packageName: String?): Int? {
        if (packageName.isNullOrBlank()) return null
        return try {
            val info = appContext.packageManager.getApplicationInfo(packageName, 0)
            info.uid
        } catch (t: PackageManager.NameNotFoundException) {
            null
        }
    }
}
