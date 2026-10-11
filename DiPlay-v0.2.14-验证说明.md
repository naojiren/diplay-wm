# DiPlay 0.2.14 验证说明

构建日期：2026-10-04。针对用户在 0.2.13 上反馈的 USBMUX interface 1 / usbfs errno 2，按要求对比桌面的 Android 8 移植 APK 后修正。

## 实际安装包

- 路径：`D:/codex/DiPlay-Legacy-Android/DiPlay-v0.2.14-Android8-release.apk`
- 版本：0.2.14，versionCode 33，包名 `com.shihab.diplay`
- 大小：7,546,457 字节，约 7.20 MiB
- Release 构建，未启用 debuggable；最低 API 19，包含 Android 8.0 / 8.1。
- ABI：armeabi-v7a、arm64-v8a、x86、x86_64。
- SHA-256：`c1c4db2c8f9f062d6c4d2dea252d1a16b2e5009a29cdbf767cadd870a94c246d`

已验证文件实际位于项目根目录，复制后的哈希与构建输出一致。
沿用之前交付包的签名，支持覆盖同签名的 0.2.13 并保留配置。证书名称仍为 Android Debug，没有换用新的正式发行证书。
本地认证资源来自项目明确指定的构建输入，没有从参考 APK 复制认证材料，不需要另配认证服务器。

## 修正内容

- 旧系统新建 USB 连接后始终执行系统配置选择，参考移植包的实际调用顺序，不仅凭手机返回的配置号就跳过。
- 系统配置选择失败时，不再因为手机读回目标配置号就报告成功，而是进入已有的 usbfs 回退；真正失败时停止，避免申请不存在的接口。
- 底层配置和 USBMUX 申请结果接入可导出的应用日志，错误显示目标配置和当前配置，便于区分失败原因。
- 保留先前 USB 16 KB 适配、共享 fd、NCM 配置检查及无线端口恢复；本轮未声称新的无线真机验证结果。

参考包对比及证据见 `docs/ANDROID8-REFERENCE-COMPARISON.md`。参考包没有提供可直接照搬的 T3 专用 USB 驱动，文件名也不能证明已在用户设备成功投屏。

## 本轮验证

- 修改前运行新增配置测试：9 项失败，明确复现了错误跳过或错误接受配置选择的代码分支。
- 修改后 `:shared:testDebugUnitTest :common:testDebugUnitTest`：383 项通过，0 失败、0 错误、0 跳过。
- 包括 21 项 API 26/27/28 配置测试及 4 项 API 26/27 USB 打开流程测试，检查调用顺序、失败关闭和诊断信息。
- `:mobile:lintRelease`：0 错误，5 条现有警告。
- `:mobile:assembleStandaloneRelease`：构建成功。
- APK v1/v2 签名和 zipalign 校验通过。
- APK 两份本地认证资源与显式构建输入哈希一致，未包含签名密钥库。
- 独立 Android 8.1 / API 27 模拟器：从 0.2.13 覆盖安装成功，0.2.14 启动成功，检查期间崩溃日志为空。
- 最终 APK 的网络类在 API 27 上完成 10 轮 TCP/UDP 创建、收发、关闭测试；USB JNI 库加载和无效 fd 错误返回检查通过。

## 未验证边界

没有连接用户的 T3 车机或真实 iPhone。配置测试使用模拟的设备及内核状态，不等同于在 T3 上复现或解决所有 ENOENT 原因。
本包不能宣称三个模式真机投屏、声音、触控、Siri 和长时间稳定性均已通过。
若仍发生同类错误，请从 DiPlay 主界面的“诊断”区域保存诊断报告；Android 8 会通过系统文件选择器选择保存位置。完整报告比屏幕最后一行错误更能确定配置与接口的真实变化。
