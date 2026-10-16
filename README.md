# spoken-server

数字人口语训练（英语陪练）后端。DDD 脚手架（Google ADK agent）+ 迁入的 OpenTalking 数字人对话后端。

> **⚠️ 开发前先读 [`docs/wiki/llm-wiki.md`](docs/wiki/llm-wiki.md)** —— 它是本项目的「AI 衔接知识库」，
> 记录了架构、踩过的坑、迁移记录、环境与待办。换工作目录 / 开新 AI 会话时，先让 AI 读这份文件即可直接衔接。
>
> **产品设计逻辑看 [`docs/wiki/product-design.md`](docs/wiki/product-design.md)** —— 人格契约（零评判 / 必追问）、
> 分级语言策略、发音评测的可见性边界、交互与降级语义。改产品行为先读它。

## 快速启动

```bash
mvn -DskipTests clean package
java -jar spoken-server-app/target/ai-agent-scaffold-lite-app.jar
# 默认 dev profile，端口 8091，MySQL 172.18.0.102:13306/spoken
```
## 领域说明
已整理成文档：[领域、子领域与业务归属](/D:/develop/code/project/jugao/spoken-server/docs/wiki/domain-responsibilities-2026-10-16.md)。

当前是 **六个业务领域**：

| 领域 / 子领域 | 负责什么，应该写什么业务 |
| --- | --- |
| **identity 身份账号** | 注册登录、资料、明确偏好、伙伴形象和音色选择 |
| **content 学习内容** | 教材、课文、原题、场景、目录和内容有效性 |
| **coaching.conversation 会话** | 会话生命周期、用户轮次、回复有效性、打断、完成回执 |
| **coaching.teaching 教学** | 教学策略、跟读目标、纠音次数、追问、待播计划与已播任务 |
| **coaching.practice 练习** | 当前课程、练习模式、切课版本、继续邀请、最近课程 |
| **coaching.history 历史** | 已保存会话与轮次的查询、分页和明细 |
| **assessment.pronunciation 发音评测** | 音频评分、音素和单词表现、评测结果有效性 |
| **assessment.expression 表达观察** | 独立回答判断、完成程度、候选档位和置信度 |
| **learning 学习进展** | 证据接纳、跨场等级、手动难度、能力描述和练习统计 |
| **companion 伙伴** | 回复生成、上下文选择、历史召回、长期记忆和遗忘 |

`companion` 目前按 `chat / context / memory` 分组，尚未独立成子域；`shared` 是公共技术契约，不是业务领域。

最关键的归属区别是：**教材有什么题归 content，这轮教哪道题归 teaching，实际播了什么归 conversation，回答得怎样归 assessment，长期能力如何归 learning，带着哪些背景生成回应归 companion。**

跨领域流程写在 **Case**，业务判断写在 **Domain**，数据库和模型、数字人厂商接入写在 **Infrastructure**。文档已补齐数据流图、状态归属、具体代码入口和新增需求速查表；本次只修改文档。域流程写在 Case，业务判断写在 Domain，数据库和模型、数字人厂商接入写在 Infrastructure。文档已补齐数据流图、状态归属、具体代码入口和新增需求速查表；本次只修改文档。