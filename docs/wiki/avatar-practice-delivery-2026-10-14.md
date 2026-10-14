# 数字人主界面与课程 Agent 交付记录

后续修复：用户实际语音对话发现必要上下文超出旧预算，已调整发布预算、补充六级回归并更新本机服务，见 [上下文预算修复](context-budget-fix-2026-10-14.md)。

2026-10-14，用户授权后续步骤依次继续。第 0–5 步代码与构建已完成；以下区分已验证行为与体验边界。未部署生产，未提交 Git，保留原有工作区改动。

## 体验方式

最新调试 APK：`D:\develop\code\project\jugao\android\app\build\outputs\apk\debug\app-debug.apk`，已安装 MI 8。

SHA256：`5D78021B020A4E4CB5B7980EC24E353107B9E1EC5284315BCBF07D57650787AA`。

手机测试账号与真实账号分开。USB 端口映射的手机 8080 指向本机 8092 验证服务；8091 原开发实例保持。8092 仅监听本机，进程编号在 `spoken-server-app/target/stage-five-server.pid`。新后端已应用开发库 V16；生产迁移未执行。手机原 USB 常亮设置已恢复为 0。

进入数字人 → 点击开始 → 点击顶部“练习” → 选择课。再次进入仍默认自由对话，数字人询问是否继续最近课。左上进入伙伴与设置。可尝试说“继续上次课程”“切回自由对话”“以后重点练面试，请记住”“删除我的长期学习目标”，结果以实际业务状态为准。

## 分步记录

| 步骤 | 记录 |
| --- | --- |
| 0 源码与脚手架 | [验证记录](avatar-practice-stage-zero-2026-10-14.md) |
| 1 主界面与待机 | [交付记录](avatar-practice-stage-one-2026-10-14.md) |
| 2 伙伴与设置 | [交付记录](avatar-practice-stage-two-2026-10-14.md) |
| 3 文本课程与按钮 | [交付记录](avatar-practice-stage-three-2026-10-14.md) |
| 4 继续与工具切换 | [交付记录](avatar-practice-stage-four-2026-10-14.md) |
| 5 个人偏好与伙伴工具 | [交付记录](avatar-practice-stage-five-2026-10-14.md) |

## 验证证据

后端 `target/stage-five-ddd-regression.log`：114 项整体定向回归通过；`target/stage-five-tool-tests.log`：当前模型通过现有脚手架执行 3 轮业务工具；`target/stage-five-db-tests.log`：14 项数据库与课程组合校验通过；`target/stage-five-final-package.log`：最终 26 项针对性校验与打包通过。计数有重叠，不相加。Android 构建和已有 11 项网络测试通过。两仓库 `git diff --check` 通过。

最终包启动成功，独立测试账号的真实 `/me` 返回空偏好和“正在了解”，查询端口装配正常；`/textbooks` 返回三课，随后撤销测试令牌。原有开发服务保留。

真机已验证主界面、静默准备、开始、设置候选取消、课程目录、选课首题、自由切换保持连接及重新进入后的真实最近课询问。图片见 `assets/avatar-practice`。最后修复空闲取消误标已完成字幕为“已打断”，最新截图没有此误标。

[课程面板](assets/avatar-practice/stage-five-courses-expanded.png)、[选课第一题](assets/avatar-practice/stage-five-course-active.png)、[继续课程开场](assets/avatar-practice/stage-five-continue-opening.png)、[最终字幕状态](assets/avatar-practice/stage-five-final-main.png)。

## 已知验证边界

完整自然语音控制、音色切换听感与声学回声质量仍需体验验收。额外无媒体准备的纯 HTTP 合成语音测试在 QUEUED 会话的文本流前置步骤失败，没有完成 ASR 全链路。该失败记录与独立模型工具测试不混作通过。

媒体首次准备受 GPU 槽位影响；本次真机课程切换保持一次连接，首次画面约 5.929 秒。此前第 1 步点击开始到 TTS 开始约 1.5 秒、二次协商约 159–262 毫秒；不是所有环境的性能保证。在线模型工具测试 3 轮约 24.4 秒，不等同麦克风到实际出声延迟。

当前仍采用每轮短 ADK 会话，业务状态每轮后端注入；没有教材上传、向量库、评分、完整考试或题号恢复。
