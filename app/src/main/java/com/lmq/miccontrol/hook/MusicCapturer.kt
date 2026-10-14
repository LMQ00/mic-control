package com.lmq.miccontrol.hook

import com.lmq.miccontrol.hub.AudioHub
import com.lmq.miccontrol.hub.PcmCodec

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
     * 在当前进程挂载声音截取。
     *
     * 注意：这里**不**判断"当前应用是不是被选中的音乐软件"，那个判断留到每次
     * 真正播放声音时再做（见下方）。原因是应用启动后钩子就固定了，而你在界面上
     * 的选择随时可能变——在这里判断会导致改了选择却不生效。
     *
     * @param lpparam 当前加载的应用信息
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClassIfExists(
            "android.media.AudioTrack",
            lpparam.classLoader
        ) ?: return

        // AudioTrack.write 有多个重载（byte[]/short[]/float[]/ByteBuffer 等）
        val writeMethods = clazz.declaredMethods.filter { it.name == "write" }
        if (writeMethods.isEmpty()) return

        log("${lpparam.packageName}：发现 ${writeMethods.size} 个 write 重载，挂载截取")

        writeMethods.forEach { method ->
            // 长度与偏移的位置都必须按方法签名判定，不能想当然：
            // - write(byte[], int offset, int size, int writeMode) 的最后一个 int
            //   其实是写入模式（0 或 1），当成长度的话每次只会送出 1 个字节；
            // - 同一次调用的第 2 个参数是偏移，表示本轮声音从数组哪里开始，
            //   忽略它就会截到无关内容（播放器通常复用同一块缓冲区）。
            val lengthIndex = lengthArgIndex(method)
            if (lengthIndex < 0) {
                log("  跳过不认识的签名：${describe(method)}")
                return@forEach
            }
            // 有偏移参数的情形：首个参数是数组（直接缓冲那种没有偏移）
            val offsetIndex = if (method.parameterTypes.firstOrNull()?.isArray == true) 1 else -1

            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        // 只在"当前正是被选中的音乐软件"时才截取。这样你在界面上
                        // 改了选择，不用重启任何应用就能换一个应用来截取。
                        val role = decideRole(
                            enabled = ModulePrefs.isEnabled(),
                            selectedPackage = ModulePrefs.musicPackage(),
                            currentPackage = lpparam.packageName,
                        )
                        if (role != Role.CAPTURE_MUSIC) return

                        val data = param.args.getOrNull(0) ?: return
                        val length = (param.args.getOrNull(lengthIndex) as? Int) ?: return
                        val offset = if (offsetIndex >= 0) {
                            (param.args.getOrNull(offsetIndex) as? Int) ?: 0
                        } else {
                            0
                        }
                        sendToHub(data, offset, length)
                    }
                })
                log("  hook 成功：${describe(method)}（长度参数在第 ${lengthIndex + 1} 个）")
            } catch (t: Throwable) {
                log("  hook 失败：${describe(method)} -> ${t.message}")
            }
        }
    }

    /**
     * 判断"要写入多少个"是第几个参数（从 0 开始）。
     *
     * 系统里 write 的重载只有两类形态：
     * - `write(数组, 偏移, 长度[, 写入模式])`——长度是第 3 个参数（下标 2）
     * - `write(直接缓冲, 长度[, 写入模式])`——长度是第 2 个参数（下标 1）
     *
     * 靠第一个参数的类型区分这两类，而不是靠数第几个 int。
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
     * 把不同重载传来的数据统一转成字节流送进通道。
     *
     * 两个容易出错的地方都在这里统一处理：
     *
     * 1. **偏移量不能忽略**。`write(数组, 偏移, 长度)` 里的偏移表示"本轮声音从数组
     *    的这个位置开始"。音乐播放器通常复用同一块缓冲区；若固定从数组开头读，
     *    截到的就是无关内容（多半是上一轮残留或空白），听起来断断续续。
     * 2. **"长度"的含义随类型而变**：数组给的是元素个数，缓冲给的是字节数。
     *
     * @param data 调用方传进来的缓冲区
     * @param offset 有效数据的起始位置（元素个数或字节数，视类型而定）
     * @param length 有效数据的长度（同上）
     */
    private fun sendToHub(data: Any, offset: Int, length: Int) {
        if (length <= 0) return
        try {
            when (data) {
                // 已经是字节流，直接送
                is ByteArray -> {
                    val start = offset.coerceIn(0, data.size)
                    val n = minOf(length, data.size - start)
                    if (n > 0) AudioHub.write(data, start, n)
                }

                // 16 位样本：偏移与长度都是"多少个样本"，编码后字节数翻倍
                is ShortArray -> {
                    val start = offset.coerceIn(0, data.size)
                    val count = minOf(length, data.size - start)
                    if (count <= 0) return
                    val bytes = ByteArray(count * 2)
                    val written = PcmCodec.shortsToBytes(data, start, count, bytes)
                    if (written > 0) AudioHub.write(bytes, 0, written)
                }

                // 浮点样本：取值范围 -1..1
                is FloatArray -> {
                    val start = offset.coerceIn(0, data.size)
                    val count = minOf(length, data.size - start)
                    if (count <= 0) return
                    val bytes = ByteArray(count * 2)
                    val written = PcmCodec.floatsToBytes(data, start, count, bytes)
                    if (written > 0) AudioHub.write(bytes, 0, written)
                }

                // 直接缓冲没有偏移参数：数据从它当前的位置开始，长度按字节算
                is java.nio.ByteBuffer -> {
                    val buf = data.duplicate()
                    val n = minOf(length, buf.remaining())
                    if (n <= 0) return
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
