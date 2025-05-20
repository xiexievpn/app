package com.example.xiexievpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

class XieXieVpnService : VpnService() {
    companion object {
        private const val TAG = "XieXieVpnService"
        private const val CHANNEL_ID = "XieXieVpnChannel"
        private const val NOTIFICATION_ID = 12345
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // 若要前台运行（避免被系统杀掉），可在此创建通知渠道并启动前台服务
        createNotificationChannel()
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        // 当 Service 启动时，建立 VPN + 启动 xray
        startVpn()
        startXray()
        return START_STICKY
    }

    private fun startVpn() {
        // 构建 TUN 接口
        val builder = Builder()

        // 设置 VPN 的会话名称
        builder.setSession("XieXieVPN")

        // 配置虚拟网卡地址（随意示例，可根据实际情况配置）
        builder.addAddress("10.0.0.2", 32)

        // 配置 DNS
        builder.addDnsServer("8.8.8.8")

        // 配置路由，0.0.0.0/0 表示默认路由都走 VPN
        builder.addRoute("0.0.0.0", 0)

        // 也可根据需求添加 IPv6
        // builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
        // builder.addRoute("::", 0)

        // 建立 TUN
        tunInterface = builder.establish()
        Log.d(TAG, "VPN interface established")
    }

    private fun startXray() {
        // 假设你已经在 filesDir 下把 xray 可执行文件保存为 "xray" 并 chmod +x
        val xrayBinaryPath = File(filesDir, "xray").absolutePath

        // 假设你已经生成好的 config.json 也在 filesDir 下
        val configPath = File(filesDir, "config.json").absolutePath

        try {
            // 启动 xray 进程
            val processBuilder = ProcessBuilder(xrayBinaryPath, "-config", configPath)
            // 合并错误输出到标准输出
            processBuilder.redirectErrorStream(true)

            // 启动子进程
            xrayProcess = processBuilder.start()

            Log.d(TAG, "xray started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start xray: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopXray()
        stopVpn()
    }

    private fun stopVpn() {
        // 关闭 TUN
        try {
            tunInterface?.close()
            tunInterface = null
            Log.d(TAG, "VPN interface closed")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopXray() {
        // 杀掉 xray 进程
        xrayProcess?.destroy()
        xrayProcess = null
        Log.d(TAG, "xray process destroyed")
    }

    // ============= 以下是一些辅助方法 ==============

    /**
     * 创建通知渠道 (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "XieXieVPN Foreground Service"
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "XieXieVPN is running in the background"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * 构造前台通知
     */
    private fun buildForegroundNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("XieXieVPN")
            .setContentText("VPN is running")
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return builder.build()
    }
}