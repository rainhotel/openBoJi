# 调研记录与证据边界

检索日期：2026-10-18。采用产品官方页面和平台开发者文档。没有进行竞品付费试用、真机性能测试、用户访谈或市场规模测算。

## 产品资料

| 来源 | 核实到的能力 | 本项目的设计启发 | 不能据此声称 |
| --- | --- | --- | --- |
| [Hevy 官方功能](https://www.hevyapp.com/features/) | 训练组次/重量、训练模板、休息计时、历史表现、体测等 | 训练需要专用结构和快速操作，不能只有文字日记 | 本项目比 Hevy 更专业；用户愿意迁移 |
| [TickTick 官方](https://ticktick.com/?language=en_us) | 任务、日历、习惯与专注工具 | 今日执行与长期规划需要共享任务和日历 | 日历和打卡是新的差异化；该产品完全缺乏 AI |
| [Anytype 官方](https://anytype.io/) | 本地存储、离线优先、移动端、对象与数据库视图 | 本地优先和关联数据空间已有成熟先例 | 本地优先等于独一无二；所有 AI 功能都离线 |
| [Actual Budget 官方](https://actualbudget.org/) | 本地优先、预算、账户、交易、报表、可选同步与加密 | 预算和现金流应由确定性规则计算；用户应掌握数据 | 海外银行同步能力可直接用于中国；本项目已能自动读取支付数据 |
| [Lark Base 日历官方帮助](https://www.larksuite.com/hc/en-US/articles/359626216875-use-calendar-views-in-base) | 记录通过日期字段显示为日历，支持日/周/月视图 | 列表、表格、日历应是同一批记录的不同视图 | 必须接入飞书；必须复刻完整多维表格引擎 |
| [Notion AI 官方说明](https://www.notion.com/help/ai-safety) | 工作区内 AI 搜索、对话、写作及第三方模型处理说明 | AI 工作空间已有竞争；需要清楚说明数据发送边界 | 本项目的完整组合绝对没有竞品 |

结论：以上资料证明各子能力已有供应，并不证明本项目的用户需求、留存、付费意愿或竞争空白。当前机会判断是推论：**面向个人的低摩擦多领域录入，加上可核对、可撤销的跨领域安排，值得做小规模验证**。

## Android 与本地能力资料

| 来源 | 已核实事实 | 对方案的影响 |
| --- | --- | --- |
| [Jetpack Compose](https://developer.android.com/compose) | Android 推荐的现代原生 UI 工具包 | Android 单平台原生方案的候选依据，并非已经选定 |
| [Room](https://developer.android.com/training/data-storage/room) | 面向设备本地结构化数据持久化；多端同步需要额外方案 | 本地数据层候选；Room 本身不能证明数据库已加密或已备份 |
| [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent) | 持久后台任务、约束、重试、弹性调度；遵循系统省电机制 | 队列与延期任务可用；不能承诺 Agent 24 小时常驻或每次准点执行 |
| [Health Connect 可用性](https://developer.android.com/health-and-fitness/health-connect/availability) | 对设备、系统和 Google Play 服务有适用条件 | 中国大陆手机不能假定可用；首版保留手动录入，实机确认后再集成 |
| [Health Connect 数据读取](https://developer.android.com/health-and-fitness/health-connect/read-data?hl=en) | 数据按权限读取；历史与后台访问受额外限制 | 不能承诺安装后自动拿到所有健康平台与手环数据 |
| [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) | 可检测设备是否提供端侧语音服务；可用性并非普遍保证 | 先检查本机能力；没有时提示云端识别或保留录音待处理 |
| [ML Kit Android 文字识别](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) | 提供 bundled/unbundled 选项，涉及模型体积与下载 | 中文 OCR 需验证选定分发包在目标手机的离线可用性，不能只看 API 名称 |

备查：曾对纯离线模型可行性作外围检查。[Android 端侧模型文档](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android)涉及设备性能条件；[Apple Foundation Models 可用性说明](https://developer.apple.com/documentation/foundationmodels/generating-content-and-performing-tasks-with-foundation-models)要求检查设备与地区可用性。这些不作为首版依赖，也没有据此作速度、内存、功耗或中文准确率承诺。实际实现前重新核对当前 SDK 路线。

## 研究仍未覆盖的部分

- 目标用户当前具体用哪些中国本地应用、每天切换多少次、哪些记录最容易放弃。
- “搏击、理财、学习”相关网络梗的流量规模与持续时间；不把梗的传播等同于真实需求。
- 商业版价格、法律适用与上架要求；本轮不作法律结论。
- Android 目标真机、国产系统省电、语音、OCR、无 Google 服务环境的表现。
- 实际云端模型的结构化输出质量、网络、成本与数据处理条款。

以上问题通过访谈、真实样例与后续验证解决，不用主观评分替代。
