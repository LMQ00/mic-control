package com.lmq.miccontrol

/**
 * 开关状态的存放约定。
 *
 * 界面进程和 hook 进程是两个不同的进程：界面负责写，hook 负责读。
 * 这里把"存在哪儿、叫什么名字"集中定义，两边都引用同一份常量，
 * 避免一边改了名字另一边还在读旧名字这种问题。
 */
object Prefs {

    /** 存储文件名。hook 侧读取时会用到，改名必须两边同时生效。 */
    const val FILE_NAME = "mic_control"

    /** 总开关：是否把音乐送进麦克风。 */
    const val KEY_ENABLED = "enabled"

    /** 用户选中的音乐应用包名。为空表示还没选。 */
    const val KEY_MUSIC_PACKAGE = "music_package"
}
