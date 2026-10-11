# Android 8 移植包对比记录

日期：2026-10-04。用户在 0.2.13 真机上仍遇到 `Android could not claim USBMUX interface 1 (usbfs errno 2)`，要求参考桌面移植版。

## 参考文件

- 路径：`C:/Users/A/Desktop/DiPlay-移植0.2.0_安卓8可用.apk`
- SHA-256：`583cfc77dbec839dedb464ab2bb7832bd24deb330e70d7a16e887ef706586df1`
- 包名：`com.shihab.diplay.hudtest`
- 版本：0.2.0，versionCode 20，最低 API 24，目标 API 37。
- 原文件未修改。分析副本及反编译文件仅保存在系统临时目录，没有整体复制反编译代码、认证材料或原生库到项目。
- 已对比 IphoneUsbHost、IphoneCarPlayConfiguration、NcmUsbBridge、CarPlayController 的 USB 打开方法、CarPlayVpnService 的监听方法。部分无关的大方法反编译失败，不将反编译结果当作完整源码。

尚未收到该参考包在用户这台 T3 上成功投屏的确认。文件名中的“安卓8可用”不等同于三种模式真机验证证据。

## 关键差异

| 项目 | 参考包 | 0.2.13 |
| --- | --- | --- |
| USB 配置识别 | 按 USBMUX 与 CDC NCM 描述符选择，不固定配置号 | 同样按描述符选择 |
| 新 USB 连接的配置选择 | 每次都调用系统 setConfiguration | 可能根据 GET_CONFIGURATION 读回值提前返回成功 |
| setConfiguration 返回 false | 继续尝试 claimInterface | 存在读回值吻合就认定成功的路径，可能跳过 native 回退 |
| USBMUX / NCM 连接 | 两次 openDevice，分别申请接口 | 共用已授权 fd，并分发异步完成请求 |
| 接口申请 | Android claimInterface(..., true) | 先调用相同 API，旧系统失败时尝试 usbfs |
| 额外 T3 USB 驱动 | 在对比到的 USB 路径中没有发现 | 有通用 usbfs 兼容库，不是 T3 固件驱动 |
| AirPlay 监听 | 直接 bind 指定地址/端口，无动态端口占用恢复 | 保留显式端口与动态端口回退 |

不能通过简单照搬参考包保证修复。尤其不能照搬“配置失败仍继续”，也不因本次 USBMUX 申请失败就改动尚未进入的 NCM 双 fd 策略。

## 0.2.14 修改

1. Android 9 及更旧系统在新 USB 连接上始终执行系统配置选择，与参考包的调用顺序对齐。这一步只发生在申请任何接口之前。
2. 系统配置选择失败时，即使手机读回目标配置号，也必须进入 native 配置回退，不能直接报成功。
3. 保留原有严格失败处理、16 KB 兼容、共享 fd 与 NCM 配置复核，没有用伪造成功或忽略错误掩盖失败。
4. 将目标配置、接口描述摘要、选择前后读回值、系统调用结果、native errno、USBMUX 申请结果接入应用诊断日志。错误文本增加 targetConfig 和 activeConfig，不记录认证密钥或 USB 序列号。

Android 的配置选择和设备控制传输是不同的路径。AOSP 的 usb_device_set_configuration 使用 USBDEVFS_SETCONFIGURATION，而 control transfer 使用 USBDEVFS_CONTROL，参见 [AOSP libusbhost 实现](https://android.googlesource.com/platform/system/core/+/3597339226f5c0681631df9039eebf07485c04de/libusbhost/usbhost.c)。因此手机读回状态不能替代系统配置调用的成功结果。

## 回归证据与边界

- 先修改测试再运行旧逻辑：21 项配置测试中 9 项失败，覆盖 API 26/27/28 上的提前跳过、错误接受失败调用及漏掉 native 回退。
- 修改实现后：这 21 项配置测试全部通过。
- 新增 IphoneUsbBringupTest，通过公开打开入口验证调用顺序和资源回收；API 26/27 共 4 项通过。包括“手机返回配置 6，但模拟内核尚未配置”的场景。
- 这些测试模拟了状态不一致，不是从 T3 内核复现出的硬件故障。它们证明代码判断和调用顺序已修正，不证明照片中的所有 ENOENT 成因均已解决。
- 本轮没有依据新真机日志修改无线协议；保留 0.2.13 的无线修复，不能声称无线已恢复。

最终安装包校验和整体构建结果见项目根目录的 `DiPlay-v0.2.14-验证说明.md`。
