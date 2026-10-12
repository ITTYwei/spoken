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
