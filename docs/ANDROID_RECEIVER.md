# Android ICCOA CarLink 接收端

`android/` 使用 BLE/GATT 引导和 Android 原生 Wi-Fi Direct 组网，接收支持 ICCOA CarLink 的手机投屏，无需 dongle。接收设备需要 Android 10 / API 29 及以上，并支持 BLE 外设模式和 Wi-Fi Direct。手机需要提供兼容的车联功能；兼容性仍需逐机型实测。

当前版本为 `0.15.1-no-memory.4`，调试包名为 `com.opencarlink.receiver.debug`。已实现认证、CONTROL、RTSP、RTP/PT33 与 MPEG-TS/H.264 视频、UIBC 触控和 AAC 解码尝试。

## 构建与安装

准备 JDK 17 或兼容版本、Android SDK 和 API 35 平台。通过 `ANDROID_HOME` 指向 SDK，或在 `android/local.properties` 中设置 `sdk.dir`；该本机配置不提交到仓库。首次构建需要下载 Gradle 和 Maven 依赖。

macOS / Linux：

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.opencarlink.receiver.debug/com.opencarlink.receiver.MainActivity
```

Windows 在 `android` 目录使用 `gradlew.bat` 执行相同 Gradle 任务。仓库包含 Gradle 8.11.1 wrapper 和发行包 SHA-256 校验配置。

打开接收设备的 Wi-Fi、蓝牙及系统要求的定位开关，关闭个人热点，在应用中启动接收并授予附近设备等权限。等待 P2P GO 和 BLE 就绪后，从手机的车联功能搜索接收端；广播名称沿用上游的 `Meizu CarLink`。投屏期间保持接收应用在前台。正常结束时先使用应用的“结束投屏”按钮，让手机收到退出通知。

## 会话清理与断开修复

- 认证密钥、手机公钥、设备标识和 PIN 只保存在当前会话内存中。清理后旧认证对象永久关闭，拒绝迟到写入；每次重新连接都重新认证。
- 启动时清理旧版 `carlink` / `carlink_auth` 配置。新接收会话生成新的车机标识和网络凭据；不提供历史设备快速认证。
- 结束会话先发送加密 CONTROL 退出通知和 RTSP TEARDOWN，再在 300 毫秒期限内关闭传输资源。确认旧 Wi-Fi Direct 群组已移除后才创建新群组；无法确认时显示错误，避免旧会话继续占用接收端。
- 传输异常通过 3 秒宽限和视频 / RTSP 活跃证据确认，兼容正常 BLE 引导结束、空 TCP 探测和短暂通道切换。手机明确退出时立即开始清理。
- 回调、播放器和视频缓冲按会话隔离。单个会话的进度只向前推进，防止视频已到达后，迟到的 RTSP 阶段回调关闭播放器。
- AAC 解码按名称回退；音频失败会显示提示并停用音频，视频和触控继续运行。
- 现场日志只保存在当前 UI 内存中，断开后清空；不再写公共下载目录日志，并迁移删除本应用拥有的旧日志文件。

清理范围是 OpenCarLink 管理的身份、认证记录、会话和日志。普通 APK 无法清除手机端自身的历史记录或 Android 系统全部蓝牙绑定；本应用不创建系统蓝牙绑定。Wi-Fi Direct 使用 [Android 非持久群组配置](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder#enablePersistentMode(boolean))。

## 验证范围

本次构建的 57 项 JVM 单元测试通过，覆盖临时身份清理、迟到写入 / 回调、会话进度回退、视频缓冲隔离、协议退出消息、RTSP 顺序、传输宽限和音频回退等回归场景。

本次 vivo 手机实测中，新版连续投屏约 90 秒，渲染 1,781 帧，观察期间未自动断开。该结果仅为有限时长验证；长期稳定性、两部真实手机轮流接入和 AAC 实际播放仍需进一步验证。单元测试不能替代这些实机测试。
