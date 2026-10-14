package com.lmq.miccontrol.hook

/**
 * 当前这个应用该扮演什么角色。
 *
 * 同一个模块文件被加载到不同应用里，靠这个判定来决定它做什么。
 * 抽出来单独放，是因为这段判断有几种组合容易搞错，而一旦错了表现很隐蔽：
 * 要么该截的不截，要么在不该插手的地方插手、反而拖慢目标应用。
 */
enum class Role {
    /** 什么都不做，让这个应用完全保持原样。 */
    NONE,

    /** 这是被选中的音乐软件：截取它播放的声音。 */
    CAPTURE_MUSIC,

    /** 这是别的应用（比如游戏）：把它读到的麦克风数据换成音乐。 */
    REPLACE_MIC,
}

/**
 * 纯判定逻辑，不碰任何系统状态，因此可以直接用测试覆盖。
 *
 * @param enabled 总开关是否打开
 * @param selectedPackage 界面上选中的音乐软件包名，可能还没选
 * @param currentPackage 当前正在运行的这个应用的包名
 */
fun decideRole(enabled: Boolean, selectedPackage: String?, currentPackage: String): Role {
    // 开关没开，一切照旧
    if (!enabled) return Role.NONE

    // 还没选音乐软件时什么都不做。这里刻意不进入"替换录音"分支：
    // 没有声音来源可送，替换只会白白给目标应用的录音加上等待，
    // 白白拖慢它，却没有任何效果。
    if (selectedPackage.isNullOrBlank()) return Role.NONE

    return if (currentPackage == selectedPackage) Role.CAPTURE_MUSIC else Role.REPLACE_MIC
}
