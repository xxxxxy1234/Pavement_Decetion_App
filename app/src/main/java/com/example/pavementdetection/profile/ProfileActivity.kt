package com.example.pavementdetection.profile

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.pavementdetection.R
import com.example.pavementdetection.auth.AuthApiClient
import com.example.pavementdetection.auth.LoginActivity
import com.example.pavementdetection.auth.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProfileActivity : AppCompatActivity() {

    private lateinit var tvAvatar: TextView
    private lateinit var tvUsername: TextView
    private lateinit var tvProfileTotal: TextView
    private lateinit var tvProfileImu: TextView
    private lateinit var tvProfileVisual: TextView
    private lateinit var tvLastDetection: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvDevice: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        tvAvatar        = findViewById(R.id.tvAvatar)
        tvUsername      = findViewById(R.id.tvUsername)
        tvProfileTotal  = findViewById(R.id.tvProfileTotal)
        tvProfileImu    = findViewById(R.id.tvProfileImu)
        tvProfileVisual = findViewById(R.id.tvProfileVisual)
        tvLastDetection = findViewById(R.id.tvLastDetection)
        tvVersion       = findViewById(R.id.tvVersion)
        tvDevice        = findViewById(R.id.tvDevice)

        // 用户名 + 头像首字母
        val username = TokenManager.getUsername().ifEmpty { "未知用户" }
        tvUsername.text = username
        tvAvatar.text   = username.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

        // 版本号
        tvVersion.text = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) { "1.0.0" }

        // 设备型号
        tvDevice.text = "${Build.MANUFACTURER} ${Build.MODEL}"

        loadStats()

        // ── 修改密码 ──────────────────────────────────────────────────────────
        findViewById<LinearLayout>(R.id.itemChangePassword).setOnClickListener {
            showChangePasswordDialog()
        }

        // ── 清除本地记录 ──────────────────────────────────────────────────────
        findViewById<LinearLayout>(R.id.itemClearData).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清除本地记录")
                .setMessage("将删除本机所有检测事件和GPS记录，云端数据不受影响。确定继续？")
                .setPositiveButton("清除") { _, _ -> clearLocalData() }
                .setNegativeButton("取消", null)
                .show()
        }

        // ── 退出登录 ──────────────────────────────────────────────────────────
        findViewById<LinearLayout>(R.id.itemLogout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("确定要退出当前账号吗？")
                .setPositiveButton("退出") { _, _ ->
                    TokenManager.clear()
                    goLogin()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // ── 注销账号 ──────────────────────────────────────────────────────────
        findViewById<LinearLayout>(R.id.itemDeleteAccount).setOnClickListener {
            showDeleteAccountDialog()
        }
    }

    // ── 统计数据 ──────────────────────────────────────────────────────────────
    private fun loadStats() {
        val gpsFile = File(getExternalFilesDir(null), "RoadDataCapture/eventgps.txt")
        if (!gpsFile.exists()) {
            tvProfileTotal.text  = "0"
            tvProfileImu.text    = "0"
            tvProfileVisual.text = "0"
            tvLastDetection.text = "暂无记录"
            return
        }
        val lines  = gpsFile.readLines().filter { it.isNotBlank() }
        val total  = lines.size
        val visual = lines.count { it.contains("[视觉触发]") }
        val imu    = total - visual

        tvProfileTotal.text  = total.toString()
        tvProfileImu.text    = imu.toString()
        tvProfileVisual.text = visual.toString()

        val last = lines.lastOrNull()
        tvLastDetection.text = if (last != null) {
            val time = last.substringBefore(",").trim()
            val type = if (last.contains("[视觉触发]")) "视觉" else "IMU"
            "$time · $type"
        } else "暂无记录"
    }

    // ── 修改密码弹窗 ──────────────────────────────────────────────────────────
    private fun showChangePasswordDialog() {
        val view = layoutInflater.inflate(android.R.layout.simple_list_item_2, null)
        // 用 AlertDialog 手动构建两个输入框
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etOld = EditText(this).apply {
            hint = "当前密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etNew = EditText(this).apply {
            hint = "新密码（至少6位）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etConfirm = EditText(this).apply {
            hint = "确认新密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etOld)
        layout.addView(etNew)
        layout.addView(etConfirm)

        AlertDialog.Builder(this)
            .setTitle("修改密码")
            .setView(layout)
            .setPositiveButton("确认") { _, _ ->
                val old     = etOld.text.toString()
                val new     = etNew.text.toString()
                val confirm = etConfirm.text.toString()
                when {
                    old.isEmpty()       -> toast("请输入当前密码")
                    new.length < 6      -> toast("新密码至少6位")
                    new != confirm      -> toast("两次密码不一致")
                    else                -> doChangePassword(old, new)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doChangePassword(oldPwd: String, newPwd: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = AuthApiClient.changePassword(oldPwd, newPwd)
            withContext(Dispatchers.Main) {
                if (result.success) {
                    toast("密码修改成功，请重新登录")
                    TokenManager.clear()
                    goLogin()
                } else {
                    toast(result.message)
                }
            }
        }
    }

    // ── 清除本地数据 ──────────────────────────────────────────────────────────
    private fun clearLocalData() {
        val rootDir = File(getExternalFilesDir(null), "RoadDataCapture")
        var count = 0
        if (rootDir.exists()) {
            rootDir.listFiles()?.forEach { file ->
                if (file.isDirectory &&
                    (file.name.startsWith("Event_") || file.name.startsWith("SideEvent_"))) {
                    file.deleteRecursively()
                    count++
                }
            }
            // 清空 GPS 索引
            val gpsFile = File(rootDir, "eventgps.txt")
            if (gpsFile.exists()) gpsFile.writeText("")
        }
        loadStats()
        toast("已清除 $count 条本地记录")
    }

    // ── 注销账号弹窗 ──────────────────────────────────────────────────────────
    private fun showDeleteAccountDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠ 注销账号")
            .setMessage("此操作将永久删除您的账号，登录后将无法再使用该用户名。\n\n但历史检测数据将保留用于路面养护分析。")
            .setPositiveButton("确认注销") { _, _ ->
                // 二次确认
                AlertDialog.Builder(this)
                    .setTitle("最后确认")
                    .setMessage("账号删除后无法恢复，确定注销吗？")
                    .setPositiveButton("永久注销") { _, _ -> doDeleteAccount() }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doDeleteAccount() {
        val token = TokenManager.getToken()
        android.util.Log.d("Profile", "注销Token: $token")  // ← 加这行
        CoroutineScope(Dispatchers.IO).launch {
            val result = AuthApiClient.deleteAccount()
            withContext(Dispatchers.Main) {
                if (result.success) {
                    TokenManager.clear()
                    toast("账号已注销")
                    goLogin()
                } else {
                    toast(result.message)
                }
            }
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────
    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}