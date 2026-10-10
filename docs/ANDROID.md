# Android 构建与发行

手机端最低 Android 8.0（API 26），compile / target SDK 为 37，构建工具 37.0.0。Wi-Fi Aware 需要支持该功能的硬件；不支持时继续使用蓝牙或在线连接。

0.1.1 的聊天布局、字体、颜色、底部抽屉和频道行复用上游组件。频道页只列出已加入的命名频道；创建和加入使用弹窗。地图、地理频道、附近笔记，以及针对旧 Nostr 地理聊天的 Tor / PoW 设置不再出现在手机用户流程中。

0.1.2 补齐通用中文、简体中文和繁体中文的字符串资源，并让权限说明、频道错误、命令菜单、私聊及文件反馈跟随应用语言。设置中选择“应用语言”，或使用系统默认语言。应用名称、协议命令、用户昵称和频道名称保留原值。

0.1.3 继续补齐调试面板、传输统计和安装包信息的中文文案，并修复密码确认、连接超时和退出后的异步状态。界面布局继续沿用上游组件。

Android 12 及以上的 BLE 使用 `neverForLocation`，只申请附近设备权限；定位权限的声明限定到 API 30。Android 8–11 的 BLE 扫描仍受系统定位权限和位置开关限制，可以选择“仅使用在线频道”或拒绝权限。不会申请后台定位；旧系统的后台 BLE 发现因此可能受限。在线频道的启动和使用不依赖定位权限或位置开关。Wi-Fi Aware 属于可选本地传输，其旧系统限制仍由系统决定。

Wi-Fi Aware 在 Android 13 及以上使用附近 Wi-Fi 设备权限，不强制开启系统定位。Android 12 / 12L 的 Aware API 仍要求定位权限；本分支在这两个版本回退到蓝牙和在线频道，保持不申请定位。参见 [Android 蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)与 [Wi-Fi 权限](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)。热点 APK 分享在 Android 12 / 12L 同样受旧 Wi-Fi 权限要求限制，可改用普通 APK 文件分享。

## 调试构建

安装 JDK 21 与上述 Android SDK。推荐 Android Studio，或在 Android 目录运行：

```sh
./gradlew :app:assembleDebug :wear:assembleDebug
./gradlew testDebugUnitTest lintDebug
./gradlew clientRewriteContractTest
```

手机通用调试包：`android/app/build/outputs/apk/debug/app-universal-debug.apk`。这是开发测试包，不是正式发行包。Windows 使用 `gradlew.bat`；建议将仓库与 Gradle 缓存放在较短的 ASCII 路径，避免 Java 参数文件与原生测试库的路径问题。

GitHub CI 会运行 Linux 构建与测试，默认不发布构建产物。调试签名不保证跨构建机器一致，正式使用前应安装自己的正式签名版本。

## 自己的正式签名

本分支使用独立安装标识；不能作为原版 bitchat 的更新覆盖安装。

1. 在 Android Studio 用 **Generate Signed APK** 创建并安全备份自己的 keystore。
2. 将 keystore 保存在仓库外；密码、私钥和 keystore 不进入 GitHub。
3. 为所有正式发行使用同一签名或受支持的签名轮换，保持 versionCode 递增。
4. 将该证书的 SHA-256 公共指纹作为构建变量 `BITCHAT_GITHUB_RELEASE_CERT_SHA256` 设置。它不是私钥。默认不信任原版官方发行证书。
5. 构建并签名 release APK，使用资产名称 **bitchat-android-universal.apk** 发布到本仓库的 GitHub Releases，供保留的更新和 APK 分享流程使用。
6. 确认 APK 的包名、版本、签名和来源。下载的 APK 必须匹配本机安装签名或显式固定的发行证书；不能仅因“存在签名”就信任。

上游可重现发行流程参见 `android/docs/reproducible-builds.md`。只有按其精确工具链双重构建并比对成功才能宣称可重现；普通调试构建不构成这项证明。

## 必须完成的真机验收

至少使用两台 Android 手机，覆盖一个普通国内厂商设备和不同系统版本：

- 同名频道的公开 / 密码文字、错误密码、重启后解锁、密码变更和退出。
- Noise 私聊、验证、收藏、拉黑、回执、断线重试和后台通知。
- 图片、文件、语音留言、取消发送、超限提示，以及频道与私聊 PTT。
- 蓝牙离线 mesh、支持设备间的 Wi-Fi Aware；关网后保持本地功能。
- Wi-Fi / 蜂窝切换、长时间断网、锁屏、后台、权限撤回、前台服务重启。
- panic 清除后旧连接关闭、历史与密钥被清理，不能重新注入清除前的数据。
- 与原版应用并存安装，以及本分支自身的签名更新和 APK 分享。

上游 Mesh Lab：`android/tools/release_gate/mesh_lab.py`。使用本分支实际包名配置相关测试工具；不要将设备序列号、日志、位置或真实聊天内容上传至公开仓库。

此开发环境没有执行真机验收，因此当前不作生产可靠性或兼容所有厂商后台策略的承诺。
