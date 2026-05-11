package com.example.pavementdetection.history

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.pavementdetection.R
import com.example.pavementdetection.map.MapActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class EventDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DIR_NAME = "dir_name"  // 传入事件文件夹名
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_detail)

        val dirName = intent.getStringExtra(EXTRA_DIR_NAME) ?: run { finish(); return }
        val eventDir = File(getExternalFilesDir(null), "RoadDataCapture/$dirName")
        if (!eventDir.exists()) { finish(); return }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val isVisual = dirName.startsWith("SideEvent_")
        loadImages(eventDir)
        loadDetails(eventDir, dirName, isVisual)
    }

    // ── 图片轮播 ─────────────────────────────────────────────────────────────
    private fun loadImages(eventDir: File) {
        val frames = eventDir.listFiles { f -> f.name.startsWith("frame_") && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name } ?: emptyList()

        val viewPager   = findViewById<ViewPager2>(R.id.viewPager)
        val tvImageIndex = findViewById<TextView>(R.id.tvImageIndex)

        if (frames.isEmpty()) {
            tvImageIndex.text = "无图片"
            return
        }

        viewPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = frames.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val iv = ImageView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                return object : RecyclerView.ViewHolder(iv) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val bmp = BitmapFactory.decodeFile(frames[position].absolutePath)
                (holder.itemView as ImageView).setImageBitmap(bmp)
            }
        }

        tvImageIndex.text = "1 / ${frames.size}"
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                tvImageIndex.text = "${position + 1} / ${frames.size}"
            }
        })
    }

    // ── 详情信息 ─────────────────────────────────────────────────────────────
    private fun loadDetails(eventDir: File, dirName: String, isVisual: Boolean) {
        // ── 时间 ──
        val ts = if (isVisual) dirName.substringAfter("SideEvent_")
        else          dirName.substringAfter("Event_")
        val timeStr = try {
            val sdfIn  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val sdfOut = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdfOut.format(sdfIn.parse(ts) ?: Date())
        } catch (e: Exception) { ts }

        findViewById<TextView>(R.id.tvTime).text = timeStr

        // ── 触发方式 ──
        findViewById<TextView>(R.id.tvChannel).text =
            if (isVisual) "视觉检测（通道 B）" else "IMU 颠簸触发（通道 A）"

        // ── 读 yolo_result.txt ──
        var defectType = "未识别"
        var confidence = 0f
        val yoloFile = File(eventDir, "yolo_result.txt")
        val yoloRaw  = StringBuilder()
        if (yoloFile.exists()) {
            yoloFile.forEachLine { line ->
                when {
                    line.startsWith("病害类型:") -> defectType = line.substringAfter(":").trim()
                    line.startsWith("置信度:")   -> confidence = line.substringAfter(":").trim().toFloatOrNull() ?: 0f
                }
                yoloRaw.appendLine(line)
            }
        }

        // ── 病害类型 ──
        val tvDefect = findViewById<TextView>(R.id.tvDefectType)
        tvDefect.text = if (defectType == "未识别") "未检测到病害" else defectTypeCN(defectType)

        // ── 严重程度标签（按置信度划分） ──
        val tvSeverity = findViewById<TextView>(R.id.tvSeverity)
        val (severityText, severityColor) = when {
            confidence >= 0.7f -> Pair("严重", "#FF3B30")
            confidence >= 0.45f -> Pair("中等", "#FF9500")
            confidence >  0f    -> Pair("轻微", "#34C759")
            else                -> Pair("未知", "#888888")
        }
        tvSeverity.text = severityText
        tvSeverity.setBackgroundColor(Color.parseColor(severityColor))

        // ── 置信度进度条 ──
        val confPercent = (confidence * 100).toInt()
        findViewById<ProgressBar>(R.id.progressConf).progress = confPercent
        findViewById<TextView>(R.id.tvConfValue).text =
            if (confPercent > 0) "$confPercent%" else "—"

        // ── 原始 YOLO 输出 ──
        findViewById<TextView>(R.id.tvYoloRaw).text =
            yoloRaw.toString().trim().ifEmpty { "无检测结果" }

        // ── 位置 ──
        val locFile = File(eventDir, "event_location.txt")
        val location = if (locFile.exists()) locFile.readText().trim() else "坐标未知"
        findViewById<TextView>(R.id.tvLocation).text = location

        // 在地图中查看
        findViewById<TextView>(R.id.btnViewOnMap).setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }

        // ── IMU 数据（仅通道A） ──
        val cardImu = findViewById<CardView>(R.id.cardImu)
        if (!isVisual) {
            val imuFile = File(eventDir, "event_imu.txt")
            if (imuFile.exists()) {
                val lines = imuFile.readLines().take(6)  // 只展示前6行，够说明问题
                val imuText = lines.joinToString("\n")
                    .ifEmpty { "无 IMU 数据" }
                findViewById<TextView>(R.id.tvImu).text = imuText
                cardImu.visibility = View.VISIBLE
            }
        }
    }

    // ── 病害类型中文映射（给用户看的） ──────────────────────────────────────
    private fun defectTypeCN(raw: String): String = when (raw.lowercase()) {
        "crack"                    -> "裂缝"
        "patched_crack"            -> "修补裂缝"
        "pothole"                  -> "坑洞"
        "patched_pothole"          -> "修补坑洞"
        "alligator_crack"          -> "龟裂"
        "patched_alligator_crack"  -> "修补龟裂"
        "manhole"                  -> "井盖"
        "marking"                  -> "路面标线"
        else                       -> raw
    }
}