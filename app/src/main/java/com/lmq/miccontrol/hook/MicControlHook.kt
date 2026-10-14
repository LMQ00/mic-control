package com.lmq.miccontrol.hook

import android.content.Context

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口。LSPosed 把本模块加载进目标应用进程后会调用 handleLoadPackage。
 *
 * 里程碑 1 的目标：验证「能不能在目标 App 的录音调用上做手脚」。
 * 这一阶段先用一段可辨识的测试音代替真实音乐，确认链路通不通；
 * 只要目标 App 的语音那头听到这个音，就说明 hook 生效、方向正确。
 */
class MicControlHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 不处理自己的进程：模块自身的界面不需要被 hook，
        // 处理了反而可能干扰界面读取配置。
        if (lpparam.packageName == OWN_PACKAGE) return

        log("模块已加载到 ${lpparam.packageName}，准备 hook 录音调用")

        hookAudioRecordRead(lpparam)
    }

    /**
     * hook AudioRecord.read 系列方法。
     *
     * 这是 Java 层录音的必经之路：不管上层用 MediaRecorder、AudioRecord 还是
     * 各种语音 SDK，最终读数据都会走到 read 上。替换掉它的输出，
     * 上层拿到的就是我们的数据。
     */
    private fun hookAudioRecordRead(lpparam: XC_LoadPackage.LoadPackageParam) {
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
                        // 开关关闭时立刻放行，不做任何替换。
                        // 这一段是「关掉开关立即恢复真实麦克风」的实现所在：
                        // 每次录音都实时查一次开关，因此无需重启目标应用。
                        if (!SwitchState.isEnabled()) return

                        val readCount = (param.result as? Int) ?: return
                        if (readCount <= 0) return

                        val buffer = param.args.firstOrNull() ?: return
                        TestTone.fill(buffer, readCount)
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
        private const val OWN_PACKAGE = "com.lmq.miccontrol"
    }
}
