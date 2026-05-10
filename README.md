# 路面卫士 — 路面病害检测 Android App

> 大创项目 · 基于智能网联汽车的路面病害检测系统 — Android 客户端  
> 平台：Xiaomi Redmi K30 · Android 12 · API 31  
> 包名：`com.example.pavementdetection`  
> App 名称：路面卫士

---

## 项目简介

面向城市道路养护场景，开发了一套运行于 Android 手机的路面病害实时检测系统。通过车载手机同步采集 IMU、GPS 与视频数据，结合 YOLO 目标检测模型，实现对裂缝、坑洞等 8 类路面病害的自动识别与事件记录，检测结果实时上传服务器，并通过完整的用户界面供查看与分析。

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
├── 检测统计卡片：总事件数 / IMU触发 / 视觉触发
├── 开始采集按钮 → MainActivity（相机 + 双通道检测）
├── 右上角设置 → SettingsActivity
└── 底部导航栏
    ├── 首页
    ├── 地图 → MapActivity（病害分布地图，独立进程）
    └── 记录 → HistoryActivity（历史事件列表）
               └── 点击条目 → EventDetailActivity（事件详情）
```

---

## 核心功能

### 1. 双通道病害检测

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
// compareAndSet(false, true) 抢占，finally 释放
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
| 检测类别 | 8 类（crack, patched_crack, pothole, patched_pothole, alligator_crack 等） |
| 推理加速 | NNAPI Delegate（回退 4 线程 CPU） |

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

- **YUV → NV21 精确转换**：逐行按 `rowStride`/`pixelStride` 拷贝，跳过行间 padding
- **自动旋转**：后置摄像头竖屏施加 90° 旋转，函数命名为 `toRotatedBitmap()` 避免与 CameraX 内置 `toBitmap()` 冲突
- **ByteBuffer 复用**：预分配 ~4.9MB 输入缓冲区，避免每帧重新分配内存
- **坐标系对齐**：用 `@Volatile` 变量存旋转后帧尺寸，供 `DetectionOverlayView` 实时同步

---

### 4. 病害分布地图

- 高德地图 3D SDK，读取本机 `eventgps.txt` 在地图上打点
- 红色 marker = IMU 触发，蓝色 marker = 视觉触发
- 点击 marker 弹出底部卡片：时间、触发方式、病害类型、GPS 坐标
- `android:process=":map"` 独立进程运行，避免与 CameraX GL 上下文冲突
- 高德 SDK 必须在 `Application.onCreate()` 调用 `updatePrivacyShow/Agree` 否则地图白屏

---

### 5. 历史记录与事件详情

**历史列表（HistoryActivity）**
- 读取本地所有 `Event_*` 和 `SideEvent_*` 目录
- 每条显示：缩略图、病害类型、置信度、时间、GPS坐标、通道标签（IMU/视觉）
- 按时间倒序排列

**事件详情（EventDetailActivity）**
- 多帧图片 ViewPager2 左右滑动
- 严重程度自动判断：置信度 ≥ 0.7 → 严重，≥ 0.45 → 中等，> 0 → 轻微
- 置信度进度条可视化
- 通道 A 事件展示 IMU 传感器数据片段
- 一键跳转地图查看位置

---

### 6. 首页统计（HomeActivity）

- 实时统计 `eventgps.txt` 中的事件总数、IMU 触发数、视觉触发数
- 显示最近一次事件时间和触发方式
- 底部导航统一管理页面跳转，`onResume` 时自动刷新数据

---

### 7. 数据采集与上传

```
RoadDataCapture/
├── Video_*.mp4              # 全程录像
├── IMU_*.txt                # 全程 IMU（AccX/Y/Z + GyroX/Y/Z）
├── GPS_*.txt                # 全程 GPS 轨迹
├── eventgps.txt             # 所有事件 GPS 坐标索引（A+B通道追加写入）
├── Event_时间戳/             # 通道 A 事件（IMU触发）
│   ├── frame_0~7.jpg        # 颠簸前最近8帧
│   ├── event_imu.txt
│   ├── event_location.txt
│   └── yolo_result.txt      # 病害类型/置信度/坐标框
└── SideEvent_时间戳/         # 通道 B 事件（视觉触发）
    ├── frame_0.jpg
    ├── event_location.txt
    └── yolo_result.txt
```

上传接口（OkHttp Multipart）：图片 + GPS + 病害类型 + 置信度 + 坐标框 + 设备ID + 通道标识。

---

## 配置项（SettingsActivity）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| IMU 阈值 | 3.0 m/s² | 触发通道 A 的加速度阈值 |
| IMU 采样率 | 50 Hz | 传感器采样频率 |
| 视频帧率 | 30 fps | 录像帧率，修改后重启采集页生效 |

存储于 SharedPreferences（`config`），App Resume 时重新读取生效。

---

## 已解决的关键技术问题

| 问题 | 现象 | 根因 | 解决方案 |
|------|------|------|----------|
| 模型输出全为零 | conf 极低，无检测结果 | 图像未旋转，模型接收侧置路面图 | `toRotatedBitmap()` 加 `Matrix.postRotate(90°)` |
| 旋转代码不生效 | 依然侧置 | 函数名与 CameraX 内置 `toBitmap()` 冲突 | 改名为 `toRotatedBitmap` |
| 图像颜色损坏 | 移动时 conf 明显下降 | YUV buffer 含行间 padding | 逐行按 `rowStride`/`pixelStride` 精确拷贝 |
| 检测框位置错乱 | 框与目标有明显偏移 | 传给 overlayView 的是旋转前宽高 | `@Volatile` 变量存旋转后尺寸 |
| 随机 SIGSEGV 崩溃 | App 不定期崩溃 | TFLite Interpreter 非线程安全 | `synchronized` + `AtomicBoolean` 双重互斥 |
| 通道 A 取帧错误 | 保存的不是颠簸时画面 | `frameBuffer.take(N)` 取最旧帧 | 改为 `frameBuffer.takeLast(N)` |
| GPU Delegate 崩溃 | App 启动即崩 | `NoClassDefFoundError` 未被捕获 | 改为 `catch(Throwable)` |
| 地图 GLThread 崩溃 | 退出地图页崩溃 | 高德 GL 上下文与 CameraX Surface 冲突 | `MapActivity` 独立进程 `android:process=":map"` |
| 高德地图白屏 | 地图区域全白 | 未调用隐私合规初始化 | `Application` 中调用 `updatePrivacyShow/Agree` |
| 包名改后闪退 | 改包名后点击即崩 | layout XML 中 View 类名未更新 | 全局替换 XML 中旧包名引用 |

---

## 待完成功能

### 登录注册
- 对接服务端用户系统（注册/登录 API）
- JWT Token 存 SharedPreferences，上传请求携带 Token
- 启动流程：`LoginActivity` → `HomeActivity`

### 全局病害地图
- 拉取所有用户上传的病害坐标，在地图上叠加展示
- 区分"我上传的"和"他人上传的" marker 样式

### 推理速度优化

| 方案 | 预期推理耗时 | 状态 |
|------|------------|------|
| INT8 量化模型（640 输入） | 80 ~ 150 ms | ⬜ 导出脚本已备好 |
| FP32 缩小输入至 320 | 100 ~ 150 ms | ⬜ 待验证 |
| INT8 + 320 输入组合 | 40 ~ 80 ms | ⬜ 待验证 |
| GPU Delegate | 50 ~ 100 ms | ❌ 受限于设备 OpenCL 支持 |

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

// UI 组件
androidx.recyclerview:recyclerview:1.3.2
androidx.cardview:cardview:1.0.0
androidx.viewpager2:viewpager2:1.0.0

// 网络 & 定位
com.squareup.okhttp3:okhttp:4.12.0
com.google.android.gms:play-services-location:21.2.0
```

```kotlin
// android {} 块内必须配置
androidResources {
  noCompress += "tflite"  // 防止 tflite 被压缩导致内存映射失败
}
packaging {
  jniLibs { useLegacyPackaging = true }
}
```

---
