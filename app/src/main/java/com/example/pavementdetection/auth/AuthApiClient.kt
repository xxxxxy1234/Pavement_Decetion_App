package com.example.pavementdetection.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 登录 / 注册 专用网络客户端
 * 不加 AuthInterceptor（登录请求本身不需要 Token）
 * 调用方式均为同步，请在子线程 / 协程中使用
 */
object AuthApiClient {

    // ⚠️ 替换为实际服务端地址
    private const val BASE_URL = "http://172.20.10.11:8080"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class AuthResult(
        val success: Boolean,
        val token: String = "",
        val username: String = "",
        val message: String = ""
    )

    /**
     * 注册
     * @return AuthResult，success=true 时 token/username 有效
     */
    fun register(username: String, password: String): AuthResult {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$BASE_URL/api/auth/register")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                if (response.isSuccessful) {
                    AuthResult(
                        success = true,
                        token = json.optString("token"),
                        username = json.optString("username", username)
                    )
                } else {
                    AuthResult(
                        success = false,
                        message = json.optString("message", "注册失败（${response.code}）")
                    )
                }
            }
        } catch (e: Exception) {
            AuthResult(success = false, message = "网络错误：${e.message}")
        }
    }

    /**
     * 登录
     * @return AuthResult，success=true 时 token/username 有效
     */
    fun login(username: String, password: String): AuthResult {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$BASE_URL/api/auth/login")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                if (response.isSuccessful) {
                    AuthResult(
                        success = true,
                        token = json.optString("token"),
                        username = json.optString("username", username)
                    )
                } else {
                    AuthResult(
                        success = false,
                        message = json.optString("message", "登录失败（${response.code}）")
                    )
                }
            }
        } catch (e: Exception) {
            AuthResult(success = false, message = "网络错误：${e.message}")
        }
    }



    /**
     * 修改密码
     * 请求头由 AuthInterceptor 自动注入 Token
     */
    fun changePassword(oldPassword: String, newPassword: String): AuthResult {
        val body = JSONObject().apply {
            put("oldPassword", oldPassword)
            put("newPassword", newPassword)
        }.toString().toRequestBody(JSON)

        // 改密码需要带 Token，用带拦截器的 client
        // AuthApiClient 本身没有拦截器，手动加 Authorization 头
        val request = Request.Builder()
            .url("$BASE_URL/api/auth/changePassword")
            .header("Authorization", "Bearer ${TokenManager.getToken() ?: ""}")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (response.isSuccessful) {
                    AuthResult(success = true)
                } else {
                    AuthResult(success = false,
                        message = json.optString("message", "修改失败（${response.code}）"))
                }
            }
        } catch (e: Exception) {
            AuthResult(success = false, message = "网络错误：${e.message}")
        }
    }

    /**
     * 注销账号
     * 只删 users 表记录，检测数据保留
     */
    fun deleteAccount(): AuthResult {
        val request = Request.Builder()
            .url("$BASE_URL/api/auth/account")
            .header("Authorization", "Bearer ${TokenManager.getToken() ?: ""}")
            .delete()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                if (response.isSuccessful) {
                    AuthResult(success = true)
                } else {
                    AuthResult(success = false,
                        message = json.optString("message", "注销失败（${response.code}）"))
                }
            }
        } catch (e: Exception) {
            AuthResult(success = false, message = "网络错误：${e.message}")
        }
    }
}