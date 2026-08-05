# Windows USB 驱动说明

程序需要向手机发送 Android Open Accessory 控制请求。Windows 默认的 MTP 驱动不一定允许普通程序直接发送这些请求。

建议按以下顺序排查：

1. 手机 USB 用途选择“文件传输/Android Auto”后重试。
2. 关闭会抢占手机 USB 接口的手机助手、ADB 和同步软件。
3. 手机切换到 AOA 后，仅给 `USB\VID_18D1&PID_2D01&MI_00`（Android Accessory Interface）安装 WinUSB。

注意：

- 不要替换 `MI_01` 或标注为 `ADB Interface` 的接口，否则 ADB 会失效。
- 不要替换手机正常模式下的 MTP 接口；AOA `MI_00` 是独立的设备接口。
- 可在设备管理器中卸载该测试驱动并重新插拔手机，恢复厂商驱动。
- 手机进入 AOA 模式后会重新枚举为 Google `18D1:2D00` 或相邻产品 ID；需要绑定的是该 AOA 设备的数据接口。
