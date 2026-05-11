package com.example.pavementdetection.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.pavementdetection.R
import com.example.pavementdetection.home.HomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    private lateinit var tvGoRegister: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已登录则直接跳首页
        if (TokenManager.isLoggedIn()) {
            goHome()
            return
        }

        setContentView(R.layout.activity_login)

        etUsername   = findViewById(R.id.etUsername)
        etPassword   = findViewById(R.id.etPassword)
        btnLogin     = findViewById(R.id.btnLogin)
        tvError      = findViewById(R.id.tvError)
        tvGoRegister = findViewById(R.id.tvGoRegister)
        progressBar  = findViewById(R.id.progressBar)

        btnLogin.setOnClickListener { doLogin() }

        tvGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun doLogin() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()

        // 本地校验
        if (username.isEmpty()) { showError("请输入用户名"); return }
        if (password.isEmpty()) { showError("请输入密码"); return }

        setLoading(true)

        CoroutineScope(Dispatchers.IO).launch {
            val result = AuthApiClient.login(username, password)
            withContext(Dispatchers.Main) {
                setLoading(false)
                if (result.success) {
                    TokenManager.saveToken(result.token, result.username)
                    goHome()
                } else {
                    showError(result.message)
                }
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        btnLogin.isEnabled   = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        tvError.visibility   = View.GONE
    }
}