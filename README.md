# 薄肌

Android 本地个人助手。记录训练、收支和学习，安排今天，查看每周进度。

## 使用

安装 [调试 APK](./artifacts/boji-debug.apk)。需要 Android 8.0 或更高版本。

- **今日**：记录、加计划、设置每周目标。
- **助手**：文字或语音输入，核对草稿后保存；图片可交给已配置的视觉模型。
- **日历**：查看每天的安排与记录，完成、跳过或改期。
- **空间**：搜索、筛选、切换表格，查看支出和学习/训练时长。

一句话试用：`今天练拳40分钟，晚饭28元，学Redis半小时`。未连接模型时，这种明确输入也能本地整理成三条草稿。

助手内置 **Pi Agent Core 1.3.0**：在手机本地运行工具循环，加载 Skill、查询授权记录、生成草稿。设置里配置默认/备用模型，填写 HTTPS 地址、模型名和密钥；接口需支持 Chat Completions 与工具调用。图片能力由所选模型提供。近期记录与收支需分别授权。系统语音服务可能联网，是否可用取决于手机。

设置中可以编辑长期偏好、导入和移除 `SKILL.md`，以及清空会话记忆。内置“每日计划、每周复盘”两个 Skill。开启近期记录授权后，助手可读取偏好并恢复最近八轮以内的会话；更换授权或模型设置会清空旧会话。工具运行显示当前步骤，可以停止。

数据存于应用私有目录，不使用业务云数据库。密钥由 Android Keystore 加密保护；记录数据库不额外做应用层加密。支持密码加密备份、合并恢复和明文 CSV 导出。备份含记录、计划、目标、视图、重复规则、长期偏好及已保存记录引用的图片，不包含模型密钥、对话、导入的 Skill 和待确认草稿。卸载前请备份并记住密码。

## 构建

使用 JDK 17、Android SDK 36、Gradle 8.13；构建 Agent bundle 使用 Node.js（建议 22.19+）和 npm。Android Studio 可直接使用已打包的 Agent 资源；修改 `agent-runtime` 后需重新打包。本机 PowerShell 可运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 -Verify
powershell -ExecutionPolicy Bypass -File .\scripts\test-emulator.ps1 -Serial emulator-5554
powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 -Install -Serial emulator-5554
```

构建脚本安装锁定的 npm 依赖、重新打包 Pi，再运行检查。模拟器测试脚本只接受专用模拟器，生成验收数据后清理所创建的业务行。脚本只调整当前进程环境，不修改系统配置。

调试 APK 输出：`artifacts/boji-debug.apk`。Android 设备已连接时，可以使用 `adb install -r artifacts/boji-debug.apk` 安装。

## 实现

Kotlin / Jetpack Compose / SQLite，业务数据本地持久化。实际记录是完成依据；金额按整数分保存；同一天的训练按目标规则计次；草稿需要确认，重复确认不会重复入库。固定周重复规则保存后，打开应用时补齐未来八周实例，修改/删除只作用于本次。

当前是首版调试包。Skill 支持 Markdown 指令，不执行脚本或附带文件；长期记忆由用户编辑，暂不做自动记忆提取、向量检索、MCP 接入或多 Agent 并发。健康平台自动导入、通知提醒、跨设备同步及任意自定义表结构也未实现。

[产品设计](./docs/README.md) · [Agent 选型与架构](./docs/agent-runtime-evaluation.md) · [验收记录](./docs/implementation-status.md)
