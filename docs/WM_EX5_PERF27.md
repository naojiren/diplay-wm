# WePlay perf27：BCM4339 无线接收保护

基于 perf26，版本 71 / `0.2.17-legacy.1-wm-ex5-perf27-network-guard`。

## 依据

同局域网和 Wi-Fi Direct 均有音频卡顿。2026-10-15 的 IPv4 被动抓包显示，媒体包在应用解码之前已有 0.6–0.8 秒间隔及序号缺口；应用解码丢帧、队列溢出和写入错误均为 0。单纯增加固定音频缓冲不能修复传输接收链路。

实车无线驱动信息：BCM4339 Rev 1、驱动 1.141.97、固件 6.37.39.93。实际 driver ioctl 的 magic、芯片号、电源模式、扫描抑制状态均能读取。原有 `WifiScanPause` 只在 Android 10 生效，Android 6 没有运行这项扫描保护。脱离 CarPlay 时读取到 PM_FAST=2、scan_suppress=0；该读数不能证明 CarPlay 运行期间仍为省电模式。

## 修改

在本车的同局域网和 Wi-Fi Direct 连接建立后，取得高性能 Wi-Fi 锁和有超时的 CPU 唤醒锁。通过已有 su 的标准输入命令模式启动一个小型 ARMv7 辅助程序，临时抑制 BCM4339 后台扫描，并将快速省电返回时间从实测的 200ms 提高至 2000ms，延长无线活跃窗口。每两秒校验状态。

实车发现 PM_OFF 命令虽然返回成功，回读仍为 PM_FAST=2，因此没有把它当作成功关闭省电；新方案使用已经在车机回读验证的 `pm2_sleep_ret=2000` 兼容处理。原值已超过 2000ms 时不调低。实车短时验证中，scan_suppress 能从 0 变成 1、sleep_return 能从 200 变成 2000；输入管道关闭后两项均恢复原值。

这项处理只允许包名 `com.shihab.diplay.wmex5`、Android 6 API 23、CQ1301 / j6headunit 设备；辅助程序另检查 Broadcom magic 和 BCM4339 芯片号。驱动或 root 不可用时保留普通系统锁，不将保护失败变成 CarPlay 连接失败。

退出、切换连接、控制器重建时，串行关闭旧保护，再启动新保护。辅助程序遇到输入管道关闭、应用进程消失、退出信号或 20 秒没有心跳时，恢复进入前的状态。恢复只撤销仍等于本保护目标值的项；保存了带启动标识的恢复记录，应对异常结束，下一次取得保护前先恢复遗留记录。辅助程序之间使用独占锁，避免互相覆盖恢复快照。

原有音频缓冲默认 300ms、音频输出、麦克风、连接控制通道、仪表投屏和实体按键处理保持 perf26。没有改无线信道或向手机声明新的音频协议。

## 构建与验证

辅助程序由 Gradle 使用 NDK r25c / Android 23 工具链从源码生成，作为一个 ARMv7 资源随包分发，保持单架构安装包。只处理扫描、电源模式和快速省电返回时间这三项运行时状态，不刷写固件。

交付前运行 AGENTS.md 完整检查、native 策略测试，以及版本、签名、认证资源和源码一致性检查。native 策略的 11 个场景覆盖开始/恢复、原状态已符合目标、读失败、部分设置失败后的恢复、保留外部修改、恢复失败、固件重写后的重新校验、非法快照、PM_OFF 被忽略/拒绝时的回退及保留更长的既有返回时间。

实车验证需确认日志 `WM radio guard active=1`、退出后的 `restored=true`，再对比连续音乐和导航的到包间隔、序号缺口和补缓冲次数。代码检查不能代替用户对声音连续性的确认。

## 接口来源

Broadcom 的公开 [wlioctl 头文件](https://android.googlesource.com/platform/hardware/broadcom/wlan/+/froyo/bcm4329/src/include/wlioctl.h) 定义电源与扫描 ioctl 编号；[驱动电源实现](https://android.googlesource.com/kernel/common.git/+/bcmdhd-3.10/drivers/net/wireless/bcmdhd/wl_cfg80211.c) 使用 PM_OFF 关闭省电；[驱动 ioctl 入口](https://android.googlesource.com/kernel/common/+/6444e59eb90ee05bdc0d51e0dabd82515688cf58/drivers/net/wireless/bcmdhd/dhd_linux.c) 描述用户态数据布局。此次在本车读取验证了该 ABI。

公开 [Broadcom 驱动电源超时实现](https://android.googlesource.com/kernel/common/+/3f20ba7d5d1dbcd63d31e1e539a83bd3baace048%5E1..3f20ba7d5d1dbcd63d31e1e539a83bd3baace048/) 使用 `pm2_sleep_ret` 设置毫秒超时，上限 2000ms；本车实际写入与回读验证了该参数可用。
