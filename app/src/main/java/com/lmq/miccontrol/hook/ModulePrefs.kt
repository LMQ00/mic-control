package com.lmq.miccontrol.hook

import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge

/**
 * hook 进程里读取界面上的设置。
 *
 * 界面和 hook 跑在两个不同的进程里，不能共享内存变量，所以设置通过一份
 * 共享的配置文件传递：界面负责写、这里负责读。
 *
 * ## 两个必须注意的点
 *
 * 1. **每次都要重新读**，不能启动时读一次就缓存住。否则你在界面上改了开关，
 *    已经启动的游戏不会跟着变，必须重启游戏才生效。
 * 2. **新建后必须先主动加载一次**。这类配置对象不会在构造时读文件，
 *    如果直接读，拿到的是空值——表现就是"开关明明打开了却毫无反应"，
 *    而且没有任何报错，极难排查。所以第一次取得对象后立刻加载一遍。
 *
 * 读不到配置时一律按"开关是关的"处理：宁可不出效果，也不要让你处于
 * "以为关了、其实没关"的状态。
 */
object ModulePrefs {

    /** 界面所在应用的包名，必须与界面应用一致。 */
    private const val PACKAGE_NAME = "com.lmq.miccontrol"

    /** 配置文件名字，需与界面侧一致。 */
    private const val FILE_NAME = "mic_control"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_MUSIC_PACKAGE = "music_package"

    private var prefs: XSharedPreferences? = null

    /** 是否已经打印过"读取失败"的提示，避免每次录音都刷日志。 */
    @Volatile
    private var warnedFailure = false

    /** 总开关是否打开。 */
    fun isEnabled(): Boolean = readBoolean(KEY_ENABLED, false)

    /** 用户选定的音乐软件包名，没选则为空。 */
    fun musicPackage(): String? {
        val p = obtain() ?: return null
        return try {
            p.getString(KEY_MUSIC_PACKAGE, null)
        } catch (t: Throwable) {
            null
        }
    }

    private fun readBoolean(key: String, default: Boolean): Boolean {
        val p = obtain() ?: return default
        return try {
            p.getBoolean(key, default)
        } catch (t: Throwable) {
            default
        }
    }

    private fun obtain(): XSharedPreferences? {
        prefs?.let { p ->
            // 文件变了才重新加载，避免每次录音都读磁盘
            try {
                if (p.hasFileChanged()) p.reload()
            } catch (t: Throwable) {
                // 重新加载失败不致命，继续用上一次的值
            }
            return p
        }

        return try {
            val created = XSharedPreferences(PACKAGE_NAME, FILE_NAME)
            // 构造出来时是空的，必须主动加载一次，否则第一次读到的全是默认值
            created.reload()
            // 让配置文件可被本进程读取。不同系统上效果不一致，失败不影响后续读取
            runCatching { created.makeWorldReadable() }
            prefs = created
            created
        } catch (t: Throwable) {
            if (!warnedFailure) {
                warnedFailure = true
                XposedBridge.log("[MicControl] 读取设置失败，将按「已关闭」处理：${t.message}")
            }
            null
        }
    }
}
