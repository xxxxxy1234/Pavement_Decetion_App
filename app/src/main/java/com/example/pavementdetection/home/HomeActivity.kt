package com.example.pavementdetection.home

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.example.pavementdetection.R
import com.example.pavementdetection.settings.SettingsActivity
import com.example.pavementdetection.detection.MainActivity
import com.example.pavementdetection.history.HistoryActivity
import com.example.pavementdetection.map.MapActivity
import com.example.pavementdetection.profile.ProfileActivity
import java.io.File

class HomeActivity : AppCompatActivity() {

    private lateinit var tvTotalCount: TextView
    private lateinit var tvImuCount: TextView
    private lateinit var tvVisualCount: TextView
    private lateinit var tvLastEvent: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        tvTotalCount  = findViewById(R.id.tvTotalCount)
        tvImuCount    = findViewById(R.id.tvImuCount)
        tvVisualCount = findViewById(R.id.tvVisualCount)
        tvLastEvent   = findViewById(R.id.tvLastEvent)

        findViewById<CardView>(R.id.cardCapture).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.navHome).setOnClickListener { /* 已在首页 */ }
        findViewById<LinearLayout>(R.id.navMap).setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.navHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.navProfile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadStats()
    }

    private fun loadStats() {
        val gpsFile = File(getExternalFilesDir(null), "RoadDataCapture/eventgps.txt")
        if (!gpsFile.exists()) {
            tvTotalCount.text  = "0"
            tvImuCount.text    = "0"
            tvVisualCount.text = "0"
            tvLastEvent.text   = "暂无检测记录"
            return
        }

        val lines   = gpsFile.readLines().filter { it.isNotBlank() }
        val total   = lines.size
        val visual  = lines.count { it.contains("[视觉触发]") }
        val imu     = total - visual

        tvTotalCount.text  = total.toString()
        tvImuCount.text    = imu.toString()
        tvVisualCount.text = visual.toString()

        val last = lines.lastOrNull()
        if (last != null) {
            val time = last.substringBefore(",").trim()
            val type = if (last.contains("[视觉触发]")) "视觉检测" else "IMU颠簸"
            tvLastEvent.text = "最近记录：$time · $type"
        } else {
            tvLastEvent.text = "暂无检测记录"
        }
    }
}