package com.lmq.miccontrol.hub

/**
 * 声音样本与字节流之间的转换。
 *
 * 链路两端必须严格使用同一套规则：音乐那边把样本编码成字节，游戏那边再解码回样本。
 * 两边只要有一点不一致，队友听到的就是噪音——而且这种问题光看代码看不出来，
 * 只有实际听起来才发现。所以把规则集中到这一处，并用测试把往返一致性固定住。
 *
 * 统一约定：**小端序**（低位字节在前），这是安卓音频数据的通行格式。
 */
object PcmCodec {

    /**
     * 16 位样本编码为字节流。
     *
     * @param samples 源样本
     * @param offset 从第几个样本开始
     * @param count 编码多少个样本
     * @param out 输出的字节数组
     * @return 实际写入的字节数（样本数的两倍）
     */
    fun shortsToBytes(samples: ShortArray, offset: Int, count: Int, out: ByteArray): Int {
        if (count <= 0 || offset < 0 || offset >= samples.size) return 0
        val n = minOf(count, samples.size - offset, out.size / 2)
        if (n <= 0) return 0
        for (i in 0 until n) {
            val v = samples[offset + i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return n * 2
    }

    /**
     * 字节流解码为 16 位样本。
     *
     * @param bytes 源字节
     * @param byteCount 源字节中的有效长度（只处理完整的样本）
     * @param out 输出的样本数组
     * @param outOffset 从输出数组的第几个位置开始写
     * @return 实际写出的样本个数
     */
    fun bytesToShorts(bytes: ByteArray, byteCount: Int, out: ShortArray, outOffset: Int): Int {
        if (byteCount <= 0 || outOffset < 0 || outOffset >= out.size) return 0
        val samples = minOf(byteCount / 2, out.size - outOffset, bytes.size / 2)
        if (samples <= 0) return 0
        for (i in 0 until samples) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[outOffset + i] = ((hi shl 8) or lo).toShort()
        }
        return samples
    }

    /**
     * 浮点样本（约定取值 -1..1）编码为 16 位字节流。
     *
     * 超出范围的取值会被截断到边界，避免溢出成刺耳的噪音。
     *
     * @return 实际写入的字节数
     */
    fun floatsToBytes(samples: FloatArray, offset: Int, count: Int, out: ByteArray): Int {
        if (count <= 0 || offset < 0 || offset >= samples.size) return 0
        val n = minOf(count, samples.size - offset, out.size / 2)
        if (n <= 0) return 0
        for (i in 0 until n) {
            val v = (samples[offset + i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return n * 2
    }

    /**
     * 字节流解码为浮点样本（取值 -1..1）。
     *
     * @return 实际写出的样本个数
     */
    fun bytesToFloats(bytes: ByteArray, byteCount: Int, out: FloatArray, outOffset: Int): Int {
        if (byteCount <= 0 || outOffset < 0 || outOffset >= out.size) return 0
        val samples = minOf(byteCount / 2, out.size - outOffset, bytes.size / 2)
        if (samples <= 0) return 0
        for (i in 0 until samples) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            val s = ((hi shl 8) or lo).toShort()
            out[outOffset + i] = s.toFloat() / Short.MAX_VALUE
        }
        return samples
    }

    /**
     * 用新数据覆盖直接缓冲中"刚刚读入的那一段"。
     *
     * 这里要特别小心：[java.nio.ByteBuffer] 版本的录音读取在返回前，
     * 已经把它自己的位置向后推进了"读了几个字节"那么多。所以不能直接
     * 往当前位置追加——那样数据会落在错误的偏移上，替换等于没做，
     * 调用方拿到的仍是原来的内容。
     *
     * 正确做法：把位置退回本次读取的起点，写入新数据，再把位置还原到读取之后的状态。
     * 新数据不够长时，剩余部分填静音，避免残留上一轮的旧声音。
     *
     * @param buffer 调用方的直接缓冲
     * @param bytesRead 本次读取返回的字节数（决定要覆盖多长一段）
     * @param source 要写入的新数据
     * @return 实际写入的新数据字节数
     */
    fun fillReadBuffer(buffer: java.nio.ByteBuffer, bytesRead: Int, source: ByteArray): Int {
        if (bytesRead <= 0) return 0

        // 读取之后的位置就是这一段的末尾，据此算出起点
        val end = buffer.position()
        val start = end - bytesRead
        if (start < 0) return 0

        buffer.position(start)

        // 先写新数据，能写多少写多少
        val n = minOf(source.size, bytesRead)
        if (n > 0) buffer.put(source, 0, n)

        // 不足的部分补静音，保证这一整段都被覆盖过
        repeat(bytesRead - n) { buffer.put(0) }

        // 位置还原到读取之后的状态，让调用方看到的信息与正常读取一致
        buffer.position(end)
        return n
    }
}
