package com.example.pavementdetection.map

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.*
import com.example.pavementdetection.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 本地病害地图页
 *
 * 读取 eventgps.txt，解析所有事件坐标，在高德地图上打点。
 * 颜色区分：视觉触发（蓝色）、IMU触发（红色）
 * 点击 marker → 弹出底部卡片，显示时间、触发方式、坐标
 */
class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var aMap: AMap

    // 底部信息卡片
    private lateinit var cardPanel: LinearLayout
    private lateinit var tvCardTime: TextView
    private lateinit var tvCardType: TextView
    private lateinit var tvCardLocation: TextView
    private lateinit var tvCardDefect: TextView
    private lateinit var tvEventCount: TextView

    // 解析出的所有事件
    data class EventPoint(
        val time: String,
        val lat: Double,
        val lng: Double,
        val isVisual: Boolean,   // true=视觉触发，false=IMU触发
        val defectType: String   // 从对应事件目录 yolo_result.txt 读取，可能为空
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        // 地图 View 必须在 onCreate 调用 onCreate
        mapView = findViewById(R.id.mapView)
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        mapView.onCreate(savedInstanceState)
        aMap = mapView.map

        cardPanel       = findViewById(R.id.cardPanel)
        tvCardTime      = findViewById(R.id.tvCardTime)
        tvCardType      = findViewById(R.id.tvCardType)
        tvCardLocation  = findViewById(R.id.tvCardLocation)
        tvCardDefect    = findViewById(R.id.tvCardDefect)
        tvEventCount    = findViewById(R.id.tvEventCount)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        cardPanel.setOnClickListener { cardPanel.visibility = View.GONE }

        setupMap()
        loadAndPlotEvents()
    }

    // ── 地图基础配置 ──────────────────────────────────────────────────────────
    private fun setupMap() {
        aMap.uiSettings.apply {
            isZoomControlsEnabled = true
            isMyLocationButtonEnabled = false
            isScaleControlsEnabled = true
        }
        aMap.isMyLocationEnabled = true
        aMap.myLocationStyle = MyLocationStyle().apply {
            myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
            interval(2000)
        }

        // Marker 点击事件
        aMap.setOnMarkerClickListener { marker ->
            val event = marker.`object` as? EventPoint
            if (event != null) showCard(event)
            true
        }

        // 点击地图空白处收起卡片
        aMap.setOnMapClickListener { cardPanel.visibility = View.GONE }
    }

    // ── 读取 eventgps.txt 并解析 ─────────────────────────────────────────────
    private fun loadAndPlotEvents() {
        val gpsFile = File(getExternalFilesDir(null), "RoadDataCapture/eventgps.txt")
        if (!gpsFile.exists()) {
            tvEventCount.text = "暂无事件记录"
            return
        }

        val events = mutableListOf<EventPoint>()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        gpsFile.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                // 格式：2026-04-25 22:57:02, 32.157427,118.701679 [视觉触发]
                //   或：2026-04-25 22:57:07, 32.157427,118.701679
                val isVisual = line.contains("[视觉触发]")
                val clean = line.replace("[视觉触发]", "").trim()

                // 分割时间和坐标
                val commaIdx = clean.indexOf(',')          // 第一个逗号后是坐标部分
                if (commaIdx < 0) return@forEachLine

                val timePart  = clean.substring(0, commaIdx).trim()
                val coordPart = clean.substring(commaIdx + 1).trim()

                val coords = coordPart.split(",")
                if (coords.size < 2) return@forEachLine

                val lat = coords[0].trim().toDoubleOrNull() ?: return@forEachLine
                val lng = coords[1].trim().toDoubleOrNull() ?: return@forEachLine

                // 时间格式化（去掉可能的毫秒或多余空格）
                val timeClean = timePart.trim()

                events.add(EventPoint(timeClean, lat, lng, isVisual, ""))
            } catch (e: Exception) {
                Log.w("MapActivity", "解析行失败: $line")
            }
        }

        // 补充病害类型（从对应事件目录读 yolo_result.txt）
        val enriched = events.map { event ->
            val defect = readDefectType(event.time, event.isVisual)
            event.copy(defectType = defect)
        }

        plotMarkers(enriched)
        tvEventCount.text = "共 ${enriched.size} 条事件记录"

        // 自动移动镜头到最新事件
        if (enriched.isNotEmpty()) {
            val last = enriched.last()
            aMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(last.lat, last.lng), 16f)
            )
        }
    }

    // ── 从事件目录读取病害类型 ────────────────────────────────────────────────
    private fun readDefectType(timeStr: String, isVisual: Boolean): String {
        val roadDir = File(getExternalFilesDir(null), "RoadDataCapture")
        if (!roadDir.exists()) return ""

        // 把时间字符串转成文件夹名格式：2026-04-25 22:57:02 → 20260425_225702
        return try {
            val sdfIn  = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val sdfOut = SimpleDateFormat("yyyyMMdd_HHmmss",       Locale.getDefault())
            val date   = sdfIn.parse(timeStr) ?: return ""
            val ts     = sdfOut.format(date)

            val prefix  = if (isVisual) "SideEvent_$ts" else "Event_$ts"
            val dir     = File(roadDir, prefix)
            val yoloTxt = File(dir, "yolo_result.txt")

            if (!yoloTxt.exists()) return ""

            // 从 yolo_result.txt 里找"病害类型: xxx"这行
            yoloTxt.readLines()
                .firstOrNull { it.startsWith("病害类型:") }
                ?.substringAfter("病害类型:")
                ?.trim()
                ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ── 在地图上打 Marker ─────────────────────────────────────────────────────
    private fun plotMarkers(events: List<EventPoint>) {
        events.forEach { event ->
            val color = if (event.isVisual)
                BitmapDescriptorFactory.HUE_AZURE       // 视觉触发：蓝色
            else
                BitmapDescriptorFactory.HUE_RED         // IMU触发：红色

            val options = MarkerOptions()
                .position(LatLng(event.lat, event.lng))
                .icon(BitmapDescriptorFactory.defaultMarker(color))
                .title(event.time)

            val marker = aMap.addMarker(options)
            marker?.`object` = event
        }
    }

    // ── 显示底部信息卡片 ──────────────────────────────────────────────────────
    private fun showCard(event: EventPoint) {
        tvCardTime.text     = "时间：${event.time}"
        tvCardType.text     = "触发方式：${if (event.isVisual) "视觉检测" else "IMU颠簸"}"
        tvCardLocation.text = "坐标：${"%.6f".format(event.lat)}, ${"%.6f".format(event.lng)}"
        tvCardDefect.text   = if (event.defectType.isNotEmpty())
            "病害类型：${event.defectType}"
        else
            "病害类型：未识别"

        cardPanel.visibility = View.VISIBLE
    }

    // ── 生命周期转发（高德地图必须） ──────────────────────────────────────────
    override fun onResume()  { super.onResume();  mapView.onResume()  }
    override fun onPause() {
        mapView.onPause()
        super.onPause()    // ← 同样先 mapView 后 super
    }
    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()   // ← mapView.onDestroy() 必须在 super 之前
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
}