# 开发与贡献

本分支复用上游 Android v2.0.1，以 GPL-3.0 开源。先阅读 [来源说明](UPSTREAM.md)、[架构](docs/ARCHITECTURE.md)、[协议](docs/PROTOCOL.md)、[维护记录](docs/REVIEW.md) 和 [Android 仓库规则](android/AGENTS.md)。

## 本地检查

中继使用 `worker/package.json`、锁文件和 `.node-version` 固定工具版本：

```sh
cd worker
pnpm install --frozen-lockfile
pnpm check
pnpm test
pnpm dry-run
```

Android 使用 JDK 21，SDK 版本以 `android/gradle/libs.versions.toml` 为准：

```sh
cd android
./gradlew :app:assembleDebug :wear:assembleDebug :app:assembleRelease testDebugUnitTest lintDebug clientRewriteContractTest --no-daemon
python3 ../tools/verify-apk.py --apk-dir app/build/outputs/apk/release --unsigned
```

Windows 使用 `gradlew.bat`。建议用短 ASCII 路径运行 Android 工具链；不把个人机器路径写进源码。Lint 任务成功不代表报告没有错误，见 [当前限制](docs/TESTING.md#lint-的限制)。

手机模块是共享源码的来源，Wear 通过 `syncSharedAppSources` 生成共享代码。不要手动复制共享文件或编辑生成目录。

在仓库根目录运行 `node tools/check-docs.mjs` 检查本分支文档及 Android 模块入口的相对链接和标题锚点；CI 同样执行此检查。上游目录的其他历史文档保留原有结构，描述上游行为时应结合分支文档判断。

## 修改约束

- 保留原版 UI 组件、字体和布局。新增面向用户的文案进入资源文件，至少同步英文、通用中文和繁体中文；协议字段、用户名称及诊断日志保持原值。
- 传输、身份和加密修改应补充可观察行为的回归测试与协议说明；不兼容协议变更必须升级协议版本。
- 不在主线程派生密钥或执行阻塞 I/O。退出、停止服务和 panic 后的异步任务必须失效。
- 消息归属和未读优先按身份判断；只在缺少身份字段的历史消息中使用昵称回退。身份重建后刷新本机身份，旧消息标签不随昵称变化重写。
- 连接失败立即废弃其回调权限，覆盖退避期间的迟到消息。测试使用可注入的调度器、socket 和合成身份，不以任意延时等候网络。
- Durable Object 存储迁移保留既有标签；不得为了部署成功删除或复用已经应用的标签。
- 测试只用合成数据，不连接生产聊天服务、不提交构建产物、设备证据或凭据。真实传输和安全变更仍需物理设备验收。

## 提交和评审

提交说明写清触发问题、最终行为和验证结果。功能变化同时更新使用、部署、协议和测试记录中的相关部分。CI 只检查源码，不自动部署 Cloudflare 或发布 APK。

正式发布前完成 [Android 验收](docs/ANDROID.md#必须完成的真机验收) 和签名与更新验证；自动测试不能代替真机。尚未完成时保持候选 Release，并列明剩余事项。只有完成独立双重构建后才声明可重现；本分支目前没有该项证据。长期签名、四个发布附件和密钥备份见 [发行指南](docs/RELEASE.md)。

## 提交问题

普通错误使用仓库的 Bug report 模板，提供版本、预期结果、实际结果及合成复现步骤。不要上传原始日志、真实聊天、账号凭据或设备标识。安全问题按 [安全报告方式](SECURITY.md#报告问题) 私密沟通，不在公开 Issue 中披露可利用细节。
