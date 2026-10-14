package com.lmq.miccontrol.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口。LSPosed 加载本模块到目标进程后会调用 handleLoadPackage。
 *
 * 里程碑 1 的目标：只验证「能不能在目标 App 的录音调用上做手脚」这一件事，
 * 先不接真实音乐数据，用一段可辨识的测试音确认链路通不通。
 */
class MicControlHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 里程碑 1 只处理目标应用（由 LSPosed 作用域决定），自身进程不处理，
        // 避免模块界面自己被 hook 造成困扰。
        if (lpparam.packageName == HOOK_OWN_PACKAGE) return

        log("模块已加载到 ${lpparam.packageName}，开始 hook AudioRecord")

        hookAudioRecordRead(lpparam)
    }

    /**
     * hook AudioRecord.read 系列方法。
     *
     * 这是所有 Java 层录音的必经之路：无论上层用 MediaRecorder 还是直接 new
     * AudioRecord，最终读数据都会走到这几个重载上。替换掉它的返回值，
     * 上层拿到的就是我们的数据。
     */
    private fun hookAudioRecordRead(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClassIfExists(
            "android.media.AudioRecord",
            lpparam.classLoader
        ) ?: run {
            log("找不到 AudioRecord 类，本应用可能不使用 Java 层录音")
            return
        }

        // AudioRecord 的 read 有多个重载：byte[]/short[]/float[]/ByteBuffer 等。
        // 这里逐个尝试 hook，能找到几个算几个。
        val readMethods = clazz.declaredMethods.filter { it.name == "read" }

        if (readMethods.isEmpty()) {
            log("AudioRecord 上没有找到 read 方法")
            return
        }

        log("发现 ${readMethods.size} 个 read 重载，逐个 hook")

        readMethods.forEach { method ->
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val result = param.result
                        // read 返回读到的字节/样本数，小于等于 0 表示没读到，
                        // 这种调用不处理，避免破坏调用方的错误处理逻辑。
                        val readCount = (result as? Int) ?: return
                        if (readCount <= 0) return

                        val buffer = param.args.firstOrNull() ?: return
                        TestTone.fill(buffer, readCount)

                        log("已替换一次录音数据：${method.name}，长度 $readCount")
                    }
                })
                log("  hook 成功：${describe(method)}")
            } catch (t: Throwable) {
                log("  hook 失败：${describe(method)} -> ${t.message}")
            }
        }
    }

    /** 生成便于阅读的方法签名，用于日志。 */
    private fun describe(method: java.lang.reflect.Method): String =
        "${method.name}(${method.parameterTypes.joinToString(", ") { it.simpleName }})"

    private fun log(msg: String) {
        XposedBridge.log("[$TAG] $msg")
    }

    companion object {
        private const val TAG = "MicControl"
        private const val HOOK_OWN_PACKAGE = "com.lmq.miccontrol"
    }
}
