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
 * ## 为什么全部收发都交给后台线程
 *
 * 建立连接这件事本身是阻塞的（要等对方出现），而调用方分别在"音乐正在写声音"
 * 和"游戏正在读麦克风"这两个关键时刻使用本对象。如果让它们直接参与连接过程，
 * 一旦对方还没起来，音乐播放或游戏录音就会被卡住——这是绝对不能接受的。
 *
 * 所以这里的规矩是：
 * - 对外的 [write] 和 [read] 只跟本地缓冲打交道，立刻返回，永不阻塞；
 * - 真正的连接、收发全部由一个后台线程负责，连不上就等一会儿再试。
 */
object AudioHub {

    /** 通道的名字，两个进程必须一致才能对接上。 */
    private const val SOCKET_NAME = "com.lmq.miccontrol.audio"

    private const val SOCKET_BUFFER_SIZE = 64 * 1024

    /** 连接失败后等待多久再试，避免空转。 */
    private const val RETRY_INTERVAL_MS = 500L

    /** 发送线程每次从本地缓冲取数据的等待上限。 */
    private const val SEND_POLL_TIMEOUT_MS = 100L

    private val lock = Object()

    @Volatile
    private var connected = false

    @Volatile
    private var workerStarted = false

    /** 待发送的声音：生产者放进来，发送线程取走。 */
    private val outgoing = AudioStream(128 * 1024)

    /** 已收到的声音：接收线程放进来，消费者取走。 */
    private val incoming = AudioStream(256 * 1024)

    val isConnected: Boolean get() = connected

    /**
     * 把截取到的声音交给通道（音乐侧调用）。
     *
     * 只写本地缓冲，立刻返回。缓冲满了会丢弃最旧的数据——声音是实时的，
     * 宁可丢掉过期的一小段，也绝不能让音乐播放卡住。
     */
    fun write(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        ensureWorker()
        outgoing.write(data, offset, length)
    }

    /**
     * 取一段声音（游戏侧调用）。
     *
     * 只从本地缓冲取，最多等待 [timeoutMs] 毫秒。
     *
     * @return 实际读到的字节数；0 表示暂时没有数据，调用方应保持原样
     */
    fun read(destination: ByteArray, length: Int, timeoutMs: Long): Int {
        if (length <= 0) return 0
        ensureWorker()
        return incoming.read(destination, length, timeoutMs)
    }

    /** 确保后台线程已启动，多次调用只启动一个。 */
    private fun ensureWorker() {
        if (workerStarted) return
        synchronized(lock) {
            if (workerStarted) return
            workerStarted = true
            Thread({ workerLoop() }, "mic-control-hub").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * 后台线程主循环：连接 → 收数据 → 发数据，断开后重新来过。
     *
     * 整个生命周期都在这个线程里，所以这里的阻塞调用不会影响到任何应用。
     */
    private fun workerLoop() {
        while (true) {
            var session: Session? = null
            try {
                session = openSession()
                if (session == null) {
                    sleepQuietly(RETRY_INTERVAL_MS)
                    continue
                }

                connected = true
                startReceiver(session.input)
                sendLoop(session.output)
            } catch (t: Throwable) {
                // 连接中断属正常情况，走下面的重试
            } finally {
                connected = false
                runCatching { session?.close() }
                sleepQuietly(RETRY_INTERVAL_MS)
            }
        }
    }

    /** 一次连接会话所持有的资源。 */
    private class Session(
        val server: LocalServerSocket?,
        val socket: LocalSocket,
    ) {
        val input: InputStream = socket.inputStream
        val output: OutputStream = socket.outputStream

        fun close() {
            runCatching { socket.close() }
            runCatching { server?.close() }
        }
    }

    /**
     * 建立一次连接。谁先到谁当服务端，另一方直接连过去，
     * 因此不要求两个应用有固定的启动顺序。
     */
    private fun openSession(): Session? {
        // 先试着自己当服务端。名字被占用说明对方已经在等了，那就连过去。
        val server = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (t: Throwable) {
            null
        }

        if (server != null) {
            return try {
                // 这里会一直等到对方出现为止。这在后台线程上，不影响任何人。
                val accepted = server.accept()
                accepted.setReceiveBufferSize(SOCKET_BUFFER_SIZE)
                accepted.setSendBufferSize(SOCKET_BUFFER_SIZE)
                Session(server, accepted)
            } catch (t: Throwable) {
                runCatching { server.close() }
                null
            }
        }

        return try {
            val client = LocalSocket().apply {
                connect(
                    LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT)
                )
            }
            client.setReceiveBufferSize(SOCKET_BUFFER_SIZE)
            client.setSendBufferSize(SOCKET_BUFFER_SIZE)
            Session(null, client)
        } catch (t: Throwable) {
            null
        }
    }

    /** 持续把本地缓冲里的声音发出去，直到连接断开。 */
    private fun sendLoop(output: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = outgoing.read(buf, buf.size, SEND_POLL_TIMEOUT_MS)
            if (n <= 0) continue
            // 写失败会抛异常，由外层捕获后断开重连
            output.write(buf, 0, n)
            output.flush()
        }
    }

    /** 持续把对方发来的声音收进本地缓冲，直到连接断开。 */
    private fun startReceiver(input: InputStream) {
        Thread({
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) incoming.write(buf, 0, n)
                }
            } catch (t: Throwable) {
                // 断开属正常情况
            }
        }, "mic-control-recv").apply {
            isDaemon = true
            start()
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
