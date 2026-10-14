package com.lmq.miccontrol.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频缓冲的单元测试。
 *
 * 这个缓冲是整条链路的中间环节，它有两个必须守住的承诺：
 * 1. 写进来的数据，读出来要和原来一致（不能错位、不能串数据）；
 * 2. 不管生产者和消费者谁快谁慢，都不能把任何一方卡死。
 *
 * 这两条一旦破了，表现就是"队友听到的声音是噪音"或者"放歌卡住"，
 * 而且很难现场排查，所以用测试把它们固定住。
 */
class AudioStreamTest {

    @Test
    fun `写进去再读出来内容一致`() {
        val stream = AudioStream(64)
        val input = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        stream.write(input)

        val output = ByteArray(8)
        val got = stream.read(output, 8, timeoutMs = 0)

        assertEquals(8, got)
        assertArrayEquals(input, output)
    }

    @Test
    fun `按偏移和长度写入时只取指定范围`() {
        val stream = AudioStream(64)
        // 只写中间那 3 个字节
        val input = byteArrayOf(10, 20, 30, 40, 50, 60)
        stream.write(input, offset = 2, length = 3)

        val output = ByteArray(8)
        val got = stream.read(output, 8, timeoutMs = 0)

        assertEquals(3, got)
        assertArrayEquals(byteArrayOf(30, 40, 50), output.copyOf(3))
    }

    @Test
    fun `数据跨过缓冲区末尾时仍然连续`() {
        // 缓冲区故意取小一点，制造"绕回开头"的情况
        val stream = AudioStream(10)

        // 先写 8 个字节，读走 6 个，让写指针靠近末尾
        stream.write(ByteArray(8) { it.toByte() })
        val first = ByteArray(6)
        stream.read(first, 6, timeoutMs = 0)

        // 再写 4 个字节，这次必然跨越末尾绕回开头
        stream.write(byteArrayOf(100, 101, 102, 103))

        val second = ByteArray(6)
        val got = stream.read(second, 6, timeoutMs = 0)

        assertEquals(6, got)
        // 期望依次是之前剩下的 6、7，以及新写入的 100..103
        assertArrayEquals(byteArrayOf(6, 7, 100, 101, 102, 103), second)
    }

    @Test
    fun `没有数据时读取不为难调用方且不阻塞`() {
        val stream = AudioStream(32)
        val output = ByteArray(8)

        val started = System.currentTimeMillis()
        val got = stream.read(output, 8, timeoutMs = 0)
        val elapsed = System.currentTimeMillis() - started

        assertEquals(0, got)
        // 超时设为 0 时必须立刻返回，不能挂住调用方
        assertTrue("读取不应阻塞，实际耗时 ${elapsed}ms", elapsed < 200)
    }

    @Test
    fun `生产者写得比消费者快时丢弃最旧的数据而不是无限增长`() {
        val size = 16
        val stream = AudioStream(size)

        // 写入远超缓冲区容量的数据
        for (round in 0 until 4) {
            stream.write(ByteArray(size) { (round * 10 + it).toByte() })
        }

        // 读出来的应该是最新的一段：最后一轮写入的完整 16 字节
        val output = ByteArray(size)
        val got = stream.read(output, size, timeoutMs = 0)

        assertEquals(size, got)
        assertArrayEquals(ByteArray(size) { (30 + it).toByte() }, output)
    }

    @Test
    fun `消费者只要还有数据就能读到不会无故被跳过`() {
        val size = 32
        val stream = AudioStream(size)

        // 先写满
        stream.write(ByteArray(size) { it.toByte() })
        // 再写满一轮，此时缓冲区里应保留最新的一整轮
        stream.write(ByteArray(size) { (100 + it).toByte() })

        // 连续小段读取，模拟消费者正常消费
        val collected = ArrayList<Byte>()
        val chunk = ByteArray(8)
        repeat(4) {
            val got = stream.read(chunk, chunk.size, timeoutMs = 0)
            for (i in 0 until got) collected.add(chunk[i])
        }

        // 应当恰好读满 32 字节，且全部来自第二轮写入
        assertEquals(size, collected.size)
        val expected = ByteArray(size) { (100 + it).toByte() }
        assertArrayEquals(expected, collected.toByteArray())
    }

    @Test
    fun `单次请求超过缓冲区容量时读到容量上限而不是永远读不满`() {
        val size = 16
        val stream = AudioStream(size)

        // 请求长度是容量的两倍
        stream.write(ByteArray(size) { it.toByte() })

        val output = ByteArray(size * 2)
        val got = stream.read(output, size * 2, timeoutMs = 0)

        // 能读到已有的全部数据，而不是因为请求过大就返回 0
        assertEquals(size, got)
    }

    @Test
    fun `读取长度大于已有数据时只返回实际可读的部分`() {
        val stream = AudioStream(64)
        stream.write(byteArrayOf(1, 2, 3))

        val output = ByteArray(16)
        val got = stream.read(output, 16, timeoutMs = 0)

        assertEquals(3, got)
    }

    @Test
    fun `空写入与非法长度不会破坏已有数据`() {
        val stream = AudioStream(32)
        stream.write(byteArrayOf(7, 8, 9))

        // 长度为 0 或负数的写入应被忽略
        stream.write(ByteArray(0))
        stream.write(byteArrayOf(1, 2, 3), offset = 0, length = 0)
        stream.write(byteArrayOf(1, 2, 3), offset = 0, length = -5)

        val output = ByteArray(8)
        val got = stream.read(output, 8, timeoutMs = 0)

        assertEquals(3, got)
        assertArrayEquals(byteArrayOf(7, 8, 9), output.copyOf(3))
    }
}
