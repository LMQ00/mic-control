package com.lmq.miccontrol.hub

/**
 * 一段可同时读写的声音数据缓冲区。
 *
 * 生产者不断往里写（音乐应用的声音），消费者不断往外读（游戏要的麦克风数据），
 * 两边各自在不同线程甚至不同进程里跑，靠这个缓冲区传递。
 *
 * 有两个刻意的设计：
 * 1. 缓冲区满了不阻塞生产者，而是丢弃最旧的数据——声音是实时的，
 *    宁可丢掉过期的一小段，也不能让音乐播放卡住；
 * 2. 消费者如果落后太多，会主动跳到最新位置——避免听到几秒前的旧声音，
 *    这对"实时对话"场景比"一个字节都不丢"重要得多。
 */
class AudioStream(private val size: Int) {

    private val buffer = ByteArray(size)

    /** 累计写入的总字节数，只增不减，用于计算读写位置。 */
    private var written = 0L

    /** 累计读出的总字节数。 */
    private var read = 0L

    private val lock = Object()

    /**
     * 写入一段数据。
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

            // 生产者太快时丢弃最旧的数据，保证不阻塞
            if (written - read > size) {
                read = written - size
            }
            lock.notifyAll()
        }
    }

    /**
     * 读取数据，数据不足时最多等待 timeoutMs 毫秒。
     *
     * @return 实际读到的字节数，0 表示等待超时且没有数据
     */
    fun read(destination: ByteArray, length: Int, timeoutMs: Long): Int {
        if (length <= 0) return 0
        synchronized(lock) {
            // 消费者落后太多就跳到较新的位置，避免听到过期声音
            val maxLag = size / 2
            if (written - read > maxLag) {
                read = written - maxLag
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            while (written - read < length) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                try {
                    lock.wait(remaining)
                } catch (e: InterruptedException) {
                    break
                }
            }

            val available = minOf(written - read, length.toLong()).toInt()
            if (available <= 0) return 0

            var offset = 0
            while (offset < available) {
                val index = ((read + offset) % size).toInt()
                val chunk = minOf(size - index, available - offset)
                System.arraycopy(buffer, index, destination, offset, chunk)
                offset += chunk
            }
            read += available
            return available
        }
    }
}
