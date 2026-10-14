package com.lmq.miccontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 通知栏里的开关。
 *
 * 为什么要有它：这个工具用起来是这样的——你在打游戏，想放歌就打开、不想放了就关掉。
 * 每次都切出去开应用太麻烦，所以放一个常驻通知，下拉就能点。
 *
 * 通知里的按钮和界面上的开关操作的是同一份设置，两边状态始终一致。
 */
object ControlNotification {

    private const val CHANNEL_ID = "mic_control"
    private const val NOTIFICATION_ID = 1001

    /** 点击通知按钮时发出的动作。 */
    const val ACTION_TOGGLE = "com.lmq.miccontrol.action.TOGGLE"

    /** 把通知显示出来（或刷新成当前状态）。 */
    fun show(context: Context) {
        createChannel(context)

        val manager = NotificationManagerCompat.from(context)
        // 用户没授权通知时什么都不做，不能因此崩溃
        if (!manager.areNotificationsEnabled()) return

        try {
            manager.notify(NOTIFICATION_ID, build(context))
        } catch (t: SecurityException) {
            // 权限被撤销等情况，忽略即可
        }
    }

    /** 移除通知。 */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** 通知当前是否显示着。 */
    fun isShown(context: Context): Boolean {
        return try {
            NotificationManagerCompat.from(context)
                .activeNotifications
                .any { it.id == NOTIFICATION_ID }
        } catch (t: Throwable) {
            // 部分系统上查询通知列表会抛异常，此时保守地当作"没显示"
            false
        }
    }

    private fun build(context: Context): Notification {
        val enabled = SettingsStore(context).enabled

        // 点按钮 = 切换开关
        val toggleIntent = Intent(context, ToggleReceiver::class.java).setAction(ACTION_TOGGLE)
        val togglePending = PendingIntent.getBroadcast(
            context,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 点通知主体 = 打开界面
        val openPending = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(
                if (enabled) "已打开：麦克风输入的是音乐" else "已关闭：麦克风正常工作"
            )
            .setContentIntent(openPending)
            .addAction(0, if (enabled) "关闭" else "打开", togglePending)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "控制开关",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "用于随时开关麦克风控制"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
