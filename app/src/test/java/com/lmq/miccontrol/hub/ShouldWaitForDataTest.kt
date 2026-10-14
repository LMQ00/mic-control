package com.lmq.miccontrol.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "该不该为了等声音多停留一会儿"这段判断的测试。
 *
 * 它看起来只是个小优化，实际关系到目标应用会不会被明显拖慢：录音读取通常
 * 每十来毫秒就来一次，如果每次都在这里多等，游戏语音就会卡。而"开着开关
 * 但还没开始放歌"恰是最常见的起始状态，所以这些组合必须逐条钉死。
 */
class ShouldWaitForDataTest {

    private val threshold = 200L

    @Test
    fun `通道没接通时不等待`() {
        assertFalse(
            shouldWaitForData(
                connected = false,
                lastIncomingAt = 1000L,
                now = 1100L,
                idleThresholdMs = threshold,
            )
        )
    }

    @Test
    fun `从未收到过声音时不等待`() {
        // 这是"开关开着但还没放歌"的情形，最不该在这里耗时间
        assertFalse(
            shouldWaitForData(
                connected = true,
                lastIncomingAt = 0L,
                now = 5000L,
                idleThresholdMs = threshold,
            )
        )
    }

    @Test
    fun `刚刚收到过声音时值得等待`() {
        assertTrue(
            shouldWaitForData(
                connected = true,
                lastIncomingAt = 1000L,
                now = 1050L,
                idleThresholdMs = threshold,
            )
        )
    }

    @Test
    fun `恰好在阈值边界上仍然等待`() {
        assertTrue(
            shouldWaitForData(
                connected = true,
                lastIncomingAt = 1000L,
                now = 1000L + threshold,
                idleThresholdMs = threshold,
            )
        )
    }

    @Test
    fun `超过阈值一点点就不再等待`() {
        assertFalse(
            shouldWaitForData(
                connected = true,
                lastIncomingAt = 1000L,
                now = 1000L + threshold + 1,
                idleThresholdMs = threshold,
            )
        )
    }

    @Test
    fun `长时间没声音时不等待`() {
        // 歌放完之后就是这种状态：连接还在，但对面没在出声
        assertFalse(
            shouldWaitForData(
                connected = true,
                lastIncomingAt = 1000L,
                now = 1000L + 60_000L,
                idleThresholdMs = threshold,
            )
        )
    }
}
