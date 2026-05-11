package com.example.pavementdetection.settings

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.pavementdetection.R

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etT   = findViewById<EditText>(R.id.etThreshold)
        val etF   = findViewById<EditText>(R.id.etFrequency)
        val etFps = findViewById<EditText>(R.id.etFps)
        val btnSave = findViewById<Button>(R.id.btnSave)

        // 新增：顶部返回按钮
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)

        // 回显当前值
        etT.setText(prefs.getFloat("threshold", 3.0f).toString())
        etF.setText(prefs.getInt("frequency", 50).toString())
        etFps.setText(prefs.getInt("video_fps", 30).toString())

        btnSave.setOnClickListener {
            val t        = etT.text.toString().toFloatOrNull()
            val f        = etF.text.toString().toIntOrNull()
            val fpsValue = etFps.text.toString().toIntOrNull()

            if (t != null && f != null && fpsValue != null) {
                prefs.edit().apply {
                    putFloat("threshold", t)
                    putInt("frequency", f)
                    putInt("video_fps", fpsValue)
                    apply()
                }
                Toast.makeText(this, "保存成功，返回主页生效", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "请输入正确的数字格式", Toast.LENGTH_SHORT).show()
            }
        }
    }
}