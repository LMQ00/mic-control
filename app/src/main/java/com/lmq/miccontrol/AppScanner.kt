package com.lmq.miccontrol

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** 一个可选的音乐应用。 */
data class MusicApp(
    val packageName: String,
    val label: String,
)

/**
 * 读取手机上已安装的应用，供用户挑选音乐软件。
 *
 * 会把系统自带组件和一些明显不是音乐播放器的应用滤掉，减少列表噪音。
 */
object AppScanner {

    /**
     * 列出候选的音乐应用。
     *
     * @param context 用于查询应用列表
     * @return 按名称排序的应用列表；查询失败时返回空列表，由界面提示
     */
    fun listMusicCandidates(context: Context): List<MusicApp> {
        val pm = context.packageManager
        return try {
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            installed
                .asSequence()
                .filter { it.packageName != context.packageName }
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { MusicApp(it.packageName, it.loadLabel(pm).toString()) }
                .sortedBy { it.label }
                .toList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * 按关键词筛出匹配的应用。
     *
     * 关键词为空时返回原列表；否则应用名称或包名包含关键词即算匹配，不区分大小写。
     */
    fun filterApps(apps: List<MusicApp>, query: String): List<MusicApp> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return apps
        return apps.filter {
            it.label.contains(keyword, ignoreCase = true) ||
                it.packageName.contains(keyword, ignoreCase = true)
        }
    }
}
