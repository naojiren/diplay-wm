# 威马 EX5 仪表投屏测试版

目标车辆：2019 款威马 EX5，车机 Android 6.0.1。

基于 DiPlay-Legacy-Android 的 `af40062ca44307b2f55b52ce7aca8d84744402a9`，本地分支 `wm-ex5-cluster`。安装名称为“DiPlay 威马测试版”，包名为 `com.shihab.diplay.wmex5`，可以与原包名的 DiPlay 同时安装。测试时请退出另一版本，避免抢占连接和仪表画面。

## 认证修复版 authfix2

2026-10-09 更新：版本名 `0.2.17-legacy.1-wm-ex5-authfix2`，版本号 38，包名和签名沿用首版，可覆盖安装并保留设置。

首版使用的 Legacy v0.2.17 发布资源含有 UTF-8 文本转换造成的二进制损坏，导致启动时报“无法加载 CarPlay 认证”，同时禁用连接按钮。修复版使用从 Legacy v0.2.21 发布包以二进制读取并验证的认证输入。打包前运行实际认证加载器，检查证书匹配、重复签名和篡改挑战拒绝；运行资源仍保存在 Git 忽略目录。

认证解析与签名使用随应用提供的加密实现，避免依赖旧系统中实现不同的默认提供程序。另移植上游的音频兼容修复：Android 6.0 使用旧版音频焦点接口，避免加载 Android 8.0 才有的 `AudioFocusRequest`；播放阶段不再调用 Android 7.0 才提供的欠载统计接口。音频焦点行为测试增加 API 23，并用 Android 6.0 模拟器测试实际 APK 的认证、PCM 播放和连接入口。

认证资料的公开修复说明见 [Legacy v0.2.18](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/releases/tag/v0.2.18)。本版仍基于此前威马分支，仅引入上述相关修复。

覆盖安装后先确认底部认证错误消失、“连接手机”可以点击、“通过 USB 连接”能进入连接界面，再继续下方仪表测试。手机实际连接和双屏地图仍需实车验证。

## 适配方法

提供明确开启的威马模式，从 Android 的 Presentation 副屏列表中选择仪表通道。默认自动选择第一个可用副屏，和用户提供的 `7.1.0.600067_chenmo` 高德修改版使用相同的列表入口；也可以手动选择副屏。Android 6.0 使用同样的 `TYPE_TOAST` 窗口类型。地图画面来自 DiPlay 已有的 CarPlay 独立仪表视频流，主屏继续显示普通 CarPlay 界面。

这次适配借用了修改版高德的副屏呈现方法。CarPlay 的独立地图流仍取决于 iPhone、iOS 和所用导航应用；仪表测试图正常出现，不能单独证明手机会输出仪表地图。

增加了四边留白、15 秒测试图和副屏诊断。设置变更在下一次 CarPlay 连接生效；测试图即时使用新设置。威马模式与原有 ADB 仪表模式互斥。

## 明天的测试顺序

1. 停车后安装 APK，打开“DiPlay 威马测试版”。退出原 DiPlay，以及正在占用仪表投屏的高德导航。
2. 打开“设置 → 高级 → 仪表盘上的CarPlay地图（实验性）”，开启“威马 EX5 仪表投屏（实验性）”。开启时会同时打开本卡片的仪表地图开关。
3. 不连接 iPhone，先选择“仪表副屏 → 自动”，点击“显示仪表测试画面 · 15 秒”。应该在仪表地图区域看到红、绿、蓝、黄四角和十字线，15 秒后消失。
4. 如果画面到了其他副屏，手动更换“仪表副屏”。如果区域需要缩小，调整左、上、右、下留白，再点击测试图。每侧支持 0%～40%。确认退出设置或等待 15 秒后画面能够恢复。
5. 按 DiPlay 原有连接向导连接 iPhone。首次建议用苹果地图启动一条导航路线，分别观察中控主屏和仪表地图；之后再测试 iPhone 高德等导航应用。
6. 更改副屏或留白后，手动断开并重新连接 CarPlay。再检查画面位置、转弯提示、结束导航、断开连接和退出应用后的仪表恢复。

如果没有发现副屏，可以先用已正常工作的高德开启一次仪表地图，然后退出高德、重新进入 DiPlay 设置查看副屏列表。若仍没有副屏，保存诊断报告。

## 出现问题时保留的信息

打开“设置 → 诊断 → 保存诊断报告”。报告包含所有显示屏、Presentation 副屏、威马模式开关、选择的副屏、尺寸、窗口类型、留白和流状态。

反馈时记录：测试图是否出现、显示在哪个区域、CarPlay 主屏是否正常、仪表是等待/黑屏/有地图、手机型号、iOS 版本、导航应用，以及停止投屏后是否恢复。报告中以 `wmEx5` 开头的字段用于核对威马适配。

此版本需由实车验证显示通道、窗口层级和 CarPlay 地图输出；电脑上的构建与自动测试不能代替这些测试。

## 构建

Android 最低 API 19；项目当前采用 SDK 37、NDK 25.2.9519653、Gradle 9.5.0 和完整 JDK 25。独立车机包需要在 `.private/runtime-assets/offline-mfi` 放入显式提供的运行认证资源，该目录不纳入 Git。

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :mobile:assembleStandaloneDebug :home:assembleDebug :maphost:assembleDebug \
  --no-daemon --max-workers=2 --console=plain
```
