# 构建、签名与 GitHub Release

本分支源码以 GPL-3.0 开源。Release 保存可安装 APK、校验值和对应源码版本；开源许可、发行签名和运行验收分别解决不同问题。

## 当前版本与发布状态

0.2.0（versionCode 8）开始使用独立的长期发行证书，手机最低 Android 14。安装包继续使用 R8 精简，提供 ARM64 和通用包；UI、频道协议、加密算法及 Worker 存储结构保持兼容。

当前按**发行候选版本**准备。自动构建、回归测试、签名与产物检查的具体结果见 [验证记录](TESTING.md)。真实手机持续运行、双机 BLE / Wi-Fi Aware、升级保留数据、后台与媒体验收尚未完成。首次上传应勾选 **Set as a pre-release**；完成 [使用前验收](OPERATIONS.md#使用前验收) 后，可将同一份候选发行转为正式 Release。不能仅根据 Gradle 成功就写成“长期稳定性已验证”。

推荐 tag：`v0.2.0`；候选标题：`bitchat 0.2.0 — 发行候选`。若候选发现问题，增加 versionCode 并发布新版本，不覆盖已经公开的 APK。

## 发行文件

| 文件 | 用途 |
| --- | --- |
| `bitchat-android-arm64-v8a.apk` | 主流 ARM64 手机，推荐手动安装 |
| `bitchat-android-universal.apk` | 四种架构通用；应用内更新 / 远程 APK 分享依赖这个固定名称 |
| `SHA256SUMS` | 本次上传文件的 SHA-256 清单 |
| `BUILDINFO.json` | 版本、源码提交、工具链、签名公共指纹与文件校验值，不含本地路径 |

同版本 ARM64 与通用包使用相同代码、资源、签名和 versionCode，可在支持 ARM64 的设备上互相覆盖。32 位 ARM、x86 和 x86_64 的独立 APK 仍由构建生成，可额外发布。GitHub 自动提供 tag 对应的源码压缩包；不要用无法对应产物的提交作为 tag。

应用更新读取 GitHub 的 `releases/latest`，它排除 prerelease。候选版需手动下载；正式发布后现有更新入口才会看到它。不要为了推送候选版修改客户端，让全部用户自动接收预览。

## 长期签名与旧版升级

公开身份材料：

- [发行证书](../release/certificate.pem)：仅含公钥证书。
- [签名轮换证明](../release/signing-lineage.base64)：公开的旧证书 → 新证书证明，不含私钥。
- `android/gradle.properties` 中的 `BITCHAT_GITHUB_RELEASE_CERT_SHA256`：固定的发行证书指纹，用于验证下载的发行 APK。

0.2.0 使用 APK Signature Scheme v3，最低 API 34 的手机支持该方案。迁移链允许从匹配的旧开发证书承接已安装数据，不授权用旧证书回退，也不继承旧证书的共享 UID、签名权限或认证特权。后续正常发行只需新发行密钥和公共迁移证明。

产物检查核对旧 APK 签名、迁移链、新 APK 当前签名、承接数据及禁止回退能力。静态检查不等于真实手机的覆盖安装。只有与链中开发证书相同的旧安装属于迁移范围；其他电脑自行构建的 debug APK、原版官方 APK、不同密钥签名的分支 APK 不在此范围。

遇到“签名冲突”先停止安装，核对包名、证书和版本。**不要为绕过错误先卸载应用**：卸载或清除数据会删除本地身份、历史和频道创建者身份。本项目没有能保证恢复全部身份与聊天的通用备份 / 导入功能。

## 维护者密钥备份

私有文件是 `release.p12`（PKCS#12 keystore）和 `password.txt`，密钥别名为 `bitchat-release`。它们应由维护者保存在仓库外。密码文件是明文凭据，与 keystore 同时泄露等同于私钥泄露。

1. 将 keystore 加密备份到至少两个独立位置，密码另存于密码管理器或其他受保护位置。
2. 保存公共证书、迁移证明、已发布源码提交和校验文件。
3. 在隔离的本地目录确认备份可打开、证书指纹与仓库一致；不要上传私钥来验证。
4. 不把密钥、密码、原始 R8 映射、开发日志、设备信息或完整构建目录放进 GitHub。
5. 丢失发行私钥可能导致无法覆盖升级；疑似泄露时停止发布并评估迁移，不能仅重建一个同名文件继续发行。

普通贡献者不需要发行私钥。自行 fork 应使用自己的应用标识、签名及发行信任材料；本仓库的签名工具会拒绝不匹配的密钥。

## 从源码生成发行文件

使用 JDK 21、Python 3.10+，Android SDK / Build Tools 按 `android/gradle/libs.versions.toml`。设置 `JAVA_HOME`、`ANDROID_HOME`。以下从仓库根目录运行；Windows 使用 `gradlew.bat` 和可用的 Python 命令。

```sh
cd android
./gradlew :app:assembleDebug :wear:assembleDebug :app:assembleRelease testDebugUnitTest lintDebug clientRewriteContractTest --no-daemon
cd ..
python3 tools/verify-apk.py --apk-dir android/app/build/outputs/apk/release --unsigned
```

普通 release 保持未签名。发行不要传入 `bitchat.previewSigning`。用实际文件路径替换占位符；输出必须是新目录：

```sh
python3 tools/sign-release.py --input-dir android/app/build/outputs/apk/release --output-dir .build/signed-0.2.0 --keystore <仓库外的release.p12> --password-file <仓库外的password.txt>
python3 tools/verify-apk.py --apk-dir .build/signed-0.2.0 --release
```

若保存了此前发布的通用 APK，为两条命令添加 `--baseline <旧通用APK>`，同时验证原生库、静态资产及签名升级关系。对比排除 `assets/dexopt/baseline.prof` 和 `baseline.profm`，它们随 DEX 变更重新生成。未来有意更新其他资产时，旧包字节对比会失败，维护者须审查变化并更新验收范围，不能忽略失败。

签名脚本先检查全部未签名包，再签名五个架构产物，最后验证实际 APK 中的迁移链。它不修改输入、不覆盖已有输出目录，也不把密码放进命令行或输出。失败目录中的文件不能发布。

通过检查后，将 ARM64 和通用包复制为上表名称，生成校验清单和构建信息。签名后不要再修改 APK、压缩重打包或执行 zipalign。工具细节见 [Android apksigner](https://developer.android.com/tools/apksigner)。

将最终源码与文档提交后，可用以下命令生成四个发布附件。它要求 Git 工作区干净，读取当前提交作为 `sourceCommit`；因此必须先构建该提交对应的代码，再打包，不能拿其他提交的旧 APK 冒充。输出目录须不存在：

```sh
python3 tools/package-release.py --signed-dir .build/signed-0.2.0 --output-dir .build/releases/v0.2.0
```

`BUILDINFO.json` 记录声明的源码来源与工具链，不是独立可重现构建证明。`SHA256SUMS` 包含两个 APK 与构建信息文件的哈希，不对自身计算哈希。

## 手动上传到 GitHub

1. 打开 [新建 Release 页面](https://github.com/liaopinyi714/bitchat/releases/new)，使用有仓库写权限的 GitHub 账号。
2. 创建 tag `v0.2.0`，Target 选择本次检查后的精确提交。若 main 已有更新，采用 `BUILDINFO.json` 中的 `sourceCommit`，不要直接采用新的 main。
3. 标题填写 `bitchat 0.2.0 — 发行候选`，粘贴 [0.2.0 发布说明](RELEASE_NOTES_0.2.0.md)，保留验证范围和待完成事项。
4. 附件区上传 ARM64 APK、通用 APK、`SHA256SUMS`、`BUILDINFO.json` 四个文件。不要改动固定 APK 文件名或上传签名目录。
5. 等待四个上传任务完成。勾选 **Set as a pre-release**，先 **Save draft**，复核版本、目标提交、文件名和说明。
6. 确认后点 **Publish release**。发布后重新下载两个 APK，核对 SHA-256，并检查安装界面。
7. 完成真机验收后，编辑该 Release，取消 prerelease 并设为最新版本。若修改过源码或 APK，发布递增版本，不替换旧 tag 下的文件。

Windows 验证下载文件：`Get-FileHash .\bitchat-android-arm64-v8a.apk -Algorithm SHA256`。结果应与 `SHA256SUMS` 一致。校验值确认文件一致性，可信来源及 APK 签名仍需同时核对。

页面操作参见 [GitHub 管理发行版](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)。发布 APK 不会部署 Cloudflare；中继仍按 [部署说明](DEPLOYMENT.md) 由仓库所有者操作。

## 后续维护

每次发行递增 versionCode、更新 versionName、变更记录和说明，保留同一密钥与迁移链。先检查，再签名、验收、发布。发现已发布问题，优先推出更高 versionCode 的修复；普通安装器会阻止降级，不应把卸载或清除数据作为常规回退步骤。

上游 [可重现构建](../android/docs/reproducible-builds.md) 与 [维护者发行指南](../android/docs/maintainer-release-guide.md) 的官方仓库、Play 身份和密钥属于上游。本分支没有完成独立双重构建比对，不声明二进制已验证可重现。
