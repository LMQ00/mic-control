package com.lmq.miccontrol.hook

import de.robv.android.xposed.XSharedPreferences

/**
 * hook 进程里读取开关状态。
 *
 * 界面进程和 hook 进程是两个不同的进程，不能共享内存里的变量，
 * 所以开关状态通过一份共享的配置来传递：界面负责写，这里负责读。
 *
 * 关键点是**每次用到时都重新读一次**，而不是启动时读一次就缓存住。
 * 这样你在界面上关掉开关，目标应用下一次录音就会立刻恢复真实麦克风，
 * 不需要重启游戏、也不需要重启手机。
 */
object SwitchState {

    /** 写配置的应用的包名，必须和界面所在的应用一致。 */
    private const val PACKAGE_NAME = "com.lmq.miccontrol"

    /** 配置文件名字，和界面侧用的是同一个。 */
    private const val FILE_NAME = "mic_control"

    /** 开关对应的配置项名字。 */
    private const val KEY_ENABLED = "enabled"

    private var prefs: XSharedPreferences? = null

    /**
     * 当前开关是否打开。
     *
     * 读取失败时一律返回 false（即不替换录音数据）。这是刻意的选择：
     * 读不到配置时宁可让麦克风保持正常工作，也不要让用户处于
     * 「以为关了实际没关」的状态。
     */
    fun isEnabled(): Boolean {
        return try {
            val p = obtain() ?: return false
            // 只有配置文件变了才重新加载，避免每次录音都读磁盘
            if (p.hasFileChanged()) {
                p.reload()
            }
            p.getBoolean(KEY_ENABLED, false)
        } catch (t: Throwable) {
            false
        }
    }

    private fun obtain(): XSharedPreferences? {
        prefs?.let { return it }
        return try {
            XSharedPreferences(PACKAGE_NAME, FILE_NAME).also {
                // 尝试让配置文件可被本进程读取。不同系统版本上这一步的
                // 效果不一致，失败也不影响后续读取：读不到会走 isEnabled()
                // 的兜底分支，麦克风保持正常工作。
                runCatching { it.makeWorldReadable() }
                prefs = it
            }
        } catch (t: Throwable) {
            null
        }
    }
}
