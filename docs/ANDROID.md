# Android 构建与发行

0.1.6 起手机端最低 Android 14（API 34），compile / target SDK 为 37，构建工具 37.0.0。支持 Android 14、15、16、17 对应的平台 API 范围；版本门槛不代表已在全部系统和厂商设备上验收。Wear 独立保持最低 API 33。Wi-Fi Aware 需要支持该功能的硬件；不支持时继续使用蓝牙或在线连接。

0.1.1 的聊天布局、字体、颜色、底部抽屉和频道行复用上游组件。频道页只列出已加入的命名频道；创建和加入使用弹窗。地图、地理频道、附近笔记，以及针对旧 Nostr 地理聊天的 Tor / PoW 设置不再出现在手机用户流程中。

0.1.2 补齐通用中文、简体中文和繁体中文的字符串资源，并让权限说明、频道错误、命令菜单、私聊及文件反馈跟随应用语言。设置中选择“应用语言”，或使用系统默认语言。应用名称、协议命令、用户昵称和频道名称保留原值。

0.1.3 继续补齐调试面板、传输统计和安装包信息的中文文案，并修复密码确认、连接超时和退出后的异步状态。界面布局继续沿用上游组件。

0.1.4 修复昵称变化后的消息分组：新消息显示新昵称，旧消息保留原昵称。私聊回执和文件取消按身份判断归属，避免改名后丢失操作或同名用户被误认作自己。

0.1.5 将身份判断统一到消息模型、私聊未读、持久化和会话名称；失败连接的旧回调在退避期间也会失效。没有共用就绪频道的已知私聊对端可通过邮箱接收签名昵称公告。改动没有重新设计界面，也没有更换 Noise 或频道加密格式。

支持的手机系统上，BLE 使用 `neverForLocation`，申请附近设备权限。本分支移除精确、粗略及后台定位权限，并阻止依赖库把它们加入合并清单。在线频道的启动和使用不依赖定位权限或位置开关，可以选择“仅使用在线频道”。0.1.5 及更早版本支持 Android 8–11，其 BLE 扫描受系统前台定位要求限制；新版不再支持这些系统。

Wi-Fi Aware 和热点 APK 分享使用附近 Wi-Fi 设备权限。Android 17 的本地网络权限仍按系统版本申请。参见 [Android 蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)与 [Wi-Fi 权限](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)。

## 调试构建

安装 JDK 21 与上述 Android SDK。推荐 Android Studio，或在 Android 目录运行：

```sh
./gradlew :app:assembleDebug :wear:assembleDebug
./gradlew testDebugUnitTest lintDebug
./gradlew clientRewriteContractTest
```

手机通用调试包：`android/app/build/outputs/apk/debug/app-universal-debug.apk`。这是开发测试包，不是正式发行包。Windows 使用 `gradlew.bat`；建议将仓库与 Gradle 缓存放在较短的 ASCII 路径，避免 Java 参数文件与原生测试库的路径问题。

GitHub CI 会运行 Linux 构建与测试，默认不发布构建产物。调试签名不保证跨构建机器一致，正式使用前应安装自己的正式签名版本。

## 精简安装包

开发调试包包含未精简的代码、Compose 调试工具和 ADB 测试入口，通用包还包含四种处理器的库。日常安装使用经过 R8 代码和资源精简的 release 构建；原版界面、语言、加密、媒体、离线扫码、BLE 和 Wi-Fi Aware 保留。

本地开发预览使用以下命令，以同一台构建机器的开发证书签名：

```sh
./gradlew :app:assembleRelease '-Pbitchat.previewSigning=true' --no-daemon
python3 ../tools/verify-apk.py --apk-dir app/build/outputs/apk/release
```

Windows 使用 `gradlew.bat` 和 `python`，并将属性参数保持在引号内。Python 需 3.10 及以上，检查工具使用 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 中的 Build Tools 37.0.0，Windows 验证签名还需要 `JAVA_HOME`。这只是本地预览签名；跨构建机器的签名可能不同。正常 `assembleRelease` 不带该属性时保持未签名，正式发行仍按下一节使用自己的 keystore。

输出位于 `android/app/build/outputs/apk/release/`：

| 包 | 选择方式 |
|---|---|
| `app-arm64-v8a-release.apk` | 主流 ARM64 手机优先使用，体积最小且包含完整功能 |
| `app-armeabi-v7a-release.apk` | Android 14+ 的 32 位 ARM 系统 |
| `app-x86-release.apk` / `app-x86_64-release.apk` | 对应架构的设备或模拟器 |
| `app-universal-release.apk` | 四种架构合一；无法判断架构时使用 |

普通未签名构建的文件名在 `release` 后增加 `-unsigned`；不能直接安装。ABI 包只减少其他处理器的库，同版本的代码、资源和功能相同。原生库继续使用系统直接加载的打包方式，没有用重复解压来换取表面下载体积。手机最低版本提高后的空间节省与处理器分包、调试转精简构建的节省应分别理解；提高最低版本本身并非主要来源。

安装包检查会核对五个 APK 的版本、最低 SDK、定位权限缺失、全部声明语言、原版字体、离线扫码模型、JNI / WorkManager 入口、持久化 JSON 字段、16 KB ZIP 对齐，以及相同的代码和资源。可选 `--baseline <旧通用APK>` 额外比对旧包的原生库和静态资产，并检查预览覆盖安装签名一致。ZIP 对齐不等同于逐库 ELF 验证。脚本输出体积，不能证明完整真机运行正常，也不测量聊天历史或缓存占用；实际安装占用还取决于系统编译、数据和缓存。

0.1.6 本地构建记录如下，单位为十进制 MB（1 MB = 1,000,000 字节）：

| 比较 | 原包 | 精简包 | 减少 |
|---|---:|---:|---:|
| 0.1.5 通用 debug → 0.1.6 通用精简预览 | 77.07 MB | 58.08 MB | 24.6% |
| 0.1.5 ARM64 debug → 0.1.6 ARM64 精简预览 | 45.14 MB | 26.15 MB | 42.1% |
| 0.1.5 通用 debug → 推荐 ARM64 精简预览 | 77.07 MB | 26.15 MB | 66.1% |

未压缩 DEX 从 90.78 MB 降到 11.88 MB，减少约 86.9%。最后一行同时包含精简构建和按架构分包两种收益，不是同架构比较，也不是安装后空间的实测。R8 精简在之前的 release 配置中已启用；本次将其用于可覆盖现有本地开发安装的精简预览，补齐混淆保留和检查，并提高最低系统版本。没有把加密库的保留规则全部删除来追求更小体积。原理参见 [Android R8 优化说明](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)。

CI 同时构建 debug 与普通未签名 release，并以 `--unsigned` 检查精简包。debug 继续保留开发和物理 Mesh Lab 入口；正式包与本地精简预览不包含 ADB 测试钩子。

GitHub 预览与正式发行的区别、文件准备和发布说明见 [Release 发布](RELEASE.md)。

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
- 前台及后台改名后的双端显示、旧消息标签、同名用户未读、断网改名后重连，以及退避期间迟到消息不能恢复旧连接。
- 图片、文件、语音留言、取消发送、超限提示，以及频道与私聊 PTT。
- 蓝牙离线 mesh、支持设备间的 Wi-Fi Aware；关网后保持本地功能。
- Wi-Fi / 蜂窝切换、长时间断网、锁屏、后台、权限撤回、前台服务重启。
- panic 清除后旧连接关闭、历史与密钥被清理，不能重新注入清除前的数据。
- 与原版应用并存安装，以及本分支自身的签名更新和 APK 分享。

上游 Mesh Lab：`android/tools/release_gate/mesh_lab.py`。使用本分支实际包名配置相关测试工具；不要将设备序列号、日志、位置或真实聊天内容上传至公开仓库。

此开发环境没有执行真机验收，因此当前不作生产可靠性或兼容所有厂商后台策略的承诺。
