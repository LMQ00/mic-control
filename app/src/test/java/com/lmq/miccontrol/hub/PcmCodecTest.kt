package com.lmq.miccontrol.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声音编解码的单元测试。
 *
 * 链路两端必须严格使用同一套编解码规则：一边把样本变成字节，另一边再变回样本。
 * 只要有一点不对称，队友听到的就是噪音或杂音——而这类问题在代码上看不出来，
 * 只有真听起来才发现。所以用测试把"往返一致"这件事钉死。
 */
class PcmCodecTest {

    @Test
    fun `16 位样本编码后再解码应完全还原`() {
        val original = shortArrayOf(0, 1, -1, 12345, -12345, Short.MAX_VALUE, Short.MIN_VALUE)
        val bytes = ByteArray(original.size * 2)

        val written = PcmCodec.shortsToBytes(original, 0, original.size, bytes)
        assertEquals(original.size * 2, written)

        val restored = ShortArray(original.size)
        val count = PcmCodec.bytesToShorts(bytes, written, restored, 0)

        assertEquals(original.size, count)
        assertArrayEquals(original, restored)
    }

    @Test
    fun `16 位编码使用小端序`() {
        // 0x1234 按小端序应是 0x34 在前、0x12 在后
        val out = ByteArray(2)
        PcmCodec.shortsToBytes(shortArrayOf(0x1234), 0, 1, out)

        assertEquals(0x34.toByte(), out[0])
        assertEquals(0x12.toByte(), out[1])
    }

    @Test
    fun `负数样本往返后不失真`() {
        val original = shortArrayOf(-1, -256, -32768)
        val bytes = ByteArray(original.size * 2)
        PcmCodec.shortsToBytes(original, 0, original.size, bytes)

        val restored = ShortArray(original.size)
        PcmCodec.bytesToShorts(bytes, bytes.size, restored, 0)

        assertArrayEquals(original, restored)
    }

    @Test
    fun `浮点样本编码后再解码应接近原值`() {
        val original = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f)
        val bytes = ByteArray(original.size * 2)

        val written = PcmCodec.floatsToBytes(original, 0, original.size, bytes)
        assertEquals(original.size * 2, written)

        val restored = FloatArray(original.size)
        PcmCodec.bytesToFloats(bytes, written, restored, 0)

        // 16 位量化会引入微小误差，允许千分之一的偏差
        for (i in original.indices) {
            assertEquals(
                "第 $i 个样本偏差过大",
                original[i].toDouble(),
                restored[i].toDouble(),
                0.001,
            )
        }
    }

    @Test
    fun `浮点超范围取值被截断而不是溢出成噪音`() {
        // 超出 -1..1 的值若直接相乘会整数溢出，产生刺耳噪音
        val original = floatArrayOf(5f, -5f, 100f)
        val bytes = ByteArray(original.size * 2)

        PcmCodec.floatsToBytes(original, 0, original.size, bytes)

        val restored = FloatArray(original.size)
        PcmCodec.bytesToFloats(bytes, bytes.size, restored, 0)

        assertEquals(1f, restored[0], 0.001)
        assertEquals(-1f, restored[1], 0.001)
        assertEquals(1f, restored[2], 0.001)
    }

    @Test
    fun `按偏移和数量只转换指定范围`() {
        val original = shortArrayOf(10, 20, 30, 40, 50)
        val bytes = ByteArray(6)

        // 只取第 2 个开始的 3 个样本
        val written = PcmCodec.shortsToBytes(original, offset = 1, count = 3, out = bytes)
        assertEquals(6, written)

        val restored = ShortArray(3)
        PcmCodec.bytesToShorts(bytes, written, restored, 0)

        assertArrayEquals(shortArrayOf(20, 30, 40), restored)
    }

    @Test
    fun `解码时写入目标数组的指定偏移位置`() {
        val original = shortArrayOf(111, 222)
        val bytes = ByteArray(4)
        PcmCodec.shortsToBytes(original, 0, 2, bytes)

        // 从目标数组第 2 个位置开始写，前面两个位置应保持原样
        val target = ShortArray(5) { 999 }
        val count = PcmCodec.bytesToShorts(bytes, bytes.size, target, outOffset = 2)

        assertEquals(2, count)
        assertArrayEquals(shortArrayOf(999, 999, 111, 222, 999), target)
    }

    @Test
    fun `目标数组放不下时只写能放下的部分`() {
        val original = ShortArray(10) { it.toShort() }
        val bytes = ByteArray(20)
        PcmCodec.shortsToBytes(original, 0, 10, bytes)

        // 目标只能装 3 个样本
        val target = ShortArray(3)
        val count = PcmCodec.bytesToShorts(bytes, bytes.size, target, 0)

        assertEquals(3, count)
        assertArrayEquals(shortArrayOf(0, 1, 2), target)
    }

    @Test
    fun `输出缓冲区不足时不会越界写入`() {
        val original = ShortArray(10) { it.toShort() }
        // 输出只有 4 字节，最多装 2 个样本
        val bytes = ByteArray(4)

        val written = PcmCodec.shortsToBytes(original, 0, 10, bytes)

        assertEquals(4, written)
    }

    @Test
    fun `零长度或越界的请求被安全拒绝`() {
        val bytes = ByteArray(8)
        val samples = ShortArray(4)

        assertEquals(0, PcmCodec.shortsToBytes(samples, 0, 0, bytes))
        assertEquals(0, PcmCodec.shortsToBytes(samples, 10, 2, bytes))
        assertEquals(0, PcmCodec.bytesToShorts(bytes, 0, samples, 0))
        assertEquals(0, PcmCodec.bytesToShorts(bytes, bytes.size, samples, 10))
    }

    @Test
    fun `奇数个字节时只处理完整的样本`() {
        // 5 个字节只能构成 2 个完整样本，最后 1 个字节应被忽略
        val bytes = byteArrayOf(1, 0, 2, 0, 99)
        val restored = ShortArray(4)

        val count = PcmCodec.bytesToShorts(bytes, bytes.size, restored, 0)

        assertEquals(2, count)
        assertEquals(1.toShort(), restored[0])
        assertEquals(2.toShort(), restored[1])
    }

    @Test
    fun `大量样本往返保持完全一致`() {
        // 用一段模拟的波形做整体验证，覆盖各种取值
        val original = ShortArray(4096) { i ->
            (kotlin.math.sin(i / 20.0) * Short.MAX_VALUE * 0.8).toInt().toShort()
        }
        val bytes = ByteArray(original.size * 2)

        val written = PcmCodec.shortsToBytes(original, 0, original.size, bytes)
        val restored = ShortArray(original.size)
        val count = PcmCodec.bytesToShorts(bytes, written, restored, 0)

        assertEquals(original.size, count)
        assertArrayEquals(original, restored)
        assertTrue("应写满整个缓冲区", written == original.size * 2)
    }
}
