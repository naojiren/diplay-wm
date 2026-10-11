# Android 8 / 8.1 车机适配

目标设备信息：T3，Android 8.1.0（API 27），系统版本 V6.4.3_20181201.103629_FG-BFD1。
交付版本：0.2.14（versionCode 33），使用 mobile 模块，不使用 Android Automotive 模块。

## 参考移植包的修正（0.2.14）

0.2.13 真机仍报 USBMUX interface 1 / errno 2。对比桌面 0.2.0 移植包后，修正旧系统上“只凭手机读回值就跳过系统配置选择或接受失败调用”的判断，始终先执行配置选择再申请接口，并把这些底层步骤接入应用诊断日志。详见 `ANDROID8-REFERENCE-COMPARISON.md`。
这是有回归测试覆盖的代码修正，不代表已在 T3 真机完成 USB 或无线投屏。参考包是否在该车机实测成功仍待确认。

## 真机报错修复（0.2.13）

- 用户真机反馈无线 `EADDRINUSE`，USB `NCM data interface 3 / errno 2 / activeConfig=1`。此前模拟器验证未覆盖这两个真机失败点。
- USB 打开流程原来没有调用已存在的兼容配置选择器，且配置切换失败后继续申请接口。现在先切换并检查当前配置，再申请 USBMUX/NCM；配置错误时不再继续。
- 旧内核配置切换使用 usbfs ioctl，同时更新设备和内核接口表，不使用仅向设备发送的原始 SET_CONFIGURATION 控制请求绕过内核状态。
- usbfs 配置切换被内核驱动占用时，在已授权的同一 iPhone fd 上解绑内核驱动后重试；不抢占其他 usbfs 用户，失败时尝试恢复解绑的驱动。
- AirPlay 控制、媒体、时钟等动态端口在端口 0 返回 EADDRINUSE 后，有界尝试显式高位端口，每次失败都关闭 socket，成功使用的端口仍按协议返回给手机。
- 无线启动错误记录具体阶段，区分热点、AirPlay、Bonjour、RFCOMM 和 iAP2。照片没有堆栈或版本号，尚不能确认原始无线错误来自哪个阶段，不能把端口回退测试称为该车机无线问题已验证解决。

usbfs 行为依据：[Android 内核 devio.c](https://android.googlesource.com/kernel/common/+/01c6460f968d7b57fc6f98adb587952628c6e099/drivers/usb/core/devio.c)，包括 claimintf 的 ENOENT 和 proc_setconfig 的驱动占用检查。

## 0.2.12 适配保留

- Android 8 的 USB 异步请求最多 16 KB。限制 USBMUX/NCM 接收请求大小，并对大发送包分段，保留完整数据。
- USBMUX 与 NCM 保留共用 USB 连接的适配，统一分发完成请求，防止两条读取线程取走对方的数据。
- Android 8/9 的 Wi-Fi Direct 使用系统生成的群组；服务不可用或创建失败时尝试本地热点。
- 旧系统本地热点接受系统选择的 2.4 GHz 或 5 GHz，无法读取信道时使用自动信道 0，不再假报信道 36。
- 旧系统本地热点优先使用 IPv4，已确认的新热点接口不因缺少 MAC 地址而被拒绝。
- 独立 Release 构建要求显式选择认证资源与签名，不再自动回退到调试签名。

## 三个模式的首次使用

| 模式 | 首次必要操作 | 设备条件 |
| --- | --- | --- |
| USB 有线 | 用数据线连接 iPhone，在车机允许 USB / VPN 请求，在手机确认信任及 CarPlay | 车机 USB 口支持数据及 USB Host |
| Wi-Fi Direct 无线 | 打开车机 Wi-Fi、蓝牙和定位，允许定位权限，配对并选择 iPhone | 固件提供可用的 Android 蓝牙及 P2P 服务；P2P 失败时尝试系统本地热点 |
| 车机热点无线 | 打开系统热点，在应用保存相同的名称和密码，再配对并选择 iPhone | 热点运行在这台车机上，手机可以加入；旧设备可使用 2.4 GHz |

Android 和 iPhone 的首次授权不能由 APK 自动代替。系统热点密码也不能从图片推断。
安装包应包含本地认证资源，正常连接不需要另配认证服务器。

## 验证边界

自动化测试覆盖 USB 16 KB 边界、并发完成分发、热点频段策略以及 API 26/27 的热点取消、超时和模式保存。
模拟器可以检查安装、启动、认证初始化、界面与模式入口，不能验证 T3 的 USB 驱动、蓝牙模块、Wi-Fi 芯片或真实 iPhone 的 CarPlay 握手。
仅凭系统信息照片，无法承诺所有 T3 固件均可完成三个模式的真实投屏。最终交付记录应以实际执行结果为准。

USB 限制依据：[Android UsbRequest 官方文档](https://developer.android.com/reference/android/hardware/usb/UsbRequest)。
