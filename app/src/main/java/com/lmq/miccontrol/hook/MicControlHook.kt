package com.lmq.miccontrol.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

import com.lmq.miccontrol.hub.AudioHub

/**
 * 模块入口。LSPosed 把本模块加载进目标应用进程后会调用 handleLoadPackage。
 *
 * 同一个模块文件承担两种角色，靠"当前是哪个应用"区分：
 * - 是你选中的那个音乐软件 → 截取它播放的声音，送进声音通道；
 * - 是其它应用（比如游戏）→ 拦截它读取麦克风的地方，把通道里的声音交给它。
 *
 * 注意：两个角色的实际动作都写在方法调用的当时去判断开关，
 * 而不是在加载时就定死。这样你在界面上开关一次，不需要重启游戏就能生效。
 */
class MicControlHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 自己的界面进程不处理
        if (lpparam.packageName == OWN_PACKAGE) return

        // 这里就挂上 hook。至于要不要真的替换，等实际发生录音/播放时再查开关，
        // 这样开关的作用是即时的，不受"应用是什么时候启动的"影响。
        val selected = SettingsReader.readMusicPackage()

        if (!selected.isNullOrBlank() && lpparam.packageName == selected) {
            log("${lpparam.packageName} 是选定的音乐应用，挂载声音截取")
            MusicCapturer.install(lpparam, selected)
        } else if (!selected.isNullOrBlank()) {
            log("${lpparam.packageName} 是目标应用，挂载录音替换")
            installMicReplacement(lpparam)
        } else {
            log("尚未指定音乐应用，${lpparam.packageName} 不做处理")
        }
    }

    /**
     * 拦截 AudioRecord.read，把声音通道里的内容交给调用方。
     *
     * 这是所有 Java 层录音的必经之路：不管上层用哪个语音 SDK，
     * 最终读麦克风都会走到这里。
     */
    private fun installMicReplacement(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClassIfExists(
            "android.media.AudioRecord",
            lpparam.classLoader
        ) ?: run {
            log("找不到 AudioRecord 类")
            return
        }

        val readMethods = clazz.declaredMethods.filter { it.name == "read" }
        if (readMethods.isEmpty()) {
            log("AudioRecord 上没有 read 方法")
            return
        }

        log("发现 ${readMethods.size} 个 read 重载")

        readMethods.forEach { method ->
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        // 每次录音都实时查开关：关掉开关立刻恢复真实麦克风，无需重启应用
                        if (!SwitchState.isEnabled()) return

                        val readCount = (param.result as? Int) ?: return
                        if (readCount <= 0) return

                        val buffer = param.args.firstOrNull() ?: return
                        fillFromHub(buffer, readCount)
                    }
                })
                log("  hook 成功：${describe(method)}")
            } catch (t: Throwable) {
                log("  hook 失败：${describe(method)} -> ${t.message}")
            }
        }
    }

    /**
     * 从声音通道取数据填进调用方的缓冲区。
     *
     * 通道里暂时没有数据时（比如音乐还没开始放），保留原有内容不动，
     * 让目标应用继续按原样工作，而不是让录音失败——这样即使这套机制没起作用，
     * 游戏语音也还是能正常用。
     */
    private fun fillFromHub(buffer: Any, count: Int) {
        try {
            when (buffer) {
                is ByteArray -> {
                    val n = minOf(count, buffer.size)
                    // 返回 0 表示暂时没有数据，此时不动缓冲区
                    AudioHub.read(buffer, n, READ_TIMEOUT_MS)
                }

                is ShortArray -> {
                    val n = minOf(count, buffer.size)
                    val bytes = ByteArray(n * 2)
                    val got = AudioHub.read(bytes, bytes.size, READ_TIMEOUT_MS)
                    if (got <= 0) return
                    val samples = got / 2
                    for (i in 0 until samples) {
                        val lo = bytes[i * 2].toInt() and 0xFF
                        val hi = bytes[i * 2 + 1].toInt()
                        buffer[i] = ((hi shl 8) or lo).toShort()
                    }
                }

                is FloatArray -> {
                    val n = minOf(count, buffer.size)
                    val bytes = ByteArray(n * 2)
                    val got = AudioHub.read(bytes, bytes.size, READ_TIMEOUT_MS)
                    if (got <= 0) return
                    val samples = got / 2
                    for (i in 0 until samples) {
                        val lo = bytes[i * 2].toInt() and 0xFF
                        val hi = bytes[i * 2 + 1].toInt()
                        val s = ((hi shl 8) or lo).toShort()
                        buffer[i] = s.toFloat() / Short.MAX_VALUE
                    }
                }

                is java.nio.ByteBuffer -> {
                    val n = minOf(count, buffer.remaining())
                    val bytes = ByteArray(n)
                    val got = AudioHub.read(bytes, n, READ_TIMEOUT_MS)
                    if (got <= 0) return
                    buffer.put(bytes, 0, got)
                }
            }
        } catch (t: Throwable) {
            // 替换失败时保持原数据，不能让目标应用的录音崩掉
        }
    }

    private fun describe(method: java.lang.reflect.Method): String =
        "${method.name}(${method.parameterTypes.joinToString(", ") { it.simpleName }})"

    private fun log(msg: String) {
        XposedBridge.log("[MicControl] $msg")
    }

    companion object {
        private const val OWN_PACKAGE = "com.lmq.miccontrol"

        /** 等待声音数据的最长时间。取值偏小，避免拖慢目标应用的录音节奏。 */
        private const val READ_TIMEOUT_MS = 50L
    }
}
