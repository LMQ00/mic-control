package com.lmq.miccontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 接收通知栏按钮的点击，切换总开关并同步到系统。
 *
 * 做成接收器而不是直接在通知里改设置，是因为通知上的按钮点击需要一个
 * 明确的处理入口；这样界面不需要一直活着，关掉界面后按钮照样能用。
 */
class ToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ControlNotification.ACTION_TOGGLE) return

        val store = SettingsStore(context)
        val want = !store.enabled
        store.enabled = want

        // 立刻把通知刷新成新状态，让人一眼看出现在是开还是关
        ControlNotification.show(context)

        // 装/卸钩子要读写别的进程，放到后台做，不阻塞通知的刷新
        val pending = goAsync()
        val injector = Injector(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (want) {
                    val uid = store.uidOf(store.musicPackage)
                    if (uid != null) {
                        injector.enable(uid)
                    } else {
                        // 没选应用就开不了，把状态退回关闭，避免界面与实况不符
                        store.enabled = false
                        ControlNotification.show(context)
                    }
                } else {
                    injector.disable()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
