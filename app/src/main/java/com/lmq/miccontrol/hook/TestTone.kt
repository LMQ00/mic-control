package com.lmq.miccontrol.hook

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 测试音发生器。
 *
 * 里程碑 1 阶段先不接真实音乐数据，用一段可辨识的音来验证链路：
 * 如果目标 App 的语音那头听到的是这个音，说明 hook 生效了。
 *
 * 之所以放一个"能被听出来"的音：调试验证时，人耳分辨一段明显的人工音，
 * 比看日志更直接，也不需要额外的分析工具。
 */
object TestTone {

    /** 测试音频率，取 440Hz（标准音 A），人耳容易辨认。 */
    private const val FREQUENCY_HZ = 440.0

    /** 采样率，与系统音频设备能力保持一致，避免重采样。 */
    private const val SAMPLE_RATE = 48000

    /** 相位累计，保证连续多个 buffer 之间波形不跳变，否则会有咔哒声。 */
    private var phase = 0.0

    /**
     * 把测试音填进调用方提供的缓冲区。
     *
     * 需要兼容多种缓冲区类型，因为 AudioRecord.read 的重载接收不同类型：
     * ByteBuffer、byte[]、short[]、float[] 都可能出现，而它们表示的样本
     * 格式不同，填充方式也不同。
     *
     * @param buffer 调用方传入的缓冲区，类型为上述之一
     * @param count  read 返回的有效长度（对 byte 型是字节数，对 short 型是样本数）
     */
    fun fill(buffer: Any, count: Int) {
        when (buffer) {
            is ByteBuffer -> fillByteBuffer(buffer, count)
            is ByteArray -> fillByteArray(buffer, count)
            is ShortArray -> fillShortArray(buffer, count)
            is FloatArray -> fillFloatArray(buffer, count)
            else -> Unit // 其他类型不认识，原样保留，宁可不出声也不破坏数据
        }
    }

    private fun fillByteBuffer(buffer: ByteBuffer, count: Int) {
        // AudioRecord 用 ByteBuffer 时通常按 16 位样本存放，此处按小端序写入
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val samples = count / 2
        for (i in 0 until samples) {
            val value = nextSample()
            // 不覆盖整个缓冲区，只写到 read 返回的长度为止，保留其余内容
            if (buffer.remaining() < 2) break
            buffer.putShort(value)
        }
    }

    private fun fillByteArray(buffer: ByteArray, count: Int) {
        val samples = count / 2
        for (i in 0 until samples) {
            val value = nextSample().toInt()
            val index = i * 2
            if (index + 1 >= buffer.size) break
            // 小端序：低字节在前
            buffer[index] = (value and 0xFF).toByte()
            buffer[index + 1] = ((value shr 8) and 0xFF).toByte()
        }
    }

    private fun fillShortArray(buffer: ShortArray, count: Int) {
        for (i in 0 until count) {
            if (i >= buffer.size) break
            buffer[i] = nextSample()
        }
    }

    private fun fillFloatArray(buffer: FloatArray, count: Int) {
        for (i in 0 until count) {
            if (i >= buffer.size) break
            // float 型样本的取值范围是 -1.0 ~ 1.0
            buffer[i] = nextSample().toFloat() / Short.MAX_VALUE
        }
    }

    /** 生成下一个 16 位样本值。 */
    private fun nextSample(): Short {
        val value = (Short.MAX_VALUE * 0.3 * Math.sin(phase)).toInt()
        phase += 2.0 * Math.PI * FREQUENCY_HZ / SAMPLE_RATE
        if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
        return value.toShort()
    }
}
