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
import java.io.*
import java.lang.Exception
import kotlin.concurrent.thread

class XieXieVpnService : VpnService() {
    companion object {
        private const val TAG = "XieXieVpnService"
        private const val CHANNEL_ID = "XieXieVpnChannel"
        private const val NOTIFICATION_ID = 12345

        // 如果你要使用 socks inbound 监听这个端口，就在 config.json 里也写 10808
        // 后面 builder.protect(LOCAL_SOCKS_PORT) 会保护这个端口
        private const val LOCAL_SOCKS_PORT = 10808
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // 复制 geoip.dat / geosite.dat到 filesDir
        // (xray 主程序不再复制，因为放进 jniLibs 才能执行)
        prepareDataAssets()

        // 创建通知渠道并启动前台服务
        createNotificationChannel()
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        startVpn()
        startXray()
        return START_STICKY
    }

    private fun startVpn() {
        val builder = Builder()

        builder.setSession("XieXieVPN")

        // VPN 地址
        builder.addAddress("10.0.0.2", 32)
        // DNS
        builder.addDnsServer("8.8.8.8")
        // 全局路由
        builder.addRoute("0.0.0.0", 0)

        // === 关键：保护 xray 的本地端口，防止死循环 ===
        builder.protect(LOCAL_SOCKS_PORT)

        // 建立 TUN
        tunInterface = builder.establish()
        Log.d(TAG, "VPN interface established")
    }

    private fun startXray() {
        // === 注意：xray 不在 filesDir 里，而在 nativeLibraryDir 中 ===
        val xrayBinaryPath = "${applicationInfo.nativeLibraryDir}/xray"

        // 生成 config.json（或者你也可以在 MainActivity 已经写好，这里直接用）
        val configFile = generateConfigFile()

        try {
            val processBuilder = ProcessBuilder(
                xrayBinaryPath,
                "-config", configFile.absolutePath
            )
            // 合并错误输出到标准输出
            processBuilder.redirectErrorStream(true)

            // 启动子进程
            xrayProcess = processBuilder.start()
            Log.d(TAG, "xray started")

            // === 把 xray 的输出打印到日志，方便排查 ===
            thread(name = "xray-logger") {
                try {
                    val reader = BufferedReader(InputStreamReader(xrayProcess!!.inputStream))
                    while (true) {
                        val line = reader.readLine() ?: break
                        Log.d("XrayOutput", line)
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
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

    /**
     * 只复制 geoip.dat 和 geosite.dat
     * xray 主程序不在这里复制，因为已经在 jniLibs/arm64-v8a/xray
     */
    private fun prepareDataAssets() {
        val assetNames = listOf("geoip.dat", "geosite.dat")
        for (name in assetNames) {
            try {
                val outFile = File(filesDir, name)
                if (!outFile.exists()) {
                    assets.open(name).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare asset $name: ${e.message}")
            }
        }
    }

    // === 生成（或写入）一个带 socks inbound 的 config.json 示例 ===
    private fun generateConfigFile(): File {
        val geoipPath = File(filesDir, "geoip.dat").absolutePath
        val geositePath = File(filesDir, "geosite.dat").absolutePath
        val configJson = """
        {
          "log": {
            "loglevel": "debug"
          },
          "inbounds": [
            {
              "tag": "socks-in",
              "protocol": "socks",
              "listen": "127.0.0.1",
              "port": $LOCAL_SOCKS_PORT
            }
          ],
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "example.com",
                    "port": 443,
                    "users": [
                      {
                        "id": "your-uuid-here",
                        "encryption": "none",
                        "flow": "xtls-rprx-vision"
                      }
                    ]
                  }
                ]
              },
              "streamSettings": {
                "network": "tcp",
                "security": "reality",
                "realitySettings": {
                  "show": false,
                  "fingerprint": "chrome",
                  "serverName": "example.com",
                  "publicKey": "xxx",
                  "shortId": "",
                  "spiderX": ""
                }
              }
            },
            {
              "tag": "direct",
              "protocol": "freedom"
            },
            {
              "tag": "block",
              "protocol": "blackhole"
            }
          ],
          "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": [
              {
                "type": "field",
                "domain": [
                  "geosite:category-ads-all"
                ],
                "outboundTag": "block"
              },
              {
                "type": "field",
                "protocol": [
                  "bittorrent"
                ],
                "outboundTag": "direct"
              },
              {
                "type": "field",
                "domain": [
                  "geosite:geolocation-!cn"
                ],
                "outboundTag": "proxy"
              },
              {
                "type": "field",
                "ip": [
                  "geoip:cn",
                  "geoip:private"
                ],
                "outboundTag": "direct"
              }
            ]
          },
          "dns": {
            "servers": ["8.8.8.8", "1.1.1.1"]
          },
          "geodata": {
            "geoip": "$geoipPath",
            "geosite": "$geositePath"
          }
        }
        """.trimIndent()

        val configFile = File(filesDir, "config.json")
        configFile.writeText(configJson)
        return configFile
    }

    // ============= 以下是一些辅助方法 ==============
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

    private fun buildForegroundNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("XieXieVPN")
            .setContentText("VPN is running")
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return builder.build()
    }
}
