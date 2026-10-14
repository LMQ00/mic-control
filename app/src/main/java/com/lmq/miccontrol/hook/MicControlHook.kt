package com.lmq.miccontrol.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

import com.lmq.miccontrol.hub.AudioHub
import com.lmq.miccontrol.hub.PcmCodec

/**
 * 模块入口。LSPosed 把本模块加载进目标应用进程后会调用 handleLoadPackage。
 *
 * 同一个模块承担两种角色，具体扮演哪个在**运行时**判定：
 * - 当这个应用是你选中的音乐软件时 → 截取它播放的声音，送进声音通道；
 * - 当这个应用是别的应用（比如游戏）时 → 拦截它读取麦克风的地方，把音乐交给它。
 *
 * ## 为什么一律在运行时判断
 *
 * 应用一旦启动，挂上的钩子就固定了，之后没法追加。如果在加载时按"此刻选中的
 * 音乐软件是谁"来决定挂哪个钩子，那么你之后在界面上改了选择，已经启动的应用
 * 会一直沿用旧角色——要么截错了应用，要么根本不截。所以两个钩子都挂上，
 * 每次都重新看一遍开关和当前选择，这样"改了立刻生效"才成立。
 */
class MicControlHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 自己的界面进程不处理
        if (lpparam.packageName == OWN_PACKAGE) return

        log("${lpparam.packageName} 已挂载，角色在运行时判定")

        MusicCapturer.install(lpparam)
        installMicReplacement(lpparam)
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
        ) ?: return

        val readMethods = clazz.declaredMethods.filter { it.name == "read" }
        if (readMethods.isEmpty()) return

        log("${lpparam.packageName}：发现 ${readMethods.size} 个 read 重载，挂载替换")

        readMethods.forEach { method ->
            val lengthIndex = lengthArgIndex(method)
            if (lengthIndex < 0) return@forEach

            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        // 运行时判断，缺一不可：
                        // 1. 总开关必须打开；
                        // 2. 当前应用不能是被选中的音乐软件——音乐软件自己也可能录音，
                        //    它是声音来源方，不该被替换。
                        if (!ModulePrefs.isEnabled()) return
                        if (ModulePrefs.musicPackage() == lpparam.packageName) return

                        val readCount = (param.result as? Int) ?: return
                        if (readCount <= 0) return

                        val buffer = param.args.getOrNull(0) ?: return
                        val offset = (param.args.getOrNull(1) as? Int) ?: 0
                        fillFromHub(buffer, offset, readCount)
                    }
                })
                log("  hook 成功：${describe(method)}")
            } catch (t: Throwable) {
                log("  hook 失败：${describe(method)} -> ${t.message}")
            }
        }
    }

    /**
     * 判断"要读/写多少个"是第几个参数。
     *
     * 系统里 read 和 write 的重载只有两类形态：
     * - `(数组, 偏移, 长度[, 模式])`——长度是第 3 个参数（下标 2）
     * - `(直接缓冲, 长度[, 模式])`——长度是第 2 个参数（下标 1）
     *
     * 必须按签名判断，不能想当然取"最后一个整数"：例如
     * `write(byte[], int, int, int writeMode)` 的最后一个整数是写入模式（0 或 1），
     * 当成长度的话每次就只送出 1 个字节。
     *
     * @return 参数下标；签名不认识时返回 -1
     */
    private fun lengthArgIndex(method: java.lang.reflect.Method): Int {
        val types = method.parameterTypes
        val first = types.firstOrNull() ?: return -1
        return when {
            java.nio.ByteBuffer::class.java.isAssignableFrom(first) -> 1
            first.isArray -> 2
            else -> -1
        }
    }

    /**
     * 从声音通道取数据，填进调用方提供的缓冲区。
     *
     * @param buffer 调用方的缓冲区，可能是数组或直接缓冲
     * @param offset 数据应写入缓冲区的起始位置（调用方可能只用缓冲区的一部分）
     * @param count 要填多少个（字节数组是字节数，样本数组是样本数）
     *
     * 通道里暂时没有数据时（比如音乐还没开始放），保留原有内容不动，
     * 让目标应用继续按原样工作，而不是让录音失败——这样即使这套机制没起作用，
     * 游戏语音也还是能正常用。
     */
    private fun fillFromHub(buffer: Any, offset: Int, count: Int) {
        try {
            when (buffer) {
                is ByteArray -> {
                    if (offset < 0 || offset >= buffer.size) return
                    val n = minOf(count, buffer.size - offset)
                    if (n <= 0) return
                    // 取数组的一段视图，让数据直接落到正确位置
                    val view = buffer.copyOfRange(offset, offset + n)
                    val got = AudioHub.read(view, n, READ_TIMEOUT_MS)
                    if (got <= 0) return
                    System.arraycopy(view, 0, buffer, offset, got)
                }

                is ShortArray -> {
                    if (offset < 0 || offset >= buffer.size) return
                    val n = minOf(count, buffer.size - offset)
                    if (n <= 0) return
                    val bytes = ByteArray(n * 2)
                    val got = AudioHub.read(bytes, bytes.size, READ_TIMEOUT_MS)
                    if (got <= 0) return
                    PcmCodec.bytesToShorts(bytes, got, buffer, offset)
                }

                is FloatArray -> {
                    if (offset < 0 || offset >= buffer.size) return
                    val n = minOf(count, buffer.size - offset)
                    if (n <= 0) return
                    val bytes = ByteArray(n * 2)
                    val got = AudioHub.read(bytes, bytes.size, READ_TIMEOUT_MS)
                    if (got <= 0) return
                    PcmCodec.bytesToFloats(bytes, got, buffer, offset)
                }

                is java.nio.ByteBuffer -> {
                    // 直接缓冲：录音读取已在返回前推进了它的位置，因此必须
                    // 用专门的写法覆盖"刚读入的那一段"，不能往当前位置追加，
                    // 否则数据落到错误偏移、替换等于没做。
                    val n = minOf(count, buffer.capacity())
                    if (n <= 0) return
                    val bytes = ByteArray(n)
                    val got = AudioHub.read(bytes, n, READ_TIMEOUT_MS)
                    if (got <= 0) return
                    PcmCodec.fillReadBuffer(buffer, count, bytes)
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
