# 路面病害检测 Android App

> 大创项目 · 基于智能网联汽车的路面病害检测系统 — Android 客户端  
> 平台：Xiaomi Redmi K30 · Android 12 · API 31  
> 包名：`com.example.pavementdetection`

---

## 项目简介

本项目面向城市道路养护场景，开发了一套运行于 Android 手机的路面病害实时检测系统。通过车载手机同步采集 IMU、GPS 与视频数据，结合 YOLO 目标检测模型，实现对裂缝、坑洞等 8 类路面病害的自动识别与事件记录，检测结果实时上传服务器，并通过完整的用户界面供后续查看与分析。

---

## 技术栈

| 模块 | 技术选型 | 版本 |
|------|----------|------|
| 开发语言 | Kotlin | — |
| 相机框架 | CameraX | 1.3.1 |
| 推理框架 | TensorFlow Lite | 2.14.0 |
| 检测模型 | YOLO26n nano FP32 | `.tflite` |
| 地图 | 高德地图 3D SDK | 9.8.2 |
| 传感器 | Android SensorManager | 加速度计 + 陀螺仪 |
| 定位 | LocationManager | GPS + 网络双源 |
| 网络传输 | OkHttp | 4.12.0 |

---

## 应用结构

```
HomeActivity（首页·导航中心）
├── 检测统计：总事件数 / IMU触发 / 视觉触发
├── 开始采集 → MainActivity（相机 + 双通道检测）
├── 底部导航
│   ├── 首页
│   ├── 地图 → MapActivity（病害分布地图）
│   └── 记录 → HistoryActivity（历史事件列表）
│              └── 点击 → EventDetailActivity（事件详情）
└── 设置 → SettingsActivity（阈值 / 采样率 / 帧率）
```

---

## 核心功能

### 1. 双通道病害检测

系统实现两条并行检测通道，互补覆盖不同漏检场景：

**通道 A — IMU 颠簸触发**

```
加速度超阈值 → 冷却3秒去重 → 截取frameBuffer最近8帧
→ 后台线程采样推理 → 保存 Event_时间戳/ → 上传服务器
```

- 触发条件：`abs(accY - 9.8) + abs(accZ) > threshold`（默认 3.0 m/s²）
- 保存内容：8帧图像 + IMU片段 + GPS坐标 + YOLO结果

**通道 B — 视觉实时检测**

```
每200ms取最新帧 → 后台推理 → 更新DetectionOverlayView
→ 检测到病害且冷却5秒 → 保存 SideEvent_时间戳/ → 上传服务器
```

- 实时检测框叠加在预览画面上
- 过滤规则：`conf > 0.35`，每类保留最高置信度，最多显示 3 个框

**互斥保护**

```kotlin
private val isDetecting = AtomicBoolean(false)
// 通道A/B共用，compareAndSet(false, true) 抢占，finally 释放
// 防止 TFLite Interpreter 并发调用导致 SIGSEGV 崩溃
```

---

### 2. YOLO 模型集成

| 参数 | 值 |
|------|----|
| 模型文件 | `assets/yolo26n_float32.tflite` |
| 输入 Shape | `[1, 640, 640, 3]`，FP32，RGB 归一化 0~1 |
| 输出 Shape | `[1, 300, 6]`，已内置 NMS |
| 输出格式 | `[x1, y1, x2, y2, conf, cls_id]`，归一化 xyxy |
| 检测类别 | 8 类路面病害（裂缝、坑洞、龟裂及其修补态等） |
| 推理线程 | NNAPI Delegate（回退 4 线程 CPU） |

**实测推理性能（Redmi K30 骁龙730G）：**

| 阶段 | 耗时 |
|------|------|
| 图像缩放 | 5 ~ 9 ms |
| ByteBuffer 预处理 | 20 ~ 34 ms |
| TFLite 推理 | 293 ~ 351 ms |
| 输出解析 | 0 ~ 1 ms |
| **全流程合计** | **318 ~ 391 ms** |

---

### 3. 图像处理流水线

- **YUV → NV21 精确转换**：逐行按 `rowStride`/`pixelStride` 拷贝，跳过行间 padding，解决图像颜色损坏问题
- **自动旋转**：后置摄像头竖屏施加 90° 旋转，修复模型输出全零问题
- **函数命名隔离**：自定义扩展函数命名为 `toRotatedBitmap()`，避免与 CameraX 内置 `toBitmap()` 冲突
- **ByteBuffer 复用**：预分配 ~4.9MB 输入缓冲区，避免每帧重新分配内存

---

### 4. 病害分布地图

- 集成高德地图 3D SDK，读取本机 `eventgps.txt` 在地图上打点
- 红色 marker = IMU 颠簸触发，蓝色 marker = 视觉检测触发
- 点击 marker 显示底部信息卡片：时间、触发方式、病害类型、GPS 坐标
- 独立进程运行（`android:process=":map"`），避免与 CameraX GL 上下文冲突

---

### 5. 历史记录与事件详情

- 历史列表：读取本地所有事件目录，展示缩略图、病害类型、置信度、时间、坐标
- 事件详情：
    - 多帧图片左右滑动查看
    - 严重程度自动判断（置信度 ≥ 0.7 → 严重，≥ 0.45 → 中等，> 0 → 轻微）
    - 置信度进度条可视化
    - 通道 A 事件展示 IMU 传感器数据
    - 一键跳转至地图查看位置

---

### 6. 数据采集与上传

```
RoadDataCapture/
├── Video_*.mp4              # 全程录像
├── IMU_*.txt                # 全程 IMU（AccX/Y/Z + GyroX/Y/Z）
├── GPS_*.txt                # 全程 GPS 轨迹
├── eventgps.txt             # 所有事件 GPS 坐标索引
├── Event_时间戳/             # 通道 A 事件
│   ├── frame_0~7.jpg
│   ├── event_imu.txt
│   ├── event_location.txt
│   └── yolo_result.txt
└── SideEvent_时间戳/         # 通道 B 事件
    ├── frame_0.jpg
    ├── event_location.txt
    └── yolo_result.txt
```

上传接口（OkHttp Multipart）：每次事件触发后异步上传图片 + GPS + 病害类型 + 置信度 + 坐标框 + 设备ID。

---

## 已解决的关键技术问题

| 问题 | 现象 | 根因 | 解决方案 |
|------|------|------|----------|
| 模型输出全为零 | 所有帧 conf 极低 | 图像未旋转，模型接收侧置路面图 | `toRotatedBitmap()` 加 `Matrix.postRotate(90°)` |
| 旋转代码不生效 | 加了旋转但依然侧置 | 函数名与 CameraX 内置 `toBitmap()` 冲突 | 改名为 `toRotatedBitmap` |
| 图像颜色损坏 | 移动时 conf 明显下降 | YUV buffer 含行间 padding | 逐行按 `rowStride`/`pixelStride` 精确拷贝 |
| 检测框位置错乱 | 框与目标有明显偏移 | 传给 overlayView 的是旋转前宽高 | `@Volatile` 变量存旋转后尺寸 |
| 随机 SIGSEGV 崩溃 | App 不定期崩溃 | TFLite Interpreter 非线程安全 | `synchronized` + `AtomicBoolean` 双重互斥 |
| 通道 A 取帧错误 | 保存的不是颠簸时画面 | `frameBuffer.take(N)` 取最旧帧 | 改为 `frameBuffer.takeLast(N)` |
| GPU Delegate 崩溃 | App 启动即崩 | `NoClassDefFoundError` 未被捕获 | 改为 `catch(Throwable)` |
| 地图 GLThread 崩溃 | 退出地图页后崩溃 | 高德 GL 上下文与 CameraX Surface 冲突 | MapActivity 独立进程运行 |
| 高德地图白屏 | 地图区域全白 | 未调用隐私合规初始化接口 | Application 中调用 `updatePrivacyShow/Agree` |
| 包名改后闪退 | 改包名后点击即崩 | layout XML 中自定义 View 类名未更新 | 全局替换 XML 中旧包名引用 |

---

## 配置项

| 参数 | 默认值 | 说明 |
|------|--------|------|
| IMU 阈值 | 3.0 m/s² | 触发通道 A 的加速度阈值 |
| IMU 采样率 | 50 Hz | 传感器采样频率 |
| 视频帧率 | 30 fps | 录像帧率 |

配置存储于 SharedPreferences（`config`），App Resume 时重新读取生效。

---

## 待优化方向

### 推理速度

| 方案 | 预期推理耗时 | 状态 |
|------|------------|------|
| INT8 量化模型（640 输入） | 80 ~ 150 ms | ⬜ 待导出验证 |
| FP32 缩小输入至 320 | 100 ~ 150 ms | ⬜ 待导出验证 |
| INT8 + 320 输入组合 | 40 ~ 80 ms | ⬜ 待导出验证 |
| GPU Delegate | 50 ~ 100 ms | ❌ 受限于设备 OpenCL 支持 |

### 功能计划

- [ ] 登录注册（对接服务端用户系统）
- [ ] 拉取全局病害地图（展示所有用户上传数据）
- [ ] 设置页 UI 改版（适配深色风格）
- [ ] 相机页优化（加返回首页入口）
- [ ] INT8 量化模型导出与集成
- [ ] 服务器端管理平台（Web，地图展示 + 图片管理）

---

## 依赖清单

```kotlin
// CameraX 1.3.1
androidx.camera:camera-core / camera-camera2 / camera-lifecycle / camera-video / camera-view

// TFLite
org.tensorflow:tensorflow-lite:2.14.0
org.tensorflow:tensorflow-lite-support:0.4.4

// 高德地图
com.amap.api:3dmap:9.8.2

// UI
androidx.recyclerview:recyclerview:1.3.2
androidx.cardview:cardview:1.0.0
androidx.viewpager2:viewpager2:1.0.0

// 网络 & 定位
com.squareup.okhttp3:okhttp:4.12.0
com.google.android.gms:play-services-location:21.2.0
```

```kotlin
// android {} 块内配置
androidResources {
    noCompress += "tflite"  // 防止 tflite 文件被压缩导致内存映射失败
}
packaging {
    jniLibs { useLegacyPackaging = true }
}
```

---

*最后更新：2026年5月*