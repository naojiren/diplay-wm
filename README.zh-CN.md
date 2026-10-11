# DiPlay 威马优化版 CarPlay · diplay-wm

[简体中文](README.md) · [English](README.en.md)

为 **2019 款威马 EX5、Android 6.0.1 车机**适配的 CarPlay 接收端，支持中控画面和仪表地图。基于 [DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android)，保留旧 Android 兼容与威马双屏、音频、USB、无线和低延迟优化。

**下载安装到车机，不是 iPhone。** [下载最新安装包](https://github.com/naojiren/diplay-wm/releases/latest) · [全部版本](https://github.com/naojiren/diplay-wm/releases) · [问题反馈](https://github.com/naojiren/diplay-wm/issues)

## 当前版本

`perf18` · 版本号 `62` · 应用名 **DiPlay 威马优化版** · 包名 `com.shihab.diplay.wmex5`。

最新包为 **perf18 ARMv7 精简版**，只保留本车使用的 `armeabi-v7a` 底层库，功能和设置沿用 perf18。原始四架构 perf18 包仍可从历史 Release 下载；用户已反馈原版测试成功，麦克风响应比此前灵敏。

体积和一致性检查见 [ARMv7 精简版说明](docs/WM_EX5_ARMV7.md)。

| 项目 | 默认设置 / 支持情况 |
|---|---|
| 中控 | 50% 分辨率，18 帧/秒 |
| 仪表地图 | 默认开启，100% 分辨率，18 帧/秒；在留白后的区域等比例居中 |
| 仪表留白 | 左侧 24%，底部 5% |
| 帧率 | 两屏提供 12、18、24、30；主屏还保留更高档位，威马菜单不提供 4/8 帧 |
| 无线 | 默认 Wi-Fi Direct，固定 5GHz 44 信道；可选 36、157、2.4GHz 1/6 |
| 同一局域网 | 保留该模式，自动读取车机当前 Wi-Fi，不要求填写密码 |
| 网络通道 | 威马无线连接使用 IPv4 |
| 音频 | 保留此前正常的输出处理；提供手机麦克风实验选项 |
| 启动 | 打开应用自动开启蓝牙；可设置开机启动和打开后自动连接 |

已有安装会保留配对、布局和其他设置；perf18 升级首次启动一次性应用两屏 18 帧、Wi-Fi Direct、44 信道及麦克风实验默认值，此后手动选择会保存。

## 安装和连接

1. 从最新 Release 下载 `DiPlay-WM-EX5-perf18-armv7.apk`，安装到车机。
2. 从本项目此前威马包升级且签名相同时，可直接覆盖安装并保留设置。自行编译的签名可能不同；遇到签名冲突时先核对来源，卸载会清除设置。
3. 保持 iPhone 的蓝牙和 Wi-Fi 开启，完成车机蓝牙配对，在 DiPlay 中选择手机并允许 CarPlay。
4. Wi-Fi Direct 会按兼容处理断开车机已有的普通 Wi-Fi。使用同局域网时，让手机和车机连接同一路由器；若长时间没有画面，尝试让手机关闭再开启 Wi-Fi 并重新连接。
5. USB 使用支持数据传输的接口与线材。旧车机的 USB 驱动和 Android VPN 授权组件兼容性会影响是否能连接。

### 开机自动连接

在 **设置 → 连接 → 自动连接**中开启“车机启动后打开”和“打开 DiPlay 时连接”，把“默认连接模式”选为“无线”；然后在连接设置中选择 Wi-Fi Direct。

这些是用户设置，不是新安装默认强制开启。开机自启依赖车机发出 Android 开机完成广播；仅休眠后唤醒的行为需要另行验证。

### 手机麦克风实验

**高级 → 仅使用手机麦克风（实验性）**默认开启。此功能停止车机采音，保留 CarPlay 麦克风能力协商和原有声音输出；不能保证 iPhone 始终选择自身录音设备。如果 Siri 听不到声音或进入蓝牙电话界面，可关闭该选项并重连，恢复此前的车机采音。

## 兼容和验证

- 源码最低 Android 4.4 / API 19；主要实车验证目标为威马 EX5 2019、Android 6.0.1 / API 23、TI DRA74x/Jacinto6。其他车型、固件和 Android 版本仍需自行验证。
- 最新精简 APK 仅包含 ARMv7，适用于本车的 TI DRA74x/Jacinto6 32 位系统。移除其他架构主要减少下载和存储占用，不会自动降低视频延迟。精简包未进行新的实车测试。
- perf18 发布前通过 2,115 项单元测试、三个应用 lint 检查及 Android 6 双屏/音频运行检查。详情见 [版本和验证记录](docs/WM_EX5_PERF18.md)。
- 视频使用 Android MediaCodec；实车此前已确认 TI H.264 硬件解码。低帧率不一定更低延迟：旧硬解的持帧等待可能在低帧率下更加明显。

## 源码构建

参见 [构建说明](docs/BUILD.md)。公开源码不包含运行认证输入、APK 签名密钥或用户配对信息。普通源码构建用于编译和检查；可独立连接的构建需要显式提供认证输入。

```sh
./gradlew :mobile:assembleDebug
```

默认只编译和打包 ARMv7；需要 x86 模拟器或四架构开发包时，增加 `-PwmUniversalAbis=true`，详见构建说明。

主要模块：`:mobile` 为威马应用，`:common` 为界面与车机集成，`:shared` 为协议及媒体处理，`:mdnsCompat` 为 Android 6 发现端口兼容；`home` / `maphost` 为样例应用。

## 来源、许可证和认证说明

- 上游：[programmerguohuajing/DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android)、[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)、[shilapi/xcertplay](https://github.com/shilapi/xcertplay)。
- 接收端沿用 GPL-3.0；部分 DiAuto 衍生界面沿用 AGPL-3.0。第三方库及素材保留各自条款，详见 [LICENSE](LICENSE) 与 [第三方声明](docs/THIRD_PARTY_NOTICES.md)。
- 不是 Apple 官方认证产品。发布 APK 带有既有离线实验所用的附件认证材料，可从 APK 中提取；其来源和分发说明见 [SECURITY.md](SECURITY.md)。源码和发布附件不包含 Android APK 签名私钥。未来 iOS 是否继续接受该实验身份不能保证。
- 初始公开源码快照对应已测试的本地提交 `55e651c1bf987acde4c5fa6a8034146830689156`；后续补充发布文档、CI 和 ARMv7 构建筛选，运行代码未改变。
