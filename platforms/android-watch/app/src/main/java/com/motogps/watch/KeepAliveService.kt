package com.motogps.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 前台保活服务：骑行期间维持进程存活，防止 OPPO 系统的激进后台管理
 * 把应用杀掉/挤掉（息屏后回主页的现象）。BLE 链路也因此在切换页面/
 * 短暂后台时不断线。
 *
 * 常亮白名单（OPPO 官方仅限系统签名应用）第三方无法接入；本服务 +
 * 电池优化白名单 + 屏幕唤醒锁是第三方能用的全部正规手段。
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "导航保活", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MOTO GPS 导航中")
                .setContentText("蓝牙链路保持连接，可回主屏后从最近任务返回")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
