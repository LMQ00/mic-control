package com.lmq.miccontrol

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把音乐送进麦克风的那套东西。
 *
 * 手上三个文件：
 * - `mcinject`：往系统声音进程里装钩子的工具；
 * - `libmcpayload.so`：钩子本体，装进系统声音进程后由它抄音乐、换麦克风数据；
 * - 本类：把这两个文件放到对的位置，再按开关状态调工具。
 *
 * 开关打开和关闭都不重启系统声音进程，声音不会中断。
 */
class Injector(private val context: Context) {

    /** 工具与载荷在系统声音进程能读到的位置。该目录只有 root 能进。 */
    private val stageDir = File("/data/misc/audioserver")

    private val stagePayload get() = File(stageDir, "libmcpayload.so")
    private val stageInject get() = File(context.filesDir, "mcinject")

    /** 打开：把目标应用的声音接到麦克风入口。 */
    suspend fun enable(targetUid: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            prepare()
            val out = run("on", stagePayload.absolutePath, targetUid.toString())
            require(out.contains("已开启")) { out.ifBlank { "注入失败" } }
            out.trim()
        }
    }

    /** 关闭：停止替换，麦克风恢复原样。 */
    suspend fun disable(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!stagePayload.exists()) return@runCatching "未启用"
            val out = run("off", stagePayload.absolutePath)
            out.trim()
        }
    }

    /** 查询当前状态，用于界面展示。 */
    suspend fun status(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!stagePayload.exists()) return@runCatching "未启用"
            run("status", stagePayload.absolutePath).trim()
        }
    }

    /**
     * 把工具和载荷准备到可用的位置。
     *
     * 载荷必须放在系统声音进程读得到、且带可加载标记的目录，还要给它打上
     * 系统库的标签，否则该进程会拒绝加载。工具本身放在应用私有目录即可，
     * root 能直接执行。
     */
    private fun prepare() {
        stageDir.mkdirs()

        val payloadOut = File(context.filesDir, "libmcpayload.so")
        if (!payloadOut.exists() || payloadOut.length() == 0L) {
            context.assets.open("libmcpayload.so").use { input ->
                payloadOut.outputStream().use { input.copyTo(it) }
            }
        }
        if (!stageInject.exists() || stageInject.length() == 0L) {
            context.assets.open("mcinject").use { input ->
                stageInject.outputStream().use { input.copyTo(it) }
            }
        }

        // 复制到目标目录并设置权限与标签。这几步都要 root。
        sh(
            "cp ${payloadOut.absolutePath} ${stagePayload.absolutePath}",
            "chmod 755 ${stagePayload.absolutePath}",
            "chmod 755 ${stageInject.absolutePath}",
            "chcon u:object_r:system_lib_file:s0 ${stagePayload.absolutePath}",
        )
    }

    private fun run(vararg args: String): String {
        val cmd = (listOf(stageInject.absolutePath) + args).joinToString(" ")
        return sh(cmd)
    }

    private fun sh(vararg commands: String): String {
        val script = commands.joinToString(" ; ")
        val proc = ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out
    }
}
