package com.example.pavementdetection.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * Token 管理器
 * 封装 JWT Token 的存取与清除，统一管理登录态
 * 使用方式：TokenManager.init(context) 在 Application.onCreate() 中调用一次
 */
object TokenManager {

    private const val PREF_NAME = "auth"
    private const val KEY_TOKEN = "jwt_token"
    private const val KEY_USERNAME = "username"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /** 登录成功后保存 Token 和用户名 */
    fun saveToken(token: String, username: String = "") {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    /** 获取当前 Token，未登录返回 null */
    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    /** 获取当前登录用户名 */
    fun getUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""

    /** 是否已登录（Token 非空即视为有效，服务端 401 时再清除） */
    fun isLoggedIn(): Boolean = !getToken().isNullOrEmpty()

    /** 登出：清除所有登录态 */
    fun clear() {
        prefs.edit().clear().apply()
    }
}