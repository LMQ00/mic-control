package com.lmq.miccontrol

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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

    private val stageInject get() = File(context.filesDir, "mcinject")

    /** 打开：把目标应用的声音接到麦克风入口。 */
    suspend fun enable(targetUid: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = prepare()
            val out = run("on", payload.absolutePath, targetUid.toString())
            require(out.contains("已开启")) { out.ifBlank { "注入失败" } }
            out.trim()
        }
    }

    /** 关闭：停止替换，麦克风恢复原样。 */
    suspend fun disable(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            tool()
            // 卸载不认载荷路径，只按声音进程里实际映射的那几份来卸
            run("off", "-").trim()
        }
    }

    /** 查询当前状态，用于界面展示。 */
    suspend fun status(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            tool()
            run("status", "-").trim()
        }
    }

    /**
     * 把工具和载荷准备到可用的位置，返回系统声音进程该用的载荷路径。
     *
     * 载荷按内容命名，形如 `libmcpayload-3f8a1c2d4e5f.so`。内容一变就是另一个文件，
     * 声音进程里已经映射的那份不会被覆盖，工具按文件名取到的符号才对得上。
     */
    private fun prepare(): File {
        val payloadBytes = context.assets.open("libmcpayload.so").use { it.readBytes() }
        val tag = MessageDigest.getInstance("SHA-256").digest(payloadBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(12)
        val name = "libmcpayload-$tag.so"

        val payloadOut = File(context.filesDir, name)
        if (!payloadOut.exists() || payloadOut.length() != payloadBytes.size.toLong()) {
            payloadOut.writeBytes(payloadBytes)
        }

        stageTool()

        // 载荷只在目标位置还没有这份内容时复制，先写临时名再改名，不留半截文件。
        // 复制这几步都要 root。
        val payload = File(stageDir, name)
        sh(
            "mkdir -p ${stageDir.absolutePath}",
            "if [ ! -f ${payload.absolutePath} ]; then " +
                "cp ${payloadOut.absolutePath} ${payload.absolutePath}.$$ && " +
                "mv ${payload.absolutePath}.$$ ${payload.absolutePath} || exit 1; fi",
            "chmod 755 ${payload.absolutePath}",
            "chcon u:object_r:system_lib_file:s0 ${payload.absolutePath}",
        )
        return payload
    }

    /** 关闭和查询用：刷新失败就用已经存在的那份，让「关掉」这个兜底动作还能走。 */
    private fun tool(): File {
        runCatching { stageTool() }
        if (!stageInject.exists()) throw IllegalStateException("工具还没准备好")
        return stageInject
    }

    /** 工具每次都用安装包里的这份：文件目录跨版本保留，不刷新就会一直跑旧工具。 */
    private fun stageTool() {
        val tmp = File.createTempFile("mcinject", ".new", context.filesDir)
        try {
            context.assets.open("mcinject").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            tmp.setReadable(true, false)
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(stageInject)) error("工具写入失败")
        } finally {
            tmp.delete()
        }
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
        val code = proc.waitFor()
        if (code != 0) throw IllegalStateException(out.trim().ifBlank { "执行失败：$code" })
        return out
    }
}
