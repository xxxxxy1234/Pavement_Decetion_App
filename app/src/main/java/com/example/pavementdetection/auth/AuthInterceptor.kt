package com.example.pavementdetection.auth

import android.content.Context
import android.content.Intent
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp 全局 Auth 拦截器
 * 功能：
 *   1. 所有请求自动注入 Authorization: Bearer <token>
 *   2. 服务端返回 401 时，清除本地 Token 并跳转登录页
 *
 * 在构建 OkHttpClient 时加入：
 *   OkHttpClient.Builder().addInterceptor(AuthInterceptor(context)).build()
 */
class AuthInterceptor(private val context: Context) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // 注入 Token（无 Token 时不加头，让服务端返回 401）
        val request = TokenManager.getToken()?.let { token ->
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } ?: originalRequest

        val response = chain.proceed(request)

        // 401：Token 失效，清除登录态并跳转登录页
        if (response.code == 401) {
            TokenManager.clear()
            redirectToLogin()
        }

        return response
    }

    private fun redirectToLogin() {
        val intent = Intent(context, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }
}