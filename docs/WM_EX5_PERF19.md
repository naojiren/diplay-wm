# perf19：解决语音输入和输出切换问题

此前进入 CarPlay 语音会话后，ChatGPT 无法收音或无法听到回复，Siri 地图导航也可能无法识别。本版恢复车机麦克风采集，补充旧系统缺少的 Opus 编码能力，并修复 Android 6 的 Siri 音频输出兼容性。车主于 2026-10-12 在 2019 款威马 EX5 / Android 6.0.1 实车确认：ChatGPT 可以语音聊天，Siri 地图导航可以正常识别。

## 修改

- 从 perf18 升级时，一次性关闭停用车机麦克风的实验开关，恢复车机录音。不能通过停止车机录音强制 iPhone 使用手机麦克风。
- 系统 Opus 编码器不可用时，使用随包提供的 Concentus 软件编码，支持 CarPlay 麦克风上行。
- Android 8 以下的 Siri 播放使用兼容的 `USAGE_MEDIA`，保留现有音乐、导航和通话输出处理。
- 增加麦克风零帧/非零帧计数和编码器诊断，不保存录音内容。
- 将原“仅使用手机麦克风”实验选项更名为“停用车机麦克风”，默认关闭，避免误解其实际作用。

保留威马双屏适配、仪表居中与留白、已有低延迟优化、无线/USB、IPv4 和各连接模式。升级不会重置已有帧率、分辨率、网络或配对设置。

## 安装包

`DiPlay-WM-EX5-perf19-audio-armv7.apk`，约 12.75 MB，仅 ARMv7；版本名 `0.2.17-legacy.1-wm-ex5-perf19-audio`，versionCode `63`，包名 `com.shihab.diplay.wmex5`。采用此前构建环境的签名，可覆盖安装保留数据。更新后重新连接 CarPlay。

## 验证范围

安装包编译完成并通过 ADB 覆盖安装。车主确认 ChatGPT 语音对话和 Siri 地图导航正常；这是用户实车反馈，不是自动化语音测试。

按车主要求跳过剩余自动检查，本版不宣称完整 CI 或 Android 6 自动运行检查全部通过。此前 perf18 的测试记录不等同于本版验证。其他车型、系统及所有音频场景仍未逐项确认。

本版含基础零帧统计，尚未合入 Legacy 最新的首音延迟统计和全程静音后自动更换录音源逻辑。

结构化版本记录见 [perf19-verification.json](releases/perf19-verification.json)；软件 Opus 编码来源和许可证见 [来源记录](WM_EX5_OPUS_SOURCE.json) 与 [第三方声明](THIRD_PARTY_NOTICES.md)。
