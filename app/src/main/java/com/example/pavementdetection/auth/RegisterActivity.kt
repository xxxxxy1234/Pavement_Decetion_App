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

class RegisterActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etPasswordConfirm: EditText
    private lateinit var btnRegister: Button
    private lateinit var tvError: TextView
    private lateinit var tvBack: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        etUsername       = findViewById(R.id.etUsername)
        etPassword       = findViewById(R.id.etPassword)
        etPasswordConfirm = findViewById(R.id.etPasswordConfirm)
        btnRegister      = findViewById(R.id.btnRegister)
        tvError          = findViewById(R.id.tvError)
        tvBack           = findViewById(R.id.tvBack)
        progressBar      = findViewById(R.id.progressBar)

        btnRegister.setOnClickListener { doRegister() }
        tvBack.setOnClickListener { finish() }
    }

    private fun doRegister() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        val confirm  = etPasswordConfirm.text.toString()

        // 本地校验
        if (username.isEmpty())         { showError("请输入用户名"); return }
        if (username.length < 3)        { showError("用户名至少3位"); return }
        if (password.isEmpty())         { showError("请输入密码"); return }
        if (password.length < 6)        { showError("密码至少6位"); return }
        if (password != confirm)        { showError("两次密码不一致"); return }

        setLoading(true)

        CoroutineScope(Dispatchers.IO).launch {
            val result = AuthApiClient.register(username, password)
            withContext(Dispatchers.Main) {
                setLoading(false)
                if (result.success) {
                    // 注册成功直接保存 Token，跳首页
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
        btnRegister.isEnabled  = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        tvError.visibility     = View.GONE
    }
}