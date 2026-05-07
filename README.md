# 路面病害检测 — Pavement Detection App

> 大创项目。手机采集 IMU + GPS + 视频，实时 YOLO 推理检测路面病害，双通道触发保存事件数据并上传服务器。

---

## 项目概览

| 项目 | 内容 |
|------|------|
| 平台 | Android（Kotlin），测试机 Xiaomi Redmi K30，Android 12，API 31 |
| 包名 | `com.example.pavementdetection` |
| 项目名 | `pavement_detection_app` |
| 相机框架 | CameraX（Preview + VideoCapture + ImageAnalysis 三路绑定） |
| 推理框架 | TensorFlow Lite 2.14.0 |
| 模型 | YOLO26n nano，FP32 精度，`.tflite` 格式 |
| 传感器 | 加速度计（TYPE_ACCELEROMETER）+ 陀螺仪（TYPE_GYROSCOPE） |
| 定位 | LocationManager，GPS_PROVIDER + NETWORK_PROVIDER 双源 |
| 网络 | OkHttp 4.12.0，检测事件上传服务器 |

---

## 模型规格

| 参数 | 值 |
|------|----|
| 模型文件 | `assets/yolo26n_float32.tflite` |
| 标签文件 | `assets/labels.txt` |
| 输入 Shape | `[1, 640, 640, 3]`，FP32，RGB，归一化 0~1 |
| 输出 Shape | `[1, 300, 6]`，已做 NMS |
| 输出格式 | 每行 `[x1, y1, x2, y2, conf, cls_id]`，坐标归一化 xyxy |
| 检测类别 | 8 类：crack, patched_crack, pothole, patched_pothole, alligator_crack, patched_alligator_crack, ... |
| 推理线程 | 4线程 CPU（NNAPI Delegate 已启用，当前效果有限） |

---

## 架构：双通道检测

### 通道 A — IMU 颠簸触发

```
加速度超阈值 → 冷却3秒去重 → 快照frameBuffer（最近60帧）
→ 后台线程采样8帧推理 → saveEventData() 保存到 Event_时间戳/
```

- 触发条件：`abs(accY - 9.8) + abs(accZ) > threshold`（默认 3.0 m/s²）
- 每次触发保存：最近 8 帧图像 + IMU 片段 + GPS + YOLO 结果文本 + 上传服务器

### 通道 B — 视觉实时检测

```
每200ms取最新帧 → 后台线程推理 → 更新DetectionOverlayView
→ 检测到病害且冷却5秒 → saveChannelBEvent() 保存到 SideEvent_时间戳/
```

- 实时显示检测框（叠加在预览画面上）
- 过滤规则：`conf > 0.35`，每类保留置信度最高的，最多显示 3 个
- 触发保存后同步上传服务器

### 互斥保护

```kotlin
private val isDetecting = AtomicBoolean(false)
// 通道A和通道B共用，compareAndSet(false, true) 抢占，finally 释放
// 避免 TFLite Interpreter 并发调用导致 SIGSEGV 崩溃
```

---

## 核心文件说明

```
app/src/main/
├── java/com/example/pavementdetection/
│   ├── MainActivity.kt          # 主逻辑：相机、传感器、双通道调度
│   ├── YoloDetector.kt          # TFLite推理封装（ByteBuffer复用、NNAPI）
│   ├── DetectionOverlayView.kt  # 检测框叠加View（坐标系映射）
│   ├── DetectionUploader.kt     # OkHttp上传检测结果到服务器
│   └── SettingsActivity.kt      # 阈值/频率/FPS设置
└── assets/
    ├── yolo26n_float32.tflite   # 模型文件
    └── labels.txt               # 类别标签，每行一个
```

---

## 关键实现细节

### 1. ImageProxy → Bitmap 转换（`toRotatedBitmap`）

**坑1**：CameraX 自带 `toBitmap()` 扩展函数，自定义时必须改名为 `toRotatedBitmap`，否则被库函数覆盖，旋转代码不生效。

**坑2**：直接用 `buffer.remaining()` 拷贝 YUV plane 会包含行间 padding 字节，图像颜色损坏，移动时 conf 明显下降。

```kotlin
private fun ImageProxy.toRotatedBitmap(): Bitmap {
    val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
    val yRowStride = yPlane.rowStride
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride  // 关键：通常为2（interleaved）

    val nv21 = ByteArray(width * height * 3 / 2)

    // 逐行拷贝Y，跳过行尾padding
    val yBuf = yPlane.buffer
    for (row in 0 until height) {
        yBuf.position(row * yRowStride)
        yBuf.get(nv21, row * width, width)
    }

    // 逐像素拷贝UV（NV21格式：V在前U在后）
    val vBuf = vPlane.buffer; val uBuf = uPlane.buffer
    var uvIndex = width * height
    for (row in 0 until height / 2) {
        for (col in 0 until width / 2) {
            val pos = row * uvRowStride + col * uvPixelStride
            nv21[uvIndex++] = vBuf.get(pos)
            nv21[uvIndex++] = uBuf.get(pos)
        }
    }

    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, width, height), 95, out)
    val decoded = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())

    // 后置摄像头竖屏固定旋转90°
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}
```

### 2. 检测框坐标系对齐

**坑**：旋转后 bitmap 宽高互换（原 480×640 → 旋转后 640×480），传给 `DetectionOverlayView` 必须用旋转后的尺寸。

```kotlin
@Volatile private var latestFrameWidth = 1
@Volatile private var latestFrameHeight = 1

// imageAnalyzer 里
val bitmap = imageProxy.toRotatedBitmap()
imageProxy.close()
latestFrameWidth = bitmap.width    // 旋转后的宽
latestFrameHeight = bitmap.height  // 旋转后的高

// 推理完成回调里（用最新帧尺寸，不是推理帧尺寸，防止框位置滞后）
overlayView.frameWidth = latestFrameWidth
overlayView.frameHeight = latestFrameHeight
```

### 3. 推理性能优化

ByteBuffer 和输出数组复用，避免每帧重新分配 ~4MB 内存：

```kotlin
// 类成员，init时分配一次
private val inputBuffer = ByteBuffer
    .allocateDirect(1 * 640 * 640 * 3 * 4)
    .apply { order(ByteOrder.nativeOrder()) }
private val outputArray = Array(1) { Array(300) { FloatArray(6) } }

// 每次推理 clear → 填充 → rewind → 推理
inputBuffer.clear()
// ... 填充像素 ...
inputBuffer.rewind()  // 必须在推理前rewind
```

NNAPI Delegate（已启用，对 FP32 加速有限）：

```kotlin
val options = Interpreter.Options()
try {
    val nnApiDelegate = org.tensorflow.lite.nnapi.NnApiDelegate()
    options.addDelegate(nnApiDelegate)
    options.numThreads = 1
} catch (e: Throwable) {
    options.numThreads = 4
}
interpreter = Interpreter(modelBuffer, options)
```

### 4. parseOutput 注意事项

```kotlin
// 坐标是归一化xyxy（0~1），不是像素值，不是xywh
val x1 = (row[0] * origW).coerceIn(0f, origW)
val y1 = (row[1] * origH).coerceIn(0f, origH)
val x2 = (row[2] * origW).coerceIn(0f, origW)
val y2 = (row[3] * origH).coerceIn(0f, origH)
if (x2 <= x1 || y2 <= y1) continue  // 过滤零面积框
// 模型已做NMS，客户端无需再做
```

---

## 性能数据（Redmi K30）

| 阶段 | 耗时 |
|------|------|
| 图像缩放 | 5~9ms |
| ByteBuffer 预处理 | 20~34ms |
| TFLite 推理（NNAPI） | 293~351ms |
| 输出解析 | 0~1ms |
| **总计** | **318~391ms** |

推理是主要瓶颈，约占总耗时 90%。

---

## 待优化 / 后续计划

### 推理速度优化（按优先级）

| 方案 | 预期效果 | 代价 | 状态 |
|------|----------|------|------|
| GPU Delegate | 推理降至 50~100ms | 需解决 AAR 原生库打包问题 | ❌ 未成功（NoClassDefFoundError） |
| NNAPI Delegate | 理论加速，实测无明显效果 | 无额外依赖 | ✅ 已启用，效果有限 |
| INT8 量化模型 | 推理降至 50~100ms | 需重新导出 .tflite | ⬜ 待尝试（最推荐） |
| 输入尺寸缩小到 320 | 推理降至 80~120ms | 需重新导出 320 输入的模型，轻微损失小目标精度 | ⬜ 待尝试 |

### GPU Delegate 问题记录

- `tensorflow-lite-gpu:2.14.0` AAR 缺少原生库，运行时 `NoClassDefFoundError: GpuDelegateFactory`
- 配合 `tensorflow-lite-gpu-delegate-plugin` 会引发编译期 API 不兼容
- 已尝试 `useLegacyPackaging = true` + `abiFilters` 均无效
- **根本原因**：该版本 GPU AAR 依赖系统预装 OpenCL，Redmi K30 上不完整

### INT8 量化导出参考（Python端，待做）

```python
import tensorflow as tf

converter = tf.lite.TFLiteConverter.from_saved_model("yolo26n_saved_model")
converter.optimizations = [tf.lite.Optimize.DEFAULT]

# 需要代表性数据集用于校准
def representative_dataset():
    for img in calibration_images:
        yield [img]

converter.representative_dataset = representative_dataset
converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
converter.inference_input_type = tf.uint8
converter.inference_output_type = tf.float32  # 输出保持float方便解析

tflite_model = converter.convert()
with open("yolo26n_int8.tflite", "wb") as f:
    f.write(tflite_model)
```

### 后续功能计划

- [ ] INT8 量化模型导出与集成
- [ ] 服务器端管理平台（Web，地图显示病害位置 + 图片）
- [ ] YOLO 多传感器融合（IMU + 视觉联合决策）
- [ ] 检测精度优化（更多训练数据，更大模型）

---

## 数据保存结构

```
/sdcard/Android/data/com.example.pavementdetection/files/RoadDataCapture/
├── Video_20260424_085400.mp4          # 录像文件
├── IMU_20260424_085400.txt            # 全程IMU（AccX,AccY,AccZ,GyroX,GyroY,GyroZ）
├── GPS_20260424_085400.txt            # 全程GPS轨迹（Latitude,Longitude）
├── eventgps.txt                       # 所有事件GPS坐标索引（A+B通道追加写入）
├── Event_20260424_085412/             # 通道A事件（IMU触发）
│   ├── frame_0.jpg ~ frame_7.jpg      # 颠簸前最近8帧
│   ├── event_imu.txt                  # 触发时刻IMU片段
│   ├── event_location.txt             # 触发时GPS坐标
│   └── yolo_result.txt                # 检测结果（类别/置信度/坐标框）
└── SideEvent_20260424_085430/         # 通道B事件（视觉触发）
    ├── frame_0.jpg                    # 触发时刻帧
    ├── event_location.txt
    └── yolo_result.txt
```

---

## 配置项（SettingsActivity）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| IMU 阈值 | 3.0 m/s² | 触发通道A的加速度阈值 |
| 采样率 | 50 Hz | IMU采样频率 |
| 视频帧率 | 30 fps | 录像帧率 |

存储在 SharedPreferences（`config`），App Resume 时重新读取。

---

## 已解决的主要 Bug

| Bug | 现象 | 根因 | 解决方案 |
|-----|------|------|----------|
| 模型输出全为0 | conf 极低，检测不到任何结果 | 图像送入模型前未旋转，模型看到侧置路面 | `toRotatedBitmap()` 末尾加 `Matrix.postRotate(90f)` |
| 旋转代码不生效 | debug 图依然旋转 | 自定义扩展函数名 `toBitmap` 与 CameraX 库函数冲突 | 改名为 `toRotatedBitmap` |
| 图像颜色损坏 | 移动时 conf 明显下降 | YUV plane 含行间 padding，`buffer.remaining()` 多拷贝无效字节 | 改为逐行按 `rowStride`/`pixelStride` 精确拷贝 |
| 检测框位置错乱 | 框和实际目标有偏移 | 传给 overlayView 的是旋转前的宽高 | 改用 `@Volatile latestFrameWidth/Height` 存旋转后尺寸 |
| SIGSEGV 崩溃 | App 随机崩溃 | TFLite Interpreter 非线程安全，通道A/B并发推理 | `synchronized(lock)` + `AtomicBoolean isDetecting` 互斥 |
| 通道A取帧错误 | 保存的不是颠簸时的画面 | `frameBuffer.take(N)` 取的是最旧的帧 | 改为 `frameBuffer.takeLast(N)` |
| GPU Delegate 崩溃 | App 启动即崩 | `NoClassDefFoundError` 未被 `catch(Exception)` 捕获 | 改为 `catch(Throwable)` |
| 包名改后闪退 | 点击即崩 | layout XML 中自定义 View 类名未随包名更新 | 全局替换 XML 中的旧包名引用 |

---

## 依赖版本（app/build.gradle.kts）

```kotlin
// CameraX
val camerax_version = "1.3.1"
implementation("androidx.camera:camera-core:${camerax_version}")
implementation("androidx.camera:camera-camera2:${camerax_version}")
implementation("androidx.camera:camera-lifecycle:${camerax_version}")
implementation("androidx.camera:camera-video:${camerax_version}")
implementation("androidx.camera:camera-view:${camerax_version}")

// TFLite
implementation("org.tensorflow:tensorflow-lite:2.14.0")
implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
// GPU依赖暂时移除（2.14.0在Redmi K30上无法正常加载原生库）

// 网络
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.google.android.gms:play-services-location:21.2.0")
```

```kotlin
// android {} 块内需要加
androidResources {
    noCompress += "tflite"  // 防止tflite文件被压缩导致内存映射失败
}
packaging {
    jniLibs {
        useLegacyPackaging = true
    }
}
```