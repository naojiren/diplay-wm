# WePlay 威马 EX5 CarPlay · diplay-wm

[简体中文](README.md) · [English](README.en.md)

为 **2019 款威马 EX5、Android 6.0.1 车机**适配的 CarPlay 接收端，支持中控画面和仪表地图。基于 [DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android)，保留旧 Android 兼容与威马双屏、音频、USB、无线和低延迟优化。

**下载安装到车机，不是 iPhone。** [下载最新安装包](https://github.com/naojiren/diplay-wm/releases/latest) · [全部版本](https://github.com/naojiren/diplay-wm/releases) · [问题反馈](https://github.com/naojiren/diplay-wm/issues)

## 当前版本

`perf24` · 版本号 `68` · 应用名 **WePlay** · 包名 `com.shihab.diplay.wmex5`。

最新包为 **WePlay perf24 ARMv7 控制队列断线修复版**，采用车主选定的图标 5。针对导航、听歌期间的 iAP2 控制队列溢出，增加上下游有界等待与增量消息解析，并减少逐帧日志开销。沿用声音正常版本的音频、麦克风、双屏适配和 300ms 默认音频缓冲。

[下载 perf24 APK](https://github.com/naojiren/diplay-wm/releases/tag/v0.2.17-wm-perf24-control) · [修复和验证范围](docs/WM_EX5_PERF24.md)。已安装到实车；2162 项测试通过、静态检查零错误，长时实车稳定性仍待验证。

| 项目 | 默认设置 / 支持情况 |
|---|---|
| 中控 | 50% 分辨率，18 帧/秒 |
| 仪表地图 | 默认开启，100% 分辨率，18 帧/秒；在留白后的区域等比例居中 |
| 仪表留白 | 左侧 24%，底部 5% |
| 帧率 | 两屏提供 12、18、24、30；主屏还保留更高档位，威马菜单不提供 4/8 帧 |
| 无线 | 默认 Wi-Fi Direct，固定 5GHz 44 信道；可选 36、157、2.4GHz 1/6 |
| 同一局域网 | 保留该模式，自动读取车机当前 Wi-Fi，不要求填写密码 |
| 网络通道 | 威马无线连接使用 IPv4 |
| 音频 | 沿用语音输入/输出切换修复；默认音乐缓冲 300ms；兼容 Android 6 Siri 输出 |
| 启动 | 打开应用自动开启蓝牙；打开时连接默认关闭，可自行设置开机启动与连接 |

覆盖升级保留已有配对、布局、帧率、无线模式与信道设置。仪表“车辆标记 · 垂直 → 下 10%”是可选用户设置（当前高度下约 46 像素），重新连接后生效；它没有固化为 perf24 默认值。

## 安装和连接

1. 从最新 Release 下载 `WePlay-WM-EX5-perf24-armv7.apk`，安装到车机。
2. 从本项目此前威马包升级且签名相同时，可直接覆盖安装并保留设置。自行编译的签名可能不同；遇到签名冲突时先核对来源，卸载会清除设置。
3. 保持 iPhone 的蓝牙和 Wi-Fi 开启，完成车机蓝牙配对，在 DiPlay 中选择手机并允许 CarPlay。
4. Wi-Fi Direct 会按兼容处理断开车机已有的普通 Wi-Fi。使用同局域网时，让手机和车机连接同一路由器；若长时间没有画面，尝试让手机关闭再开启 Wi-Fi 并重新连接。
5. USB 使用支持数据传输的接口与线材。旧车机的 USB 驱动和 Android VPN 授权组件兼容性会影响是否能连接。

### 开机自动连接

在 **设置 → 连接 → 自动连接**中开启“车机启动后打开”和“打开 DiPlay 时连接”，把“默认连接模式”选为“无线”；然后在连接设置中选择 Wi-Fi Direct。

这些是用户设置，不是新安装默认强制开启。开机自启依赖车机发出 Android 开机完成广播；仅休眠后唤醒的行为需要另行验证。

### 麦克风设置

**高级 → 停用车机麦克风（实验性）**默认关闭。保持关闭可使用本版已恢复的车机采音。开启只会停止车机录音，不能保证 iPhone 改用自身麦克风，可能导致 Siri 或 ChatGPT 无法收音；更改后在下一次连接生效。

## 兼容和验证

- 源码最低 Android 4.4 / API 19；主要实车验证目标为威马 EX5 2019、Android 6.0.1 / API 23、TI DRA74x/Jacinto6。其他车型、固件和 Android 版本仍需自行验证。
- perf24 APK 仅包含 ARMv7，约 12.83 MB；发布文件与实车安装文件一致。声音处理沿用此前已确认可用的旧版分支。
- perf24 完整 CI 命令通过，2162 项单元测试通过，静态检查零错误；包括控制队列压力恢复与真实加密隧道大包验证。实车长时间断线复测结果尚未确认。详见 [本版记录](docs/WM_EX5_PERF24.md) 与 [历史语音修复记录](docs/WM_EX5_PERF19.md)。
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
- 初始公开源码快照对应本地提交 `55e651c1bf987acde4c5fa6a8034146830689156`；后续版本增加语音与控制队列修复，并保留威马仪表和低延迟适配。新增软件 Opus 编码来源见 [来源记录](docs/WM_EX5_OPUS_SOURCE.json)。
