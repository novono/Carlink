# OpenCarLink PC

OpenCarLink PC 是 Windows 上的 ICCOA CarLink / OPPO Car+ 真实车机接收端。电脑通过 USB AOA 模拟兼容车机，接收手机实际输出的智慧车载桌面，并把鼠标触控和车机按键回传给手机。

## 已实现

- USB AOA 切换与 WinUSB Bulk 传输。
- ICCOA USB 多路复用及 AUTH/CONTROL 通道。
- P-256 ECDH/ECDSA、PIN HMAC、HKDF、AES-GCM 加密会话。
- RTSP/Wi-Fi Display 协商。
- RTP/PT33、MPEG-TS、H.264 实时解码和真实画面预览。
- UIBC 鼠标点击/拖动、返回、主页、铺满/完整显示和全屏。
- 脱敏诊断日志与可选完整负载记录。

USB 基线已在 OPPO PKT110 / ColorOS 16.1 上验证，协商参数为 1280 x 720、320 DPI、30 FPS。

## 运行

```powershell
.\run.ps1
```

已连接手机时也可以直接自动开始真实 USB 握手：

```powershell
$env:PYTHONPATH = Join-Path (Get-Location) 'src'
.\.venv\Scripts\python.exe -m open_carlink_pc --auto-connect
```

首次运行会创建 `.venv` 并安装依赖。程序打开后：

1. 用支持数据传输的 USB 线连接并解锁手机。
2. 手机 USB 用途选择“文件传输”。
3. 选择“USB 有线（稳定）”，点击“连接 USB CarLink”。
4. 等待 AOA、认证、RTSP 和视频链路建立；状态变为“投屏中”后窗口显示手机的真实车载桌面。
5. 直接在画面中点击或拖动，工具栏可发送返回和车载主页按键。

## Android 无线实验 APK

`android/` 提供 Android 原生 BLE/GATT 与 Wi-Fi Direct 接收端，无需 dongle。现已接入 AUTH、CONTROL、RTSP、RTP/视频、AAC 音频尝试和 UIBC 触控；仍属于需要逐机型验证的实验版本。

当前版本 `0.15.1-no-memory.4` 使用临时认证身份，断开后清理会话并重新等待连接；修复迟到的 RTSP 回调使播放进度回退、关闭播放器的问题。构建、安装、会话清理范围和验证边界见 [Android 接收端说明](docs/ANDROID_RECEIVER.md)。

## Windows 驱动

手机切换到 AOA 后，只为以下数据接口绑定 WinUSB：

```text
USB\VID_18D1&PID_2D01&MI_00
```

不要替换 `MI_01` 的 ADB 驱动，也不要替换普通 OPPO/MTP 设备的整体驱动。详细步骤见 [docs/USB_DRIVER.md](docs/USB_DRIVER.md)。

## 验证与打包

```powershell
$env:PYTHONPATH = Join-Path (Get-Location) 'src'
.\.venv\Scripts\python.exe -m unittest discover -s tests
.\.venv\Scripts\python.exe -m open_carlink_pc --self-test
.\build.ps1
```

日志、身份和认证状态保存在 `%LOCALAPPDATA%\OpenCarLinkPC`。

## 边界

- 无线 BLE/GATT 及 TCP 上层通道保留为实验模式，Windows P2P 组网尚未端到端打通。无线启动会优先使用 WinUSB 原始 HCI（QCA9377 已验证，Realtek `13D3:3558` 为实验支持）；找不到可用控制器时自动回退到 WinRT BLE 兼容后端，仍须以手机实机发现结果为准。替换蓝牙驱动前必须备份原厂驱动，WinUSB 接管期间 Windows 普通蓝牙不可用。
- Windows 接收端暂未实现音频输出、麦克风、蓝牙电话、传感器和方向盘按键。
- 完整负载可能包含导航、媒体、联系人或通话信息，只应在明确需要时开启。
