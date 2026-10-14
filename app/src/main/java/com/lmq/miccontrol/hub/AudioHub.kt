package com.lmq.miccontrol.hub

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress

import java.io.InputStream
import java.io.OutputStream

/**
 * 音乐进程与游戏进程之间的声音通道。
 *
 * 两个应用是两个独立的进程，内存不能直接共享，所以中间架一条本地套接字：
 * 音乐那边把截取到的声音写进来，游戏那边从这里读出去。
 *
 * 这里做成"用的时候自动连"，并且连不上会自动重试。原因是两个进程谁先启动、
 * 谁先开始放歌/录音完全不确定，写死启动顺序很容易出现"一边在等、另一边还没来"的死局。
 */
object AudioHub {

    /** 通道的名字，两个进程必须一致才能对接上。 */
    private const val SOCKET_NAME = "com.lmq.miccontrol.audio"

    private const val BUFFER_SIZE = 64 * 1024

    /** 两次重连之间至少间隔这么久，避免每读一次就尝试一次连接。 */
    private const val RETRY_INTERVAL_MS = 1000L

    private val lock = Object()

    private var server: LocalServerSocket? = null
    private var socket: LocalSocket? = null
    private var output: OutputStream? = null

    @Volatile
    private var connected = false

    private var lastAttempt = 0L

    /** 消费者侧的缓冲区：搬运线程把通道里的数据取到这里，录音线程只从这里读，不会卡住。 */
    private val incoming = AudioStream(256 * 1024)

    private var pumping = false

    val isConnected: Boolean get() = connected

    /**
     * 把截取到的声音写进通道（音乐侧调用）。
     *
     * 未能连上时不影响音乐播放：这段声音直接丢弃。声音是实时的，
     * 卡住音乐比丢一小段严重得多。
     */
    fun write(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        if (!connected && !tryConnect()) return
        try {
            output?.write(data, offset, length)
            output?.flush()
        } catch (t: Throwable) {
            // 对端断开：标记失效，下次调用会重新尝试连接
            connected = false
        }
    }

    /**
     * 从通道取声音（游戏侧调用）。
     *
     * @return 实际读到的字节数；0 表示暂时没有数据，调用方应保持原样
     */
    fun read(destination: ByteArray, length: Int, timeoutMs: Long): Int {
        if (length <= 0) return 0
        if (!connected) tryConnect()
        if (!connected) return 0
        return incoming.read(destination, length, timeoutMs)
    }

    /**
     * 建立连接。谁先到谁当服务端，另一方直接连过去。
     *
     * @return 是否已连接（含此前已连上的情况）
     */
    private fun tryConnect(): Boolean {
        synchronized(lock) {
            if (connected) return true

            // 失败太频繁就等一会儿再试，避免空转
            val now = System.currentTimeMillis()
            if (now - lastAttempt < RETRY_INTERVAL_MS) return false
            lastAttempt = now

            socket = acceptOrConnect() ?: return false

            return try {
                socket!!.setReceiveBufferSize(BUFFER_SIZE)
                socket!!.setSendBufferSize(BUFFER_SIZE)
                val out = socket!!.outputStream
                output = out
                connected = true
                startPump(socket!!.inputStream)
                true
            } catch (t: Throwable) {
                runCatching { socket?.close() }
                socket = null
                output = null
                false
            }
        }
    }

    /** 先尝试当服务端，不行就连过去。 */
    private fun acceptOrConnect(): LocalSocket? {
        // 复用已创建的服务端，避免重复占用同一个名字
        if (server == null) {
            server = try {
                LocalServerSocket(SOCKET_NAME)
            } catch (t: Throwable) {
                null
            }
        }

        server?.let { srv ->
            return try {
                srv.accept()
            } catch (t: Throwable) {
                null
            }
        }

        return try {
            LocalSocket().apply {
                connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 后台搬运：把通道里的数据持续取到缓冲区。 */
    private fun startPump(input: InputStream) {
        if (pumping) return
        pumping = true

        Thread({
            val buf = ByteArray(16 * 1024)
            try {
                while (connected) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) incoming.write(buf, n)
                }
            } catch (t: Throwable) {
                // 通道断开属正常情况（比如对端退出了）
            } finally {
                synchronized(lock) {
                    connected = false
                    pumping = false
                }
            }
        }, "mic-control-pump").apply {
            isDaemon = true
            start()
        }
    }

    /** 关闭通道并释放资源。 */
    fun shutdown() {
        synchronized(lock) {
            connected = false
            pumping = false
            runCatching { output?.close() }
            runCatching { socket?.close() }
            runCatching { server?.close() }
            output = null
            socket = null
            server = null
        }
    }
}
