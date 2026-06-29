# 豆包 Newline 助手

Android 原生应用，用于监听 Newline NP-S2201 的 USB Type-C 连接，在 USB 输入、输出音频均就绪后自动启动豆包。

## 当前功能

- Android 前台服务常驻监听
- USB 插入后自动启动应用和服务
- 枚举 USB 设备的 VID、PID 和设备类别
- 检测 USB 麦克风与扬声器
- 手动选择已连接的双向蓝牙通话设备
- Type-C 与蓝牙同时可用时优先使用 Type-C
- Type-C 不可用时回退到用户选择的蓝牙设备
- 音频就绪后启动豆包（包名 `com.larus.nova`）
- USB 断开状态恢复
- 开机后恢复已启用的服务
- 大字号状态页面和常驻通知
- 将豆包连续对话入口集中封装在 `DoubaoLauncher`

## 构建

```shell
./gradlew assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 真机测试顺序

1. 在 Android 16 手机上安装豆包并完成登录。
2. 安装本应用，首次打开并允许通知。
3. 点击“测试启动豆包”，确认豆包包名与启动行为。
4. 返回本应用，连接 Newline NP-S2201。
5. 记录页面显示的 VID、PID、USB 音频输入和输出。
6. 确认豆包是否自动启动，以及 Newline 能否拾音和播放。
7. 确认豆包连续对话的实际入口参数后，配置或固化到 `DoubaoLauncher`。

## 蓝牙模式

1. 先在系统蓝牙设置中配对并连接设备。
2. 打开本应用，允许“附近设备”权限。
3. 勾选“启用蓝牙通话设备”。
4. 点击“选择蓝牙设备”，选择目标设备。

列表取自手机系统蓝牙中所有已配对的外部设备，并在名称后标注“已连接”或
“已配对”。当前能被 Android 识别为通话音频端点的设备会优先用于路由；未
连接的设备会保留为可选项，等它重新连接后再接管音频。不会把本手机的听筒
或扬声器列为候选项。Type-C 和蓝牙同时启用时，Type-C 双向音频优先。

## 连续对话入口

当前无入口 URI 时使用豆包标准启动 Intent。代码已支持通过 SharedPreferences 键
`doubao_conversation_uri` 设置经过真机验证的 URI，并会限定由豆包包处理：

```shell
adb shell am start \
  -n com.fwp.doubaonewline/.MainActivity
```

最终版本应将真机验证得到的 Activity、Action、URI 和 Extras 固化为版本化适配规则。

## 已知事项

- 应在真机日志中记录 NP-S2201 的 VID/PID，后续用于设备身份校验。
- Android 会管理标准 USB Audio Class 路由；本应用负责检测就绪状态，不与豆包争用原始 USB 端点。
- 静态 Lint 在当前机器上执行时间异常，已终止；Debug APK 已通过完整编译。
