package com.example.xiexievpn

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    private lateinit var etUuid: EditText
    private lateinit var cbRemember: CheckBox
    private lateinit var btnLogin: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etUuid = findViewById(R.id.etUuid)
        cbRemember = findViewById(R.id.cbRemember)
        btnLogin = findViewById(R.id.btnLogin)

        // 如果已有保存的 UUID，自动填入并尝试登录
        val savedUuid = getSharedPreferences("xiexie_vpn", MODE_PRIVATE).getString("uuid", "")
        if (!savedUuid.isNullOrBlank()) {
            etUuid.setText(savedUuid)
            // 直接尝试登录
            doLogin(savedUuid, auto = true)
        }

        btnLogin.setOnClickListener {
            val uuid = etUuid.text.toString().trim()
            if (uuid.isNotEmpty()) {
                doLogin(uuid, auto = false)
            } else {
                Toast.makeText(this, "请先输入随机码", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun doLogin(uuid: String, auto: Boolean) {
        // 用OkHttp发起POST请求
        val client = OkHttpClient()
        val jsonBody = JSONObject().put("code", uuid)
        val requestBody = RequestBody.create(
            MediaType.parse("application/json; charset=utf-8"), 
            jsonBody.toString()
        )
        val request = Request.Builder()
            .url("https://vvv.xiexievpn.com/login")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@LoginActivity, "无法连接服务器: $e", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        // 登录成功
                        if (cbRemember.isChecked) {
                            // 保存 UUID
                            getSharedPreferences("xiexie_vpn", MODE_PRIVATE)
                                .edit()
                                .putString("uuid", uuid)
                                .apply()
                        } else {
                            // 如果没选记住，则删掉
                            getSharedPreferences("xiexie_vpn", MODE_PRIVATE)
                                .edit()
                                .remove("uuid")
                                .apply()
                        }

                        runOnUiThread {
                            // 跳转主界面
                            val intent = Intent(this@LoginActivity, MainActivity::class.java)
                            intent.putExtra("uuid", uuid)
                            startActivity(intent)
                            finish()
                        }
                    } else {
                        // 登录失败
                        runOnUiThread {
                            getSharedPreferences("xiexie_vpn", MODE_PRIVATE)
                                .edit()
                                .remove("uuid")
                                .apply()

                            when (response.code()) {
                                401 -> Toast.makeText(this@LoginActivity, "无效的随机码", Toast.LENGTH_SHORT).show()
                                403 -> Toast.makeText(this@LoginActivity, "访问已过期", Toast.LENGTH_SHORT).show()
                                else -> Toast.makeText(this@LoginActivity, "服务器错误", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        })
    }
}
