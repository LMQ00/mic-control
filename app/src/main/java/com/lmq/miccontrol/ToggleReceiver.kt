package com.lmq.miccontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收通知栏按钮的点击，切换总开关。
 *
 * 做成接收器而不是直接在通知里改设置，是因为通知上的按钮点击需要一个
 * 明确的处理入口；这样界面不需要一直活着，关掉界面后按钮照样能用。
 */
class ToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ControlNotification.ACTION_TOGGLE) return

        val store = SettingsStore(context)
        // 切换：原来开着就关掉，原来关着就打开
        store.enabled = !store.enabled

        // 立刻把通知刷新成新状态，让人一眼看出现在是开还是关
        ControlNotification.show(context)
    }
}
