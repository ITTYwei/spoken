# 首句优化实现与验证

日期：2026-10-12。数据库迁移 V11 已应用，开发后端已启动，ADB 手机已联调。

## 已实现的行为

1. 官方公开场景改为数据库读取，接入 `opening_line_low/mid/high`。两个官方场景通过 V11 调整为一个问题结尾，LOW 不再同时布置跟读和回答。
2. 创建时确定会话初始档位。最近 30 天、最多最近 10 场有有效发言且 CLOSED 的会话，从保存文本和该回合最新有效评测重建档位信号；至少三场、按场等权。旧长期统计非空值不直接使用。
3. 有序规则链：FIRST → RETURN（≥7个自然日）→ PLAN → RESUME → STREAK（≥5）→ DEFAULT。每个动态情境每档五份文案，默认直接复用场景文案。
4. SHA-256 稳定偏移与当地日期轮换；同一组输入连续五天不重复，第六天循环。首句决策创建时冻结并保存为 `training_session.opening_decision_json`，同会话重试和跨午夜不重算。
5. 开场和第一轮回复共用初始档位；之后仍由现有 LearnerLevelTracker 演进。首句只调用 `/say`，档位不进入 API 响应及界面。
6. 首句日志包含原文、情境、变体、版本、档位依据和素材摘要。全部模板及插值文本均检查单一问句、空槽位和中英文红线词。

## 现有数据与身份边界

- 当前 App 入口尚无认证主体，默认身份适配器只读取同设备且 `user_id IS NULL` 的匿名历史。不能根据 `user_device` 猜登录人，也不会读取账号私有计划。
- PLAN 的查询和规则已实现、经真实数据库测试。必须有服务端可信用户、`scheduled_date=今天`、有效未完成计划及对应语言主题。新增 scheduled_date 独立于原 due_date，不把截止日期当今日安排。当前匿名手机不会命中账号计划。
- RESUME 的查询和规则已实现、经数据库测试。只使用最近有效同场景会话的 `resume_topic_zh/en` 和非空 `resume_evidence`；DAO 提供明确待聊事实保存操作。当前自动阶段跟踪/待聊事实生产流程尚未实现，因此不会仅凭问号或 topics 标签猜测。
- 当前实施保留场景末尾问题，用动态承接句替换寒暄，使面试场景仍围绕自我介绍。计划/续聊只有符合当前场景的明确素材才进入。
- 近期档位在创建时从有效历史重新计算并写入首句快照；本轮没有实现账号累计统计 worker 或跨设备历史迁移。

## 注释与分层

新增生产类/接口及测试类含功能、体系架构/运行过程的 ASCII 图、具体输入输出案例和设计要点。新增 VO/命令的每个 record 组件及 PO 的每个字段均有独立注释。

identity 提供主体；learner 管近期档位和练习日期规则；practice 提供今日主题；scenario 提供分档底座；conversation 管首句纯规则和快照；Case 编排各领域并执行脚本轨；infrastructure 提供 MyBatis 数据访问。Agent 领域、ADK/MCP 目录与实现未调整。

## 验证结果

- 完整回归：31 项通过，包含 12 项首句规则测试、6 项用例测试、5 项 MySQL 集成测试及原有 8 项回归。
- 全部 75 个动态模板通过红线检查，两个官方场景的三档默认底座通过真实数据库文案检查。
- 集成测试仅创建随机 `opening_verify_*` 库，迁移 V1～V11 后测试账号历史隔离、空会话排除、近期信号重建、明确续聊证据、今日计划筛选及 JSON 快照。测试库结束后删除，不写业务库。
- 真实手机首句：首次测试会话 `s-e0821f81ce984f21`，从最近 7 场有效历史得到 LOW，显示“你好，我是 Amy。你今天做了什么？”。开场落库为 turn 98；重复 opening 返回 `already_spoken`。
- 手机真实发言“Hello hello.” 被识别、评测并保存为 turn 99；初始 LOW 与该轮生效 LOW 一致。
- 重新建会话 `s-7c161d0bb1d44e5a` 后，窗口为 8 场有效历史，仍使用同一 LOW 中文底座。
- 合成音频复测：识别完整英文测试句，turn 101，发音分 90.71；本轮信号 HIGH、生效档位仍为 LOW，符合首次上调需要两次连续同向信号的既有规则。
- 修正模型地址后第一轮回复成功：“我也打篮球。你可以试试说 I play basketball。你一般什么时候打球？”，回复和字幕已保存；该轮总延迟 30.913 秒，模型请求耗时 27.103 秒。它是正常对话生成轨，不是首句预热或首句 LLM 调用。
- 后续手机创建的新会话 `s-afd8a5e7a6a14b85` 重新计算了 9 场近期证据，初始档位为 MID，显示英文底座“Hi, I'm Amy. What did you do today?”。旧 LOW 未被永久缓存，当前学员历史变化会影响下一场的起点。

验证命令使用 Maven 仓库 `D:\develop\env\mvn_repo`：

```powershell
$env:JAVA_HOME='D:\develop\evn\jdk'
& 'D:\develop\env\apache-maven-3.9.16\bin\mvn.cmd' -o '-Dmaven.repo.local=D:\develop\env\mvn_repo' '-Dtest=OpeningPolicyTest,OpeningCaseTest,OpeningDatabaseIntegrationTest,ConversationVoiceCaseTest,AiAgentAutoConfigLogTest' '-Dsurefire.failIfNoSpecifiedTests=false' -DfailIfNoTests=false -DskipTests=false -DopeningDbIntegration=true -pl spoken-server-app -am test
```

## 运行与环境记录

- V11 执行前已完成只读一致性备份：`spoken-server-app/data/db-backups/spoken-before-start-20261012-173355.sql.gz`。
- ADB 转发仍为手机 8080 → 本机 8091；手机后端地址 `http://127.0.0.1:8080`。
- 后端仍通过先前 IDE 依赖 classpath 启动；本轮没有生成可执行 fat jar。PID 文件：`spoken-server-app/target/server-adb.pid`；运行日志：`target/server-adb.stdout.log`。
- 当前模型配置已切换为 `bonsai-2-27b`、`172.18.0.103:8989`。联调发现 base-url 原带 `/v1`，Spring AI 再追加 `/v1/chat/completions`，导致 404；仅去掉 base-url 尾部 `/v1`，保留当前模型和凭证配置。这不属于首句生成，首句不调用模型。
- 发现现有开发库 `app_user.created_at/updated_at` 已变回可空 BIGINT；迁移前备份中也是该结构。V11 不修改审计类型，本轮没有混入恢复该表的迁移。其他审计列检查记录位于 `target/opening-audit.log`，不能再将旧 V10 的全表验证结论当作当前库状态。

运行截图及详细结果保存在忽略目录 target 中；它们是验证产物，不需要提交到版本库。
