package com.lmq.miccontrol.hook

/**
 * 从录音/播放接口的方法参数里，找出"长度"和"偏移"分别是第几个。
 *
 * ## 为什么必须按签名判断
 *
 * 这些接口有多个重载，参数排布并不统一，靠"数第几个整数"来判断一定会错：
 *
 * - `write(byte[], int offset, int size, int writeMode)`——最后一个整数是
 *   **写入模式**（取值 0 或 1），若当成长度，每次就只送出 1 个字节；
 * - `write(ByteBuffer, int size, int writeMode)`——没有偏移参数，数据从缓冲
 *   当前的位置开始；
 * - `read(byte[], int offset, int size)` 与 `read(ByteBuffer, int size)` 同理。
 *
 * 归到一处是因为两端（截取音乐、替换录音）用的是同一套规则：分散成两份，
 * 改动时漏掉一处就会出现"一边按样本数、一边按字节数"的错位，
 * 听起来就是噪音，而且极难定位。
 */
object AudioArgs {

    /**
     * "长度"参数的下标（从 0 开始），无法判断时返回 -1。
     *
     * 规则：首个参数是数组时，长度在第 3 个位置（下标 2）；
     * 首参是直接缓冲时，长度在第 2 个位置（下标 1）。
     */
    fun lengthIndex(parameterTypes: Array<Class<*>>): Int {
        val first = parameterTypes.firstOrNull() ?: return -1
        return when {
            java.nio.ByteBuffer::class.java.isAssignableFrom(first) -> 1
            first.isArray -> 2
            else -> -1
        }
    }

    /**
     * "偏移"参数的下标（从 0 开始），没有这个参数时返回 -1。
     *
     * 只有数组形态的重载才有偏移；直接缓冲形态没有，它从缓冲当前位置开始读。
     */
    fun offsetIndex(parameterTypes: Array<Class<*>>): Int {
        val first = parameterTypes.firstOrNull() ?: return -1
        return if (first.isArray) 1 else -1
    }

    /**
     * 把调用方给的偏移与长度收敛到缓冲区的合法范围内。
     *
     * 调用方理论上不会给越界值，但这里必须兜住：一旦越界访问，抛出异常会被
     * 目标应用的音频线程捕获到，轻则丢数据、重则让它崩溃。
     *
     * @param bufferSize 缓冲区的总长度
     * @param offset 调用方给的起始位置
     * @param length 调用方给的长度
     * @return 合法的起始位置与实际可用长度；长度为 0 表示无需处理
     */
    fun resolveRange(bufferSize: Int, offset: Int, length: Int): Pair<Int, Int> {
        if (length <= 0 || bufferSize <= 0) return 0 to 0
        val start = offset.coerceIn(0, bufferSize)
        val available = minOf(length, bufferSize - start)
        return if (available <= 0) start to 0 else start to available
    }
}
