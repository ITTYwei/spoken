# ADB 手机联调记录（2026-10-12）

## 运行状态

- 手机：小米 8，ADB 序列号 `b955e2bc`，应用 `com.spokenai.app`。
- 当前后端运行在电脑 `8091`，dev profile，Agent 已装配。
- 手机地址已切换为 `http://127.0.0.1:8080`。
- ADB 转发：手机 `8080 → 电脑 8091`，手机 `8091 → 电脑 8091`。
- 手机已进入 anchor / FlashHead 会话，数字人画面、开场字幕与测试回复可见。保持服务与会话运行，真实麦克风可由用户按住说话继续测试。

## 已验证

| 项目 | 结果 |
| --- | --- |
| 能力、场景、模型、形象四个目录接口 | 正常；FlashHead connected=true |
| 手机创建会话及媒体连接 | 正常，画面和开场字幕可见 |
| 发音评测健康检查 | status=ok |
| 合成英文音频进入语音接口 | 16 kHz、单声道、16 bit PCM，3.7 秒 |
| 语音识别 | 完整识别测试句 |
| 发音评测及保存 | assessmentId=81，score=90.71，173 ms |
| Agent 回复、播报下发、手机字幕与记录保存 | 正常，turnId=97 |
| 数据库审计字段 | 19 张业务表、38 个字段均为 NOT NULL DATETIME，默认 CURRENT_TIMESTAMP；19 个 updated_at 均自动更新 |

测试句：`Hello, I like playing basketball. What do you like to do?`

本次语音接口验证使用合成测试音频，不代表手机真实麦克风采集已经验证。

## 启动与数据库处理

启动前保存一致性备份：`spoken-server-app/data/db-backups/spoken-before-start-20261012-161845.sql.gz`，包含 17 张表、235 行。目录已被 Git 忽略。

V5 历史 checksum 与本地不一致。先在随机临时库执行当前 V5，逐表核对三个表的列、索引及约束（忽略注释和自增计数），全部一致后，带旧值条件仅修复 V5 checksum；临时库已删除。

服务启动成功执行 V8、V9、V10，开发库 `spoken` 当前版本为 V10。新设计的表已存在，但身份、画像、计划和收尾 worker 的业务功能仍需后续开发。

所有模块代码编译成功。Spring Boot 打包插件遇到镜像 TLS 握手失败，本次复用已有 IDE 依赖类路径，启动最新 `target/classes`，没有使用可执行 fat jar。运行依赖从指定 Maven 仓库读取；临时运行参数为 `spoken-server-app/target/runtime-java.args`。

运行日志：`spoken-server-app/target/server-adb.stdout.log`、`server-adb.stderr.log`。测试响应与手机截图位于同目录的 `voice-test-result.json`、`phone-screen-result.png`。这些文件由后续 clean 清理；数据库备份保存在 data 下。
