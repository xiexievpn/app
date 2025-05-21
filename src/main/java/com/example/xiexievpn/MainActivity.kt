package com.example.xiexievpn

import android.app.Activity
import android.content.*
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var btnOpenProxy: Button
    private lateinit var btnCloseProxy: Button
    private lateinit var tvSwitchRegion: TextView

    private var currentUuid: String? = null
    private var shouldStartVpnAfterPermission = false  // 标识用户点击“打开加速”后等待VPN授权

    companion object {
        private const val REQUEST_VPN_PERMISSION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnOpenProxy = findViewById(R.id.btnOpenProxy)
        btnCloseProxy = findViewById(R.id.btnCloseProxy)
        tvSwitchRegion = findViewById(R.id.tvSwitchRegion)

        currentUuid = intent.getStringExtra("uuid") ?: ""

        btnOpenProxy.setOnClickListener {
            // 用户点击“打开加速”
            checkVpnPermissionAndStart()
        }

        btnCloseProxy.setOnClickListener {
            // 用户点击“关闭加速”
            stopAcceleration()
        }

        tvSwitchRegion.setOnClickListener {
            // 切换区域的网页
            val url = "https://cn.xiexievpn.com/app.html?code=$currentUuid"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
            Toast.makeText(this, "切换区域后需重启此应用", Toast.LENGTH_LONG).show()
        }

        // 如果从 LoginActivity 拿到了 uuid，自动获取配置
        if (!currentUuid.isNullOrBlank()) {
            fetchConfigData(currentUuid!!)
        }
    }

    /**
     * 检查是否已获得 VPN 权限，如果未授权则发起请求
     */
    private fun checkVpnPermissionAndStart() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            // 需要用户同意
            shouldStartVpnAfterPermission = true
            startActivityForResult(vpnIntent, REQUEST_VPN_PERMISSION)
        } else {
            // 已经有权限了，直接启动
            startAcceleration()
        }
    }

    /**
     * 处理 VPN 权限请求的结果
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == Activity.RESULT_OK) {
                // 用户已同意，开始加速
                if (shouldStartVpnAfterPermission) {
                    startAcceleration()
                }
            } else {
                // 用户拒绝
                Toast.makeText(this, "您拒绝了 VPN 权限请求", Toast.LENGTH_SHORT).show()
            }
            shouldStartVpnAfterPermission = false
        }
    }

    /**
     * 获取服务器配置信息
     */
    private fun fetchConfigData(uuid: String) {
        val client = OkHttpClient()
        val jsonBody = JSONObject().put("code", uuid)
        val requestBody = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://vvv.xiexievpn.com/getuserinfo")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "无法连接到服务器: $e", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val responseData = it.body?.string() ?: ""
                        try {
                            val json = JSONObject(responseData)
                            val v2rayurl = json.optString("v2rayurl", "")
                            val zone = json.optString("zone", "")

                            if (v2rayurl.isEmpty() && zone.isEmpty()) {
                                // 服务器还没生成 v2rayurl，先调用 /adduser
                                doAddUser(uuid)
                            } else if (v2rayurl.isEmpty()) {
                                // 只要 v2rayurl 为空就再轮询
                                doPollGetUserInfo(uuid)
                            } else {
                                // 已有 v2rayurl，解析并写 config.json
                                parseAndWriteConfig(v2rayurl)
                            }
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "解析服务器返回数据失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "服务器错误: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    /**
     * 调用 /adduser 接口
     */
    private fun doAddUser(uuid: String) {
        val client = OkHttpClient()
        val jsonBody = JSONObject().put("code", uuid)
        val requestBody = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://vvv.xiexievpn.com/adduser")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                // 再轮询
                doPollGetUserInfo(uuid)
            }
        })
    }

    /**
     * 轮询 getuserinfo, 3秒后再来一次
     */
    private fun doPollGetUserInfo(uuid: String) {
        window.decorView.postDelayed({
            fetchConfigData(uuid)
        }, 3000)
    }

    /**
     * 解析服务器返回的 vless://URL 并生成本地 config.json
     */
    private fun parseAndWriteConfig(urlString: String) {
        if (!urlString.startsWith("vless://")) {
            runOnUiThread {
                Toast.makeText(this, "服务器返回格式不对，非vless://开头", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val afterScheme = urlString.substring("vless://".length)  // 去掉 vless://
            val uuid = afterScheme.substringBefore("@")
            val afterAt = afterScheme.substringAfter("@")
            val domainPart = afterAt.substringBefore(":")
            val portPart = afterAt.substringAfter(":").substringBefore("?")
            // 提取 sni=xxx
            val queryPart = afterAt.substringAfter("?", "")
            val sni = queryPart.substringAfter("sni=", "").substringBefore("&")
            
            val realDomain = "$domainPart.rocketchats.xyz"

            // 生成 JSON
            val configJson = createConfigJson(uuid, realDomain, sni)

            // 写入本地 config.json
            val configFile = File(filesDir, "config.json")
            configFile.writeText(configJson)

            runOnUiThread {
                Toast.makeText(this, "获取配置成功，可打开加速", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "解析vless配置出错: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createConfigJson(uuid: String, domain: String, sni: String): String {
        // 使用 Xray 的 TUN inbound，让 Xray 能接收 VPNService TUN 流量
        val configData = JSONObject().apply {
            put("log", JSONObject().apply {
                put("loglevel", "error")
            })

            // ============= Inbounds (加入 "tun" inbound) =============
            put("inbounds", JSONArray().apply {
                // TUN inbound
                val tunInbound = JSONObject().apply {
                    put("tag", "tun-in")
                    put("protocol", "tun")
                    put("settings", JSONObject().apply {
                        put("autoRoute", true)
                        put("autoDetectInterface", true)
                        // domainStrategy 可视需求而定
                        put("domainStrategy", "IPOnDemand")
                    })
                    // 若需要 sniff SNI/HTTP Host，可开启
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        val destOverrideArray = JSONArray()
                        destOverrideArray.put("http")
                        destOverrideArray.put("tls")
                        put("destOverride", destOverrideArray)
                    })
                }
                put(tunInbound)
            })


            put("outbounds", JSONArray().apply {
                // 第一个 outbounds - vless
                put(JSONObject().apply {
                    put("protocol", "vless")
                    put("settings", JSONObject().apply {
                        put("vnext", JSONArray().apply {
                            put(JSONObject().apply {
                                put("address", domain) 
                                put("port", 443)    
                                put("users", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("id", uuid)
                                        put("encryption", "none")
                                        put("flow", "xtls-rprx-vision")
                                    })
                                })
                            })
                        })
                    })
                    put("streamSettings", JSONObject().apply {
                        put("network", "tcp")
                        put("security", "reality")
                        put("realitySettings", JSONObject().apply {
                            put("show", false)
                            put("fingerprint", "chrome")
                            // 服务器名可用 domain 或 sni
                            put("serverName", domain) 
                            // 后端给你的公钥
                            put("publicKey", "mUzqKeHBc-s1m03iD8Dh1JoL2B9JwG5mMbimEoJ523o")
                            put("shortId", "")
                            put("spiderX", "")
                        })
                    })
                    put("tag", "proxy")
                })

                // 第二个 outbounds - freedom (直连)
                put(JSONObject().apply {
                    put("protocol", "freedom")
                    put("tag", "direct")
                })

                // 第三个 outbounds - blackhole (阻断)
                put(JSONObject().apply {
                    put("protocol", "blackhole")
                    put("tag", "block")
                })
            })

            // ============= Routing 规则 =============
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray().apply {
                    // 屏蔽广告
                    put(JSONObject().apply {
                        put("type", "field")
                        put("domain", JSONArray().apply {
                            put("geosite:category-ads-all")
                        })
                        put("outboundTag", "block")
                    })
                    // BT流量直连
                    put(JSONObject().apply {
                        put("type", "field")
                        put("protocol", JSONArray().apply {
                            put("bittorrent")
                        })
                        put("outboundTag", "direct")
                    })
                    // 国外站点 -> proxy
                    put(JSONObject().apply {
                        put("type", "field")
                        put("domain", JSONArray().apply {
                            put("geosite:geolocation-!cn")
                        })
                        put("outboundTag", "proxy")
                    })
                    // 国内IP -> proxy（如你想国内IP直连，可以改成"direct"）
                    put(JSONObject().apply {
                        put("type", "field")
                        put("ip", JSONArray().apply {
                            put("geoip:cn")
                            put("geoip:private")
                        })
                        put("outboundTag", "proxy")
                    })
                })
            })
        }

        // 格式化缩进
        return configData.toString(4)
    }

    /**
     * 真正启动加速 (启动 VPNService)
     */
    private fun startAcceleration() {
        // 启动 XieXieVpnService
        val intent = Intent(this, XieXieVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }

        btnOpenProxy.isEnabled = false
        btnCloseProxy.isEnabled = true
        Toast.makeText(this, "加速已开启", Toast.LENGTH_SHORT).show()
    }

    /**
     * 停止加速 (停止 VPNService)
     */
    private fun stopAcceleration() {
        val intent = Intent(this, XieXieVpnService::class.java)
        stopService(intent)

        btnOpenProxy.isEnabled = true
        btnCloseProxy.isEnabled = false
        Toast.makeText(this, "加速已关闭", Toast.LENGTH_SHORT).show()
    }
}
