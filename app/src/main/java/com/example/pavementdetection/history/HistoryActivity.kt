package com.example.pavementdetection.history

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.pavementdetection.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    data class EventRecord(
        val dirName:    String,
        val time:       String,
        val isVisual:   Boolean,
        val defectType: String,
        val confidence: String,
        val location:   String,
        val thumbFile:  File?,
        val dateKey:    String   // "yyyy-MM-dd" 用于日期筛选
    )

    // ── 筛选状态 ──────────────────────────────────────────────────────────────
    private var filterDate:    String? = null   // null = 全部
    private var filterType:    String? = null   // null = 全部
    private var filterChannel: String? = null   // null=全部 "IMU"/"视觉"

    private lateinit var allRecords: List<EventRecord>
    private lateinit var adapter: HistoryAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var tvCount: TextView
    private lateinit var tvFilterHint: TextView
    private lateinit var btnFilterDate: TextView
    private lateinit var btnFilterType: TextView
    private lateinit var btnFilterChannel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        tvCount        = findViewById(R.id.tvCount)
        tvFilterHint   = findViewById(R.id.tvFilterHint)
        btnFilterDate  = findViewById(R.id.btnFilterDate)
        btnFilterType  = findViewById(R.id.btnFilterType)
        btnFilterChannel = findViewById(R.id.btnFilterChannel)
        emptyView      = findViewById(R.id.emptyView)
        recycler       = findViewById(R.id.recyclerView)

        allRecords = loadRecords()
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = HistoryAdapter(allRecords.toMutableList())
        recycler.adapter = adapter
        updateDisplay(allRecords)

        // ── 日期筛选 ──────────────────────────────────────────────────────────
        btnFilterDate.setOnClickListener {
            val cal = Calendar.getInstance()
            val dpd = DatePickerDialog(this, { _, y, m, d ->
                filterDate = "%04d-%02d-%02d".format(y, m + 1, d)
                btnFilterDate.text = "📅 $filterDate"
                btnFilterDate.setTextColor(0xFF2979FF.toInt())
                applyFilter()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))

            // 加"清除"按钮
            dpd.setButton(DatePickerDialog.BUTTON_NEUTRAL, "清除日期") { _, _ ->
                filterDate = null
                btnFilterDate.text = "📅 全部日期"
                btnFilterDate.setTextColor(0xFFAAAAAA.toInt())
                applyFilter()
            }
            dpd.show()
        }

        // ── 类别筛选 ──────────────────────────────────────────────────────────
        btnFilterType.setOnClickListener {
            val types = allRecords.map { it.defectType }
                .filter { it != "未识别" }
                .distinct()
                .sorted()
                .toMutableList()
            types.add(0, "全部类别")

            AlertDialog.Builder(this)
                .setTitle("选择病害类别")
                .setItems(types.toTypedArray()) { _, which ->
                    if (which == 0) {
                        filterType = null
                        btnFilterType.text = "🔍 全部类别"
                        btnFilterType.setTextColor(0xFFAAAAAA.toInt())
                    } else {
                        filterType = types[which]
                        btnFilterType.text = "🔍 ${types[which]}"
                        btnFilterType.setTextColor(0xFF2979FF.toInt())
                    }
                    applyFilter()
                }
                .show()
        }

        // ── 通道筛选 ──────────────────────────────────────────────────────────
        btnFilterChannel.setOnClickListener {
            val options = arrayOf("全部通道", "IMU 触发", "视觉触发")
            AlertDialog.Builder(this)
                .setTitle("选择触发通道")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            filterChannel = null
                            btnFilterChannel.text = "⚡ 全部通道"
                            btnFilterChannel.setTextColor(0xFFAAAAAA.toInt())
                        }
                        1 -> {
                            filterChannel = "IMU"
                            btnFilterChannel.text = "⚡ IMU 触发"
                            btnFilterChannel.setTextColor(0xFFFF3B30.toInt())
                        }
                        2 -> {
                            filterChannel = "视觉"
                            btnFilterChannel.text = "⚡ 视觉触发"
                            btnFilterChannel.setTextColor(0xFF2979FF.toInt())
                        }
                    }
                    applyFilter()
                }
                .show()
        }
    }

    // ── 应用筛选 ──────────────────────────────────────────────────────────────
    private fun applyFilter() {
        var result = allRecords

        filterDate?.let    { date    -> result = result.filter { it.dateKey == date } }
        filterType?.let    { type    -> result = result.filter { it.defectType == type } }
        filterChannel?.let { channel ->
            result = if (channel == "IMU") result.filter { !it.isVisual }
            else result.filter { it.isVisual }
        }

        updateDisplay(result)

        // 筛选提示
        val active = listOfNotNull(filterDate, filterType, filterChannel).size
        if (active > 0) {
            tvFilterHint.text = "已筛选：共 ${result.size} 条（全部 ${allRecords.size} 条）"
            tvFilterHint.visibility = View.VISIBLE
        } else {
            tvFilterHint.visibility = View.GONE
        }
    }

    private fun updateDisplay(records: List<EventRecord>) {
        tvCount.text = "${records.size} 条"
        if (records.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            recycler.visibility  = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recycler.visibility  = View.VISIBLE
            adapter.updateData(records)
        }
    }

    // ── 读取本地事件目录 ──────────────────────────────────────────────────────
    private fun loadRecords(): List<EventRecord> {
        val roadDir = File(getExternalFilesDir(null), "RoadDataCapture")
        if (!roadDir.exists()) return emptyList()

        val sdfDir  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val sdfShow = SimpleDateFormat("MM-dd HH:mm:ss",  Locale.getDefault())
        val sdfDate = SimpleDateFormat("yyyy-MM-dd",      Locale.getDefault())

        val records = mutableListOf<EventRecord>()

        roadDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val name = dir.name

            val isVisual = name.startsWith("SideEvent_")
            val isImu    = name.startsWith("Event_")
            if (!isVisual && !isImu) return@forEach

            val ts = if (isVisual) name.substringAfter("SideEvent_")
            else          name.substringAfter("Event_")

            val parsedDate = try { sdfDir.parse(ts) ?: Date() } catch (e: Exception) { Date() }
            val timeStr    = sdfShow.format(parsedDate)
            val dateKey    = sdfDate.format(parsedDate)

            var defectType = "未识别"
            var confidence = ""
            var location   = ""
            val yoloFile   = File(dir, "yolo_result.txt")
            if (yoloFile.exists()) {
                yoloFile.forEachLine { line ->
                    when {
                        line.startsWith("病害类型:") -> defectType = line.substringAfter(":").trim()
                        line.startsWith("置信度:")   -> confidence = line.substringAfter(":").trim()
                        line.startsWith("触发位置:") -> location   = line.substringAfter(":").trim()
                    }
                }
            }
            if (location.isEmpty()) {
                val locFile = File(dir, "event_location.txt")
                if (locFile.exists()) location = locFile.readText().trim()
            }

            val thumb = File(dir, "frame_0.jpg").takeIf { it.exists() }

            records.add(EventRecord(name, timeStr, isVisual, defectType, confidence, location, thumb, dateKey))
        }

        return records.sortedByDescending { it.dirName }
    }

    // ── RecyclerView Adapter ──────────────────────────────────────────────────
    inner class HistoryAdapter(private val items: MutableList<EventRecord>)
        : RecyclerView.Adapter<HistoryAdapter.VH>() {

        fun updateData(newItems: List<EventRecord>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

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

            if (item.thumbFile != null) {
                val bmp = BitmapFactory.decodeFile(item.thumbFile.absolutePath)
                holder.ivThumb.setImageBitmap(bmp)
            } else {
                holder.ivThumb.setImageResource(android.R.drawable.ic_menu_camera)
            }

            holder.tvDefect.text = item.defectType

            if (item.isVisual) {
                holder.tvChannel.text = "视觉"
                holder.tvChannel.setBackgroundColor(0x880077FF.toInt())
            } else {
                holder.tvChannel.text = "IMU"
                holder.tvChannel.setBackgroundColor(0x88FF3B30.toInt())
            }

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