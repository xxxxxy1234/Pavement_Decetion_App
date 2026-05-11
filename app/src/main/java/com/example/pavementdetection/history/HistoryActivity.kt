package com.example.pavementdetection.history

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.pavementdetection.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    data class EventRecord(
        val dirName: String,        // 文件夹名，如 Event_20260425_225702
        val time: String,           // 格式化时间
        val isVisual: Boolean,      // true=通道B视觉，false=通道A IMU
        val defectType: String,     // 病害类型
        val confidence: String,     // 置信度字符串
        val location: String,       // GPS坐标
        val thumbFile: File?        // 缩略图文件
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val records = loadRecords()

        val tvCount = findViewById<TextView>(R.id.tvCount)
        tvCount.text = "${records.size} 条"

        val emptyView  = findViewById<View>(R.id.emptyView)
        val recycler   = findViewById<RecyclerView>(R.id.recyclerView)

        if (records.isEmpty()) {
            emptyView.visibility  = View.VISIBLE
            recycler.visibility   = View.GONE
        } else {
            emptyView.visibility  = View.GONE
            recycler.visibility   = View.VISIBLE
            recycler.layoutManager = LinearLayoutManager(this)
            recycler.adapter       = HistoryAdapter(records)
        }
    }

    // ── 读取本地事件目录 ──────────────────────────────────────────────────────
    private fun loadRecords(): List<EventRecord> {
        val roadDir = File(getExternalFilesDir(null), "RoadDataCapture")
        if (!roadDir.exists()) return emptyList()

        val sdfDir  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val sdfShow = SimpleDateFormat("MM-dd HH:mm:ss",  Locale.getDefault())

        val records = mutableListOf<EventRecord>()

        roadDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val name = dir.name

            val isVisual = name.startsWith("SideEvent_")
            val isImu    = name.startsWith("Event_")
            if (!isVisual && !isImu) return@forEach

            // 解析时间
            val ts = name.substringAfter("_", "").let {
                if (isVisual) name.substringAfter("SideEvent_") else name.substringAfter("Event_")
            }
            val timeStr = try {
                sdfShow.format(sdfDir.parse(ts) ?: Date())
            } catch (e: Exception) { ts }

            // 读 yolo_result.txt
            var defectType  = "未识别"
            var confidence  = ""
            var location    = ""
            val yoloFile = File(dir, "yolo_result.txt")
            if (yoloFile.exists()) {
                yoloFile.forEachLine { line ->
                    when {
                        line.startsWith("病害类型:") -> defectType = line.substringAfter(":").trim()
                        line.startsWith("置信度:")   -> confidence = line.substringAfter(":").trim()
                        line.startsWith("触发位置:") -> location   = line.substringAfter(":").trim()
                    }
                }
            }

            // 如果 yolo_result 没有位置，读 event_location.txt
            if (location.isEmpty()) {
                val locFile = File(dir, "event_location.txt")
                if (locFile.exists()) location = locFile.readText().trim()
            }

            // 缩略图：优先 frame_0.jpg
            val thumb = File(dir, "frame_0.jpg").takeIf { it.exists() }

            records.add(EventRecord(name, timeStr, isVisual, defectType, confidence, location, thumb))
        }

        // 按时间倒序（最新的在最上面）
        return records.sortedByDescending { it.dirName }
    }

    // ── RecyclerView Adapter ──────────────────────────────────────────────────
    inner class HistoryAdapter(private val items: List<EventRecord>)
        : RecyclerView.Adapter<HistoryAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivThumb     : ImageView = view.findViewById(R.id.ivThumb)
            val tvDefect    : TextView  = view.findViewById(R.id.tvDefectType)
            val tvChannel   : TextView  = view.findViewById(R.id.tvChannel)
            val tvConfidence: TextView  = view.findViewById(R.id.tvConfidence)
            val tvTime      : TextView  = view.findViewById(R.id.tvTime)
            val tvLocation  : TextView  = view.findViewById(R.id.tvLocation)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]

            // 缩略图
            if (item.thumbFile != null) {
                val bmp = BitmapFactory.decodeFile(item.thumbFile.absolutePath)
                holder.ivThumb.setImageBitmap(bmp)
            } else {
                holder.ivThumb.setImageResource(android.R.drawable.ic_menu_camera)
            }

            // 病害类型
            holder.tvDefect.text = item.defectType

            // 通道标签
            if (item.isVisual) {
                holder.tvChannel.text = "视觉"
                holder.tvChannel.setBackgroundColor(0x880077FF.toInt())
            } else {
                holder.tvChannel.text = "IMU"
                holder.tvChannel.setBackgroundColor(0x88FF3B30.toInt())
            }

            // 置信度
            holder.tvConfidence.text = if (item.confidence.isNotEmpty())
                "置信度 ${item.confidence}" else ""

            holder.tvTime.text     = item.time
            holder.tvLocation.text = item.location.ifEmpty { "坐标未知" }

            holder.itemView.setOnClickListener {
                val intent = Intent(this@HistoryActivity, EventDetailActivity::class.java)
                intent.putExtra(EventDetailActivity.EXTRA_DIR_NAME, item.dirName)
                startActivity(intent)
            }
        }
    }
}