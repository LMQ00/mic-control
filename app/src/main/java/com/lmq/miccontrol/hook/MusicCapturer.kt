package com.lmq.miccontrol.hook

import com.lmq.miccontrol.hub.AudioHub

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 从音乐应用里截取它正在播放的声音。
 *
 * 原理：音乐应用把声音交给系统播放时，都会经过 AudioTrack 的 write 方法。
 * 我们在这里顺手复制一份，通过声音通道送出去，而原有的播放流程完全不受影响
 * ——所以你自己听歌一切照旧。
 *
 * 只截取你在界面上选中的那一个音乐应用，这是「指定音乐软件」的实现所在：
 * 不限定来源的话，游戏自己的音效也会被送回去，队友就会听到回音。
 */
object MusicCapturer {

    /**
     * 尝试对当前进程做声音截取。
     *
     * @param lpparam 当前加载的应用信息
     * @param selectedPackage 用户在界面上选中的音乐应用包名
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam, selectedPackage: String?) {
        // 只处理被选中的那个应用。没选、或不是它，都不做任何事。
        if (selectedPackage.isNullOrBlank()) {
            log("尚未指定音乐应用，跳过声音截取")
            return
        }
        if (lpparam.packageName != selectedPackage) return

        val clazz = XposedHelpers.findClassIfExists(
            "android.media.AudioTrack",
            lpparam.classLoader
        ) ?: run {
            log("找不到 AudioTrack 类")
            return
        }

        // AudioTrack.write 有多个重载（byte[]/short[]/float[]/ByteBuffer 等）
        val writeMethods = clazz.declaredMethods.filter { it.name == "write" }
        if (writeMethods.isEmpty()) {
            log("AudioTrack 上没有 write 方法")
            return
        }

        log("正在截取 ${lpparam.packageName} 的声音，发现 ${writeMethods.size} 个 write 重载")

        writeMethods.forEach { method ->
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        // 开关关闭时立刻停止截取，音乐照常播放
                        if (!SwitchState.isEnabled()) return

                        val data = param.args.firstOrNull() ?: return
                        // write 的有效长度参数位置随重载而不同：
                        // write(byte[], int offset, int length) 与 write(byte[], int, int, int) 等
                        val length = extractLength(param.args) ?: return
                        sendToHub(data, length)
                    }
                })
                log("  hook 成功：${describe(method)}")
            } catch (t: Throwable) {
                log("  hook 失败：${describe(method)} -> ${t.message}")
            }
        }
    }

    /**
     * 从参数里找出"要写入多少个"这个值。
     *
     * 不同重载的签名不一样，共同点是：最后一个 int 参数就是有效长度。
     * 单独抽出来是为了避免依赖具体的参数下标。
     */
    private fun extractLength(args: Array<Any?>): Int? {
        for (i in args.indices.reversed()) {
            val v = args[i]
            if (v is Int) return v
        }
        return null
    }

    /** 把不同重载传来的数据统一转成字节流送进通道。 */
    private fun sendToHub(data: Any, length: Int) {
        if (length <= 0) return
        try {
            when (data) {
                is ByteArray -> {
                    val n = minOf(length, data.size)
                    AudioHub.write(data, 0, n)
                }

                is ShortArray -> {
                    // 16 位样本，按小端序转成字节
                    val n = minOf(length, data.size)
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        val v = data[i].toInt()
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    AudioHub.write(bytes, 0, bytes.size)
                }

                is FloatArray -> {
                    // 浮点样本范围是 -1..1，转成 16 位整数
                    val n = minOf(length, data.size)
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        val s = (data[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                        bytes[i * 2] = (s and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    AudioHub.write(bytes, 0, bytes.size)
                }

                is java.nio.ByteBuffer -> {
                    val buf = data.duplicate()
                    val n = minOf(length, buf.remaining())
                    val bytes = ByteArray(n)
                    buf.get(bytes)
                    AudioHub.write(bytes, 0, n)
                }
            }
        } catch (t: Throwable) {
            // 截取失败不能影响音乐正常播放，静默忽略
        }
    }

    private fun describe(method: java.lang.reflect.Method): String =
        "${method.name}(${method.parameterTypes.joinToString(", ") { it.simpleName }})"

    private fun log(msg: String) {
        XposedBridge.log("[MicControl] $msg")
    }
}
