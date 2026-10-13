# DDD 领域与目录整理方案

## 已确认的产品边界

- Agent 保留独立领域，整个 `domain/agent` 恢复原目录和实现，包括 ADK、MCP、装配节点、插件和模型配置。按用户明确要求，不迁到基础设施。
- 发音评测保留独立领域，支持未来独立练习与评测历史。
- learner 与 practice 分开，分别负责长期学习档案和训练计划。
- 七个领域均建立完整标准目录；空包使用 `package-info.java` 占位，注明职责。
- Maven 本地仓库使用 `D:\develop\env\mvn_repo`。

依据本地 `.skill-sources/xfg-ddd-skills/SKILL.md`（v2.2.4）及其 project-structure、architecture、case-layer 参考。七个 Maven 模块、adapter 接口与实现位置、Case 实现目录来自技能约定；下面的业务领域划分是结合本项目提出的建议。

## 领域建议

| 包名 | 负责的业务 | 不负责的内容 | 当前落地情况 |
| --- | --- | --- | --- |
| agent | 智能体配置、装配、运行会话与内容回复能力 | 陪练会话状态、发音评分、长期学习档案 | 原始目录与实现完整保留 |
| identity | 用户身份、第三方绑定、设备归属、登录记录 | 学习水平、训练安排 | 已有表设计和完整占位包，业务代码待实现 |
| conversation | 陪练会话与轮次、会话状态、回复教学规则、会话总结 | 独立发音评分规则、长期学习档案 | 现有核心领域，优先拆分职责 |
| pronunciation | 评测请求、评分结果、评测记录与反馈 | 数字人会话生命周期 | 从 conversation 中抽出已有实现 |
| scenario | 场景内容、阶段、角色设定、分级开场内容 | 用户训练日程、实时会话状态 | 从 conversation 中抽出已有模型与仓储 |
| learner | 学习者长期画像、水平证据、进度统计 | 单次会话临时水平与训练排期 | 已有表设计和完整占位包，业务代码待实现 |
| practice | 训练计划、计划项、每日安排与完成规则 | 场景内容维护、实际会话运行 | 已有表设计和完整占位包，业务代码待实现 |

这是同一应用内的业务包边界，不意味着拆成独立服务，也不为尚未实现的功能创建空壳代码。表不是聚合：会话消息不要求全部加载进一个会话聚合；学习统计是派生结果，不能成为每轮对话必须同步成功的前置条件。

## 目录骨架

保留 `spoken-server-{types,api,domain,case,infrastructure,trigger,app}` 七模块。

```text
domain/
  agent/                                  # 完整保留原 model/service/armory 层次
  conversation/、pronunciation/、scenario/   # 首批整理已有代码
    adapter/port/                         # 领域需要的外部能力契约
    adapter/repository/                   # 本领域仓储契约
    model/aggregate/、entity/、valobj/
    service/                              # 业务规则，按职责继续分包
  identity/、learner/、practice/           # 已建立标准骨架，空包用 package-info 占位
cases/
  conversation/IConversationCase.java
  conversation/impl/ConversationCaseImpl.java
  pronunciation/IPronunciationCase.java
  pronunciation/impl/PronunciationCaseImpl.java
  catalog/                                # 能力查询用例，不是领域
infrastructure/
  adapter/port/                           # 实现领域端口
  adapter/repository/                     # 实现领域仓储
  dao/po/
  gateway/                               # 语音与数字人供应商通信；Agent 保留原实现
  gateway/dto/
  config/                                # 技术配置与运行组件装配
  redis/
trigger/http/、mq/、job/
app/                                      # 启动、环境配置、mapper 与迁移
```

基础设施保留技能规定的根目录，不新增根级 `agent`、`persistent` 或 `service`。供应商实现可在 gateway/config 内进一步组织；对外 DTO 放 gateway/dto。API DTO 可按 conversation/pronunciation/catalog 分包，接口路径和 JSON 字段保持兼容。

## 已有代码的迁移方向

| 当前代码 | 建议归属 | 理由 |
| --- | --- | --- |
| domain/agent 中 ADK Runner、ChatService、MCP、插件、Spring AI patch | 保留原路径与实现 | 用户明确要求 Agent 恢复原始层次 |
| AgentReplyGenerator（已移除） | case 直接调用 IChatService；事件转换归 agent/service/chat/AgentTextStream | Agent 是模型对话领域，跨域编排放 case |
| ReplyPromptBuilder | conversation/service/reply | 教学语气、提问与反馈规则属于陪练业务 |
| LearnerLevelTracker | learner/service/level，通过 ILearnerLevelService 提供会话档位能力 | 会话滞后跟踪与近期历史推断都归 Learner，case 负责传入初始档位与生命周期通知 |
| PronunciationServiceImpl、评测聚合/命令/结果/仓储/端口 | domain/pronunciation 对应目录 | 已完成领域提取 |
| ScenarioVO、IScenarioRepository | domain/scenario 对应目录 | 场景定义有独立的内容生命周期 |
| ConversationCaseImpl、PronunciationCaseImpl、CatalogCaseImpl | 各自 cases/{name}/impl | 遵循 Case 实现目录约定 |
| WavEncoder | 使用它的基础设施音频适配实现附近 | WAV 编码属于音频技术转换 |

跨域由 Case 协调：conversation 不直接持有 pronunciation 的业务服务或 scenario 的仓储。Case 获取场景快照交给会话；调用评测后，将陪练需要的反馈映射为会话自己的输入，避免直接传递另一领域的聚合。类型真正共享才进入 types，不把所有 VO 搬到 shared。

## 一轮语音的职责

Case 编排：检查会话 → 语音识别 → 记录用户消息 → 发音评测 → 会话水平与教学规则 → 生成回复 → 播报与事件输出。

会话领域管理状态、轮次归属及回复规则；评测领域管理评测规则与结果；基础设施处理模型流、供应商连接、重试及字节编码。评测失败是否继续陪练是用例策略，保留现有可降级语义。数据库事务只包必要的本地写入，不跨识别、评分和 LLM 网络调用。结束会话后的总结与长期统计通过可重试任务处理。

## 实施顺序与验证

1. 先移动 Case 实现、抽出 pronunciation/scenario，更新 imports 与 Spring/MyBatis 引用，保持接口行为。
2. Agent 完整保留，复核目录恢复和调用方引用；不调整 ADK/MCP 实现层次。
3. 从 ConversationServiceImpl 提取跨域流程到 Case，再按状态、轮次、教学回复规则拆服务。
4. 按真实产品需求实现 learner/practice/identity，避免为设计表提前造空服务。

每阶段使用指定 Maven 仓库编译，并验证创建/关闭会话、语音轮次、回复流、独立评测和场景查询。迁移时保留此前数据库 DATETIME 改动，不改已发布迁移脚本。

## 已落地与后续边界

已确认 learner 与 practice 分开：learner 描述“用户当前学得怎样”，practice 描述“接下来练什么、何时练、怎样算完成”。这两个领域已有数据设计与完整包骨架，具体业务实现仍待后续功能开发。

已有 Java 代码已完成 pronunciation/scenario 分包、Case 实现与装配器移入 impl、会话语音跨域编排迁入 Case、教学反馈使用会话自己的摘要模型、WAV 编码移到基础设施。会话教学规则分别放入 service/reply 和 service/level。Agent 原目录与实现恢复，保留原 Maven 依赖。

## 2026-10-12：Agent 调用边界调整

已参考 walissh-server 的 IChatService 入口，完成 case 直接编排 Agent 对话；详见 [验证记录](agent-case-boundary-verification-2026-10-12.md)。
