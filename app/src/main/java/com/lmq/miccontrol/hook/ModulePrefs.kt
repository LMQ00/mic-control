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
 * 2. **读不到就当作关闭**。底层读取依赖框架提供的通道，任何一步出问题都可能
 *    拿到空值；此时一律按"开关是关的"处理，让你不会处于"以为关了、其实没关"
 *    的状态。
 *
 * 关于读取时机：框架的读取方法内部会等待数据加载完成，因此不存在"刚创建就
 * 读到空值"的问题，不需要额外处理。首次读取会主动触发一次加载。
 */
object ModulePrefs {

    /** 界面所在应用的包名，必须与界面应用一致。 */
    private const val PACKAGE_NAME = "com.lmq.miccontrol"

    /** 配置文件名字，需与界面侧一致。 */
    private const val FILE_NAME = "mic_control"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_MUSIC_PACKAGE = "music_package"

    /**
     * 多久去确认一次设置有没有改动。
     *
     * 250 毫秒意味着：你在界面上关掉开关，正在进行的录音最迟四分之一秒后就
     * 恢复真实麦克风。人感觉不到这点延迟，而音频路径上的开销降到了很低。
     */
    private const val RECHECK_INTERVAL_MS = 250L

    private var prefs: XSharedPreferences? = null

    /**
     * 上次去问"文件变了没"的时间。
     *
     * 为什么要限流：检查变化这件事在启用安全策略的系统上开销不小，而录音读取
     * 是每几毫秒一次的高频操作。若每次都去问，等于把大量开销压进音频路径，
     * 听起来就是卡顿。这里限制为最多每 [RECHECK_INTERVAL_MS] 毫秒问一次——
     * 对使用者而言，改完开关几乎立刻生效，感觉不到延迟。
     */
    @Volatile
    private var lastCheckAt = 0L

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
            refreshIfDue(p)
            return p
        }

        return try {
            // 配置文件由界面进程写入，读取由框架提供的通道完成，不依赖文件系统
            // 权限，所以这里不需要（也无法）去改文件权限——那个操作只有文件
            // 所有者才做得到，在别人的进程里调用注定失败。
            XSharedPreferences(PACKAGE_NAME, FILE_NAME).also { prefs = it }
        } catch (t: Throwable) {
            if (!warnedFailure) {
                warnedFailure = true
                XposedBridge.log("[MicControl] 读取设置失败，将按「已关闭」处理：${t.message}")
            }
            null
        }
    }

    /** 到点了才去检查文件有没有变，避免高频调用把开销压进音频路径。 */
    private fun refreshIfDue(p: XSharedPreferences) {
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < RECHECK_INTERVAL_MS) return
        lastCheckAt = now
        try {
            if (p.hasFileChanged()) p.reload()
        } catch (t: Throwable) {
            // 重新加载失败不致命，继续用上一次的值
        }
    }
}
