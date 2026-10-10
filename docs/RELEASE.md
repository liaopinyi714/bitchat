# Release 发布

源码的 GPL-3.0 开源许可和 GitHub Releases 是两个不同事项。源码已经开源，Releases 用于发布可安装 APK、版本说明与校验值。发布 APK 时，保留对应的完整源码、许可及上游说明，让用户能够重新构建修改。

## 0.1.6 开发预览

当前适合标记为 GitHub **prerelease**。安装包已通过本地构建、单元测试及内容检查，但尚未完成精简包真机启动、旧数据升级、BLE / Wi-Fi Aware、后台和媒体的完整验收。开发证书也不是长期正式发行身份。

建议预览 tag 使用 `v0.1.6-preview.1`，标题为 `bitchat 0.1.6 开发预览`。tag 必须指向完成检查的实际源码提交，不指向尚未提交的工作目录。之后需要调整时发布递增 versionCode 的新预览，不能在用户安装后随意换签名。

先按 [Android 精简构建](ANDROID.md#精简安装包) 构建、检查和保存以下文件：

- `bitchat-0.1.6-arm64-preview.apk`：从 `app-arm64-v8a-release.apk` 重命名，主流 ARM64 手机优先使用。
- `bitchat-0.1.6-universal-preview.apk`：从 `app-universal-release.apk` 重命名，包含四种架构。
- `SHA256SUMS`：对将要上传的这两个实际文件计算 SHA-256，采用 `哈希值  文件名` 格式。

如有 32 位 ARM 或 x86 设备，可同时发布同次构建的对应 ABI 包。不要上传 keystore、密码、R8 原始映射、原始日志、测试设备信息或完整本地构建目录。发布前检查 APK 中的签名证书与构建元数据，只上传明确允许公开的文件。校验值用于检测下载内容是否一致；它不能替代签名、来源验证和安全审查。

当前更新检查和远程 APK 下载使用 GitHub 的 `releases/latest`，它指向正式 release，预览版需要手动下载。不要为推送开发预览而把现有自动更新入口改成自动接收 prerelease。

在本仓库 [Releases](https://github.com/liaopinyi714/bitchat/releases) 的 **Draft a new release** 页面选择已验证源码提交，填写 tag、下列说明并上传检查后的文件。勾选 **Set as a pre-release**；先保存 draft 复核文件、版本与校验值，再公开。

以下说明可直接用于该预览，体积记录来自一次本地构建：

> 手机最低 Android 14（API 34）。推荐 ARM64 精简包约 26.15 MB；通用精简包约 58.08 MB，包含四种处理器架构。此前 0.1.5 通用调试包约 77.07 MB；推荐包的减少同时来自代码/资源精简与按处理器分包。
>
> 保留原版界面、语言、离线扫码、加密、媒体、蓝牙与受支持设备的 Wi-Fi Aware。频道协议和 Worker 保持兼容，本次无需重新部署 Worker。
>
> 这是开发签名的预览版。仅在签名相同、versionCode 增加且 Android 版本受支持时，才能覆盖已有开发安装。未经真机全功能验收，当前 Lint 仍存在既有错误。正式签名版本需要明确迁移，不能保证直接覆盖此预览。

发布说明中的 MB 使用十进制单位；不表示手机安装后的数据、缓存或系统编译占用。界面与功能未做重设计，但静态资源检查和 debug 单元测试不能证明 R8 后全功能已经真机实测。

## 正式发行

完成 [真机验收](ANDROID.md#必须完成的真机验收)，用自己长期保存的发行 keystore 签名，并将证书公共指纹用于本分支更新验证。不要使用开发证书作为正式发行证书。自己妥善保存私钥和密码；GitHub 只保存源码和允许公开的发行文件。

普通 `assembleRelease` 不带预览属性时仍产生未签名 APK。按 [自己的正式签名](ANDROID.md#自己的正式签名) 签名、验证并记录校验值。对已经安装预览版的用户说明签名迁移；不同签名不能直接覆盖安装，卸载会清除本地聊天数据，不能为方便发布要求用户盲目卸载。

本分支的自动更新与远程 APK 分享仍使用固定资产名 `bitchat-android-universal.apk`。正式 release 必须提供这一资产，不只发布 ARM64 包；否则这些现有功能会找不到下载包。可另外提供 `bitchat-android-arm64-v8a.apk` 供首次安装选用。release、源码 tag 和递增的 versionCode 必须一致。

上游的 [可重现构建](../android/docs/reproducible-builds.md) 和 [维护者发行指南](../android/docs/maintainer-release-guide.md) 保留作参考，其中官方仓库、Play 标识和既有发行密钥的说明不能直接照搬给本分支。只有按精确工具链完成独立双重构建并比对，才可以宣称可重现；本地精简预览未作这项声明。
