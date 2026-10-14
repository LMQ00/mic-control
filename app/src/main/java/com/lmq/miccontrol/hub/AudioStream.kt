package com.lmq.miccontrol.hub

/**
 * 一段可同时读写的声音数据缓冲区。
 *
 * 生产者不断往里写（音乐应用的声音），消费者不断往外读（游戏要的麦克风数据），
 * 两边各自在不同线程甚至不同进程里跑，靠这个缓冲区传递。
 *
 * ## 设计原则
 *
 * 1. **绝不阻塞生产者**。缓冲区满了就丢弃最旧的数据——声音是实时的，
 *    宁可丢掉过期的一小段，也绝不能让音乐播放卡住。
 * 2. **绝不无故丢消费者的数据**。只要缓冲区里还有数据，读取时就应当拿到它。
 *    声音的新鲜程度由缓冲区大小天然限定（最多也就积压这么多），
 *    不需要在读取时再额外丢弃，那样只会造成"断断续续"。
 */
class AudioStream(private val size: Int) {

    private val buffer = ByteArray(size)

    /** 累计写入的总字节数，只增不减，用于计算读写位置。 */
    private var written = 0L

    /** 累计读出的总字节数。 */
    private var read = 0L

    private val lock = Object()

    init {
        require(size > 0) { "缓冲区大小必须为正数" }
    }

    /**
     * 写入一段数据。
     *
     * 缓冲区满时丢弃最旧的数据来腾出空间，因此本方法永不阻塞。
     *
     * @param data 源数据
     * @param offset 从源数据的第几个字节开始
     * @param length 有效长度
     */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (length <= 0) return
        synchronized(lock) {
            var writtenBytes = 0
            while (writtenBytes < length) {
                // 环形写入：写到末尾就绕回开头
                val index = ((written + writtenBytes) % size).toInt()
                val chunk = minOf(size - index, length - writtenBytes)
                System.arraycopy(data, offset + writtenBytes, buffer, index, chunk)
                writtenBytes += chunk
            }
            written += length

            // 超出容量的部分丢弃最旧的，保证不阻塞生产者
            if (written - read > size) {
                read = written - size
            }
            lock.notifyAll()
        }
    }

    /**
     * 读取数据。数据不足时最多等待 timeoutMs 毫秒。
     *
     * 只要缓冲区里有数据就会返回，不会额外丢弃——这一点很关键：
     * 之前曾在读取时按"消费者落后太多"主动跳过一段数据，结果是消费者
     * 明明在正常读取，却仍会莫名其妙地丢内容，听起来就是断断续续。
     *
     * @param destination 读出的数据放这里
     * @param length 最多读多少字节
     * @param timeoutMs 没有数据时最多等多久
     * @return 实际读到的字节数，0 表示等待超时且没有数据
     */
    fun read(destination: ByteArray, length: Int, timeoutMs: Long): Int {
        if (length <= 0) return 0
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (written - read <= 0) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return 0
                try {
                    lock.wait(remaining)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return 0
                }
            }

            val available = minOf(written - read, length.toLong()).toInt()
            if (available <= 0) return 0

            var copied = 0
            while (copied < available) {
                val index = ((read + copied) % size).toInt()
                val chunk = minOf(size - index, available - copied)
                System.arraycopy(buffer, index, destination, copied, chunk)
                copied += chunk
            }
            read += available
            return available
        }
    }

    /** 当前积压的字节数，用于诊断。 */
    val pending: Int
        get() = synchronized(lock) { (written - read).toInt() }
}
