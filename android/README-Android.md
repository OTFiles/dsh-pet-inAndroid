# dsh-pet 桌宠 · Android 移植版

将桌面端 [dsh-pet-indesktop](https://github.com/MerZlin/dsh-pet-indesktop)（Python + PySide6）
完整移植到 Android 的原生应用：**Kotlin + Jetpack Compose (Material 3)**，透明悬浮窗桌宠可显示在
任意应用之上。

- **最低 Android 9（API 28）**，targetSdk 34
- **APK 内不含 ffmpeg 等任何重型组件** —— 透明视频素材在 GitHub Actions 构建机上一次性预处理
- 构建与发布由 **GitHub Actions** 完成（本仓库 `.github/workflows/build-android.yml`）

## 功能对照（桌面端 → Android）

| 桌面端功能 | Android 移植 |
|---|---|
| 透明无边框置顶窗口 | ✅ 悬浮窗前台服务（`TYPE_APPLICATION_OVERLAY`），显示在任意应用之上 |
| 91 段透明 WebM 动画 | ✅ 构建期重编码为「旁路 alpha」h264（左半 RGB + 右半灰度 alpha），运行时 OpenGL shader 合成透明像素；任何设备都能硬解，**体积反而比原 WebM 更小（约 0.5×）** |
| 动画链状态机（30% 待机 / 10% 转向 / 40% 动作 / 20% 移动） | ✅ `PetEngine` 1:1 移植 |
| 转向翻转朝向 + 水平镜像（含文字动画不镜像） | ✅ |
| 屏幕漫游（移动动画前后 2s 不动，位置插值） | ✅ 按 dp 换算，自动钳制屏幕内 |
| 点击 Q 弹 + 点击回应动画 + 音效 | ✅ 点击 = Q 弹挤压 + 随机回应动画 + click.wav |
| 拖拽 / SHIFT+拖拽 / 锁定位置 | ✅ 拖动跟手；「仅长按可拖动」「锁定位置」开关 |
| 拖动物理（惯性/抛出/重力/反弹/摩擦） | ✅ `PetEngine` 1:1 移植（估速 + 弹簧 + 抛掷积分） |
| 不移动 / 播放速度 / 动画间隔 | ✅ 设置与菜单实时生效 |
| 4 档大小 / 不透明度 10–100% / 左右朝向 | ✅ |
| 系统托盘 | ✅ 前台服务常驻通知（AI 对话 / 退出 / 点击开设置） |
| 右键菜单（新版现代菜单） | ✅ 长按桌宠弹出 MD3 菜单（对话/动画集/速度/大小/物理/余额/更新/多开/快捷启动/设置/退出） |
| 自言自语气泡（多风格/定时/图片） | ✅ 气泡悬浮层锚定桌宠上方，5 种风格 |
| AI 对话（OpenAI 兼容 SSE 流式） | ✅ `ChatActivity`：会话管理 + 流式输出 + 测试连接 |
| DeepSeek 余额查询 | ✅ 气泡/设置页显示 |
| 检查更新 | ✅ GitHub Releases API + 气泡提示 |
| 生小肥鱼（多开） | ✅ 多实例悬浮窗服务，位置/朝向隔离，新实例自动错位 |
| 欧鲸鲸彩蛋 | ✅ 随机图片弹窗（可层叠） |
| 快捷启动 | ✅ 长按菜单启动已选应用 |
| 开机自启 | ✅ `BOOT_COMPLETED` 接收器 + 设置开关 |
| 窗口透明区域鼠标穿透 | ➖ 触屏设备整窗为点击区（拖拽目标更大，体验更佳） |
| 主动识屏 / Agent 联动（DSH 桥接） | ➖ Windows 专属，无对应场景 |
| 直播捕获兼容模式 | ➖ 无直播场景 |

## Android 专属新增

- **MD3 界面**：设置页（常规/桌宠行为/外观/AI 对话/快捷启动/关于）+ 长按菜单 + 聊天界面全部 Material 3。
- **毛玻璃效果（默认关闭）**：设置中可开启；Android 12+ 用 `RenderEffect` 真模糊，低版本回退半透明。
- **隐藏后台（默认开启）**：设置中可关闭；通过运行时切换两个 launcher `activity-alias`
  （`LauncherNormal` / `LauncherHidden`，后者声明 `android:excludeFromRecents`）实现——
  开启后应用不显示在最近任务列表，且同一时刻只有一个启动图标。
- **忽略电池优化**：一键引导，防止 OEM 杀后台。
- **前台服务常驻通知**：即使打开全屏应用，桌宠依然在最上层陪伴。

## 安装

1. 在 [Releases](https://github.com/OTFiles/dsh-pet-inAndroid/releases) 或 Actions Artifact
   下载 `dsh-pet-android-*.apk`。
2. 侧载安装（允许「未知来源」）。
3. 首次打开 → 设置 → 授权「悬浮窗权限」→ 打开桌宠开关。
4. 建议开启「忽略电池优化」+「开机自启」，并允许通知（Android 13+）。

## 操作

| 手势 | 效果 |
|---|---|
| 点击 | Q 弹 + 随机点击回应动画 |
| 拖动 | 移动桌宠（可开物理抛掷） |
| 长按 | MD3 菜单 |
| 长按菜单 → 动画集 | 手动播放任意动画 |

## 开发 / 构建

```bash
# 1) 预处理素材（本机需 ffmpeg；构建机一次性工具，不进 APK）
bash android/scripts/prepare-assets.sh
#    生成 android/app/src/main/assets/pet/（videos + manifest.json + 音效 + 壁纸 + 彩蛋图）

# 2) 编译（需要 Android SDK；本机环境不适合时可交给 GitHub Actions）
cd android && ./gradlew :app:assembleRelease
#    产物：android/app/build/outputs/apk/release/app-release.apk
```

### CI（推荐）

推送 `main` 自动触发 `.github/workflows/build-android.yml`：

1. `compile-check`：快速编译检查（不依赖素材，秒级反馈）。
2. `build`：ffmpeg 预处理素材 → Gradle 构建 → 签名 Release APK → 上传 Artifact；
   推送 `v*` 标签（或 workflow_dispatch 指定 tag）时自动创建 GitHub Release。

签名：仓库内 `android/keystore/dshpet-release.keystore`（口令 `dshpet123`，见
`android/app/build.gradle.kts`），保证各次构建签名一致，可原地升级。

## 已知差异与限制

- 触屏无右键：`shift_drag` 语义映射为「仅长按可拖动」。
- 素材为构建时重编码产物（不入库），改素材后需重新运行预处理脚本。
- 个别机型后台限制严格，建议开启忽略电池优化；被系统回收后，通知栏或重新打开应用可恢复。
- 聊天背景主题（内置壁纸/自定义图片）暂未移植（保留素材，后续可加）。
- 桌面端「主动识屏」「Agent 联动」「直播捕获」为 Windows 专属，无对应 Android 场景。

## 目录结构

```
android/
├── app/src/main/
│   ├── java/com/dshpet/android/
│   │   ├── MainActivity.kt          # 设置页（MD3）
│   │   ├── PetApp.kt                # 应用入口/通知渠道/别名同步
│   │   ├── data/PetConfig.kt        # DataStore 设置 + 多开实例状态
│   │   ├── pet/PetOverlayService.kt # 悬浮窗前台服务（手势/多开/通知）
│   │   ├── pet/PetVideoView.kt      # GLSurfaceView + RGB/alpha shader + ExoPlayer
│   │   ├── pet/PetEngine.kt         # 动画链状态机 + 物理（桌面端 1:1 移植）
│   │   ├── pet/PetCatalog.kt        # 动画目录/分类/时长
│   │   ├── pet/PetMenu.kt           # 长按 MD3 菜单
│   │   ├── pet/SpeechBubble.kt      # 自言自语气泡
│   │   ├── pet/EasterEggPopup.kt    # 欧鲸鲸彩蛋
│   │   ├── pet/Balance.kt / Updater.kt / BootReceiver.kt
│   │   └── chat/                    # ChatActivity/ViewModel/SSE 客户端/会话存储
│   ├── res/                         # MD3 主题/图标/布局
│   └── assets/pet/                  # 预处理产物（git 忽略，CI 生成）
├── scripts/prepare-assets.sh        # WebM → 旁路 alpha h264 + manifest
├── keystore/                        # Release 签名
└── gradle wrapper / build 文件
```

## 许可证

MIT（与桌面端一致，见仓库根 LICENSE）。
