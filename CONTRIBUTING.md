# 开发与贡献

本分支复用上游 Android v2.0.1，以 GPL-3.0 开源。先阅读 [来源说明](UPSTREAM.md)、[架构](docs/ARCHITECTURE.md)、[协议](docs/PROTOCOL.md) 和 [Android 仓库规则](android/AGENTS.md)。

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
./gradlew :app:assembleDebug :wear:assembleDebug testDebugUnitTest lintDebug clientRewriteContractTest --no-daemon
```

Windows 使用 `gradlew.bat`。建议用短 ASCII 路径运行 Android 工具链；不把个人机器路径写进源码。Lint 任务成功不代表报告没有错误，见 [当前限制](docs/TESTING.md#lint-的限制)。

手机模块是共享源码的来源，Wear 通过 `syncSharedAppSources` 生成共享代码。不要手动复制共享文件或编辑生成目录。

在仓库根目录运行 `node tools/check-docs.mjs` 检查本分支文档的相对链接和标题锚点；CI 同样执行此检查。上游目录的历史文档保留原有结构。

## 修改约束

- 保留原版 UI 组件、字体和布局。新增面向用户的文案进入资源文件，至少同步英文、通用中文和繁体中文；协议字段、用户名称及诊断日志保持原值。
- 传输、身份和加密修改应补充可观察行为的回归测试与协议说明；不兼容协议变更必须升级协议版本。
- 不在主线程派生密钥或执行阻塞 I/O。退出、停止服务和 panic 后的异步任务必须失效。
- Durable Object 存储迁移保留既有标签；不得为了部署成功删除或复用已经应用的标签。
- 测试只用合成数据，不连接生产聊天服务、不提交构建产物、设备证据或凭据。真实传输和安全变更仍需物理设备验收。

## 提交和评审

提交说明写清触发问题、最终行为和验证结果。功能变化同时更新使用、部署、协议和测试记录中的相关部分。CI 只检查源码，不自动部署 Cloudflare 或发布 APK。

正式发布前完成 [Android 验收](docs/ANDROID.md#必须完成的真机验收)、签名与更新验证及双重可重现构建；自动测试不能代替这些门槛。
