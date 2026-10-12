# DiPlay 威马 EX5 perf21 稳定音频版

基于 perf20 撤回音频短缓冲实验，音频播放、缓冲容量和接收队列恢复至实车语音正常的 perf19 实现。

- 音乐缓冲默认为 **300 毫秒**，仅保留 300、500、1000 毫秒档。
- 移除 100／150 毫秒档、缩小 AudioTrack 容量及淘汰旧音频包的实验。
- “打开 DiPlay 时连接”默认关闭，覆盖升级也会关闭一次。开机启动选项保持用户原值。
- 型号默认 **EX5**，OEM 标签默认 **Weltmeister**，车辆制造商保留 **威马**。
- AirPlay／CarPlay 返回车辆按钮改为银色五竖条威马图标，设置页显示相同图标。
- APP 桌面图标以银色五竖条为主体，青蓝色 C／播放弧线环绕并叠在下方，深蓝底色，提供五档 Android 图标尺寸。
- 保留双屏投屏、帧率和布局、无线模式与信道、音频输出路由、perf19 麦克风及软件 Opus 兼容处理。

首次打开升级版时，仅重设本次请求的音乐缓冲、打开时连接、型号、OEM 标签及返回车辆图标。
以后手动修改这些选项仍会保存，不会每次启动重设。Wi-Fi 凭据、iPhone 配对、双屏布局和其它音频选项不会清除。

## 图标参考

用户指定的是 2019 EX5 使用的银色五竖条，不是官网当前的红色文字标识，也不是桌面软件图标。
五条圆头竖条的错位形状与本车原厂 VehicleCenter／OTA 图标核对。`docs/assets/wm-airplay-logo.svg`
为本包的矢量绘制源，`common/src/main/res/raw/wm_car_home.png` 为同一图形的 256×256 PNG，使用银色渐变和深色底。
`docs/assets/wm-app-logo-master.png` 为桌面图标的设计原图；根据用户允许自由设计的要求，
将 C 曲线和播放三角融合为青蓝色前景弧线。`docs/assets/wm-app-logo.png` 为 256×256 预览，
设计工具和提示词记录在 `docs/assets/wm-app-logo-design.md`。

官网检索参考：https://www.wm-motor.com/?lang=cn ，经典标识外观参考：https://www.carlogos.org/car-brands/weltmeister-logo.html 。
此图标仅用于车主辨识自己的车辆，标志权利归其权利人。

## 安装和测试

APK 为 ARMv7 单架构，包名保持 `com.shihab.diplay.wmex5`，版本代码 65，版本名
`0.2.17-legacy.1-wm-ex5-perf21-stable`。使用与 perf19／perf20 相同的签名，可覆盖安装。

安装后打开 DiPlay，点击连接手机并重新连接 CarPlay。确认音乐缓冲为 300 毫秒，再测试音乐、导航、Siri 和 ChatGPT 语音。
此版恢复音频稳定性；不声称消除现有端到端延迟。实车音频连续性仍需车主确认。
