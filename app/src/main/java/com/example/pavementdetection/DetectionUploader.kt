package com.example.pavementdetection  // 改成你APP的包名

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object DetectionUploader {

    private const val TAG = "DetectionUploader"

    // ⚠改成你电脑的局域网IP，手机和电脑需在同一WiFi

    private const val SERVER_URL = "http://192.168.5.77:8080/api/detection/upload"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 上传一条检测记录
     * @param imageFile 检测帧图片文件（可为null）
     * @param latitude  GPS纬度
     * @param longitude GPS经度
     * @param defectType 病害类型，如 "pothole", "crack"
     * @param confidence 置信度 0~1
     * @param bboxX1/Y1/X2/Y2 检测框（归一化坐标）
     * @param channel  "A" 或 "B"
     * @param deviceId 设备标识
     * @param onResult 回调：成功true，失败false+错误信息
     */
    fun upload(
        imageFile: File?,
        latitude: Double,
        longitude: Double,
        defectType: String,
        confidence: Float,
        bboxX1: Float, bboxY1: Float,
        bboxX2: Float, bboxY2: Float,
        channel: String,
        deviceId: String,
        onResult: (Boolean, String) -> Unit
    ) {
        try {
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("latitude",   latitude.toString())
                .addFormDataPart("longitude",  longitude.toString())
                .addFormDataPart("defectType", defectType)
                .addFormDataPart("confidence", confidence.toString())
                .addFormDataPart("bboxX1",     bboxX1.toString())
                .addFormDataPart("bboxY1",     bboxY1.toString())
                .addFormDataPart("bboxX2",     bboxX2.toString())
                .addFormDataPart("bboxY2",     bboxY2.toString())
                .addFormDataPart("channel",    channel)
                .addFormDataPart("deviceId",   deviceId)

            // 如果有图片则附加
            imageFile?.let {
                builder.addFormDataPart(
                    "image", it.name,
                    it.asRequestBody("image/jpeg".toMediaType())
                )
            }

            val request = Request.Builder()
                .url(SERVER_URL)
                .post(builder.build())
                .build()

            // 异步请求，不阻塞主线程
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "上传失败: ${e.message}")
                    onResult(false, e.message ?: "网络错误")
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    Log.d(TAG, "上传响应: $body")
                    if (response.isSuccessful) {
                        onResult(true, "上传成功")
                    } else {
                        onResult(false, "服务器错误: ${response.code}")
                    }
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "上传异常: ${e.message}")
            onResult(false, e.message ?: "未知错误")
        }
    }
}