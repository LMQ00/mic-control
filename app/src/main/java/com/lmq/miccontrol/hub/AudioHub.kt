package com.lmq.miccontrol.hub

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log

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

    /**
     * 日志标记。
     *
     * 只记录"状态发生变化"这种少量事件（连上了、断了、收到第一批声音），
     * 不在每次读写时打印——那会瞬间刷满日志、拖慢音频，也让人找不到重点。
     */
    private const val TAG = "MicControl"

    private const val SOCKET_BUFFER_SIZE = 64 * 1024

    /** 连接失败后等待多久再试，避免空转。 */
    private const val RETRY_INTERVAL_MS = 500L

    /** 发送线程每次从本地缓冲取数据的等待上限。 */
    private const val SEND_POLL_TIMEOUT_MS = 100L

    /**
     * 多久没收到声音，就认为对面没在出声。
     *
     * 取值比人耳能察觉的间隔短得多：既能及时判断"现在没声音、不用等"，
     * 又不会被声音之间正常的细微间隙误判。
     */
    private const val IDLE_THRESHOLD_MS = 200L

    private val lock = Object()

    @Volatile
    private var connected = false

    @Volatile
    private var workerStarted = false

    /** 最近一次收到声音的时刻，用于判断对面是否正在出声。 */
    @Volatile
    private var lastIncomingAt = 0L

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
     * 这里刻意做成"绝大部分情况立刻返回"，因为它是被目标应用的录音线程调用的：
     * 录音读取通常每十来毫秒就来一次，任何多余的等待都会直接拖慢对方的语音，
     * 听起来就是卡顿。
     *
     * 具体策略：
     * - 缓冲里有现成数据 → 立刻取走；
     * - 缓冲是空的，且对面并没有在出声（比如歌还没开始放）→ 立刻返回 0，
     *   一点也不等。这是最常见的情形，绝不能在这里耗时间；
     * - 缓冲是空的，但对面正在出声 → 才值得稍等一下，把数据补齐。
     *
     * @return 实际读到的字节数；0 表示暂时没有数据，调用方应保持原样
     */
    fun read(destination: ByteArray, length: Int, timeoutMs: Long): Int {
        if (length <= 0) return 0
        ensureWorker()

        // 先看有没有现成的，不等
        val immediate = incoming.read(destination, length, 0)
        if (immediate > 0) return immediate

        // 缓冲空：只有确认对面正在出声，等待才有意义
        val shouldWait = shouldWaitForData(
            connected = connected,
            lastIncomingAt = lastIncomingAt,
            now = System.currentTimeMillis(),
            idleThresholdMs = IDLE_THRESHOLD_MS,
        )
        if (!shouldWait) return 0

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
                Log.i(TAG, "声音通道已接通（本端${if (session.server != null) "作为等待方" else "作为连接方"}）")
                startReceiver(session.input)
                sendLoop(session.output)
            } catch (t: Throwable) {
                // 连接中断属正常情况，走下面的重试
            } finally {
                if (connected) Log.i(TAG, "声音通道已断开，稍后自动重连")
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
            // 每次连接只报一次"收到第一批声音"，用来确认链路真的通了。
            // 若始终没有这一行，说明对面根本没在出声，而不是通道有问题。
            var firstBatchLogged = false
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        // 记下收到声音的时刻，供读取侧判断"对面是否正在出声"
                        lastIncomingAt = System.currentTimeMillis()
                        incoming.write(buf, 0, n)
                        if (!firstBatchLogged) {
                            firstBatchLogged = true
                            Log.i(TAG, "已收到对方发来的声音，链路正常")
                        }
                    }
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

/**
 * 判断缓冲没数据时，是否值得再等一会儿。
 *
 * 抽成独立函数是为了能用测试覆盖各种组合。这段判断直接关系到目标应用的录音
 * 会不会被无谓拖慢——而"开着开关但还没开始放歌"恰是最常见的起始状态，
 * 一旦在这里等下去，游戏语音就会明显变卡。
 *
 * @param connected 通道是否已接通
 * @param lastIncomingAt 最近一次收到声音的时刻（0 表示从未收到）
 * @param now 当前时刻
 * @param idleThresholdMs 超过多久没收到声音就认为对面没在出声
 * @return 是否应该继续等待数据
 */
fun shouldWaitForData(
    connected: Boolean,
    lastIncomingAt: Long,
    now: Long,
    idleThresholdMs: Long,
): Boolean {
    // 通道没连上，等也等不到东西
    if (!connected) return false
    // 从来没收到过声音，说明对面还没开始出声
    if (lastIncomingAt <= 0L) return false
    // 距上次收到声音已过去太久，判定为当前没在出声
    return now - lastIncomingAt <= idleThresholdMs
}
