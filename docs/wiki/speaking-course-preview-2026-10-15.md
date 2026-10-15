# 全部口语课程与只读内容预览

本次按用户最新范围只导入口语。不加入词汇、发音词对、语法、写作、阅读或听力资料，不运行 BandReady 服务，不改 ADK 框架。

## 内容范围与追溯

来源目录 D:/develop/code/github/ielts-prep，固定提交 10282cc853a03b6984907fec0affe6cbbd4fe0b0，内容包声明 CC0-1.0。导入脚本核对 speaking_cards.jsonl 的发布 SHA-256，Windows 工作区 CRLF 先恢复 Git 原始 LF 再核对。

496 张原始口语题卡包括 Part 1 280 张、Part 2 108 张、Part 3 108 张。旧三课编号不变，新导入 603 个练习单元，共 606 课。Part 3 沿用教材原生主题分课，全部主问题和 counter_probe 完整保留；Part 2 保留总题、所有要点和收尾问题。目标与中文陪练指导为本项目适配，不冒充原文。

`scripts/import_bandready.py` 仅产生口语资源。`textbooks/catalog.json` 显式发布四本教材；`bandready-speaking/speaking_cards.source.jsonl` 保留完整口语来源字段，运行时不向模型发送整个来源文件。

## 用户流程

数字人页点击“练习”，选教材分项，可按课名或编号搜索，分批显示课列表。点击课只读取详情，展示目标、完整原题、练习方式和许可。Part 2 单独提示总题与表达要点的关系。明确点击“开始这课练习”才调用原模式切换能力。

未开始对话也能浏览；开始按钮会提示先回到主界面开始对话。目录或详情失败有独立重试，不伪造内容。媒体待机满 60 秒仍释放槽位，但保留当前只读浏览内容，不恢复旧会话版本或媒体。快速换课、关闭面板、切账号或会话代次变化后丢弃旧预览结果。

## API 与调用链

- `GET /api/v1/textbooks/catalog`：新增完整轻量目录。每本含 part、lessonCount 和课摘要 id/title/objective/questionCount，没有 questions 原文。
- `GET /api/v1/textbooks/{book}/lessons/{lesson}`：保留完整原题详情接口，无状态写入。
- `GET /api/v1/textbooks`：保留已有完整定义接口，兼容旧调用。
- `POST /api/v1/sessions/{id}/practice`：保留既有版本校验、幂等、旧回复取消与 SSE 行为。

Controller → PracticeCase → TextbookService → ITextbookRepository，由基础设施本地资源适配器加载。新增 SpeakingPartEnum 解释三种口语形式。Agent 查询改为每本最多三课候选并保留真实总量，避免完整题库撑爆工具结果；不会根据候选顺序替用户选课。

## 验证与运行

后端 27 项检查通过：逐张对照原始快照、全部 606 课通过现有上下文预算、旧三课兼容、工具回归与模式切换。自由聊天的开场文案可以调整，测试改为核对真实播报、幂等及最终模式，不锁定某一句开场。

Android 构建成功，13 项网络检查通过，验证目录与详情均为认证 GET、Part 2 枚举及完整原题解析。2026-10-15 10:57:47 已安装到当前手机 e7105a12。真机已检查课程目录、旧课完整预览和未开始时禁用开始练习；随后本地 IDEA 服务已加载新版，手机确认新增 Part 1 显示 277 课。新手机端在目录接口为 404 时兼容读取旧版课程，并提示重启，不对鉴权错误或写操作盲目重试。

不包含自动评分、完整考试计时或题号恢复。Part 2/3 已提供原题和对应引导，但本轮没有把所有课程逐一跑一遍真实语音对话。
