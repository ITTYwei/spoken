# 第一阶段后端接口与接入顺序

2026-10-14 最新增量已接入相邻 Android：伙伴设置、文本教材、当前练习模式与 Agent 工具。下面的原阶段描述为历史范围；新接口与事件见文末，构建及体验边界见 [整体交付](avatar-practice-delivery-2026-10-14.md)。

原第一阶段实现账户、首页和“我的”的后端接口，并沿用统一数字人对话链路；当时未修改 Android。2026-10-14 增量已加入教材练习及 Android 主界面。练习回顾、练习记录、提醒、登录设备管理、注销及账号数据删除仍留待后续补齐。

2026-10-14 接入更新：相邻 Android 项目已完成数字人主界面与静默待机第 1 步，见 [交付记录](avatar-practice-stage-one-2026-10-14.md)。此处原接口路径不变。实际开发配置下，登录响应 `expiresAt` 为带小数的 Unix 秒；客户端到期解析兼容数字字面量和历史 ISO 字符串，不能仅用 `Instant.parse` 恢复令牌。

注册始终只提交账号和密码。学习目标、兴趣来自真实对话的明确陈述；英语能力来自近期独立回答证据，不在注册时提问，不进行连续画像问卷。

## 账户与登录态

| 方法 | 路径 | 请求体 |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | `{"account":"learner_1","password":"password123"}` |
| POST | `/api/v1/auth/login` | 同上 |
| POST | `/api/v1/auth/logout` | 无 |
| POST | `/api/v1/auth/password` | `{"oldPassword":"password123","newPassword":"newpassword123"}` |

账号为4至32位字母、数字或下划线，大小写统一；密码8至128位。注册与登录返回`token/expiresAt/userNo/account`。客户端安全保存token，此后所有`/api/v1/**`请求（含SSE）携带`Authorization: Bearer <token>`。注册、登录、幂等退出和OPTIONS不要求有效登录态。不要把token放在URL查询参数里。

登录态持续30天并在服务重启后保留；到期返回401，客户端回登录页。退出撤销当前令牌，修改密码撤销全部令牌并返回`status=login_required`。账号冲突409；会话不存在和访问他人会话都返回404。既有匿名设备会话不迁给账号，不能仅凭deviceId认领旧数据。

## 首页

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/home` | `avatars/voices/selection`，卡片信息与上次选择 |
| PUT | `/api/v1/home/selection` | `{"avatarId":"anchor","voice":"Cherry"}`，保存选择 |
| POST | `/api/v1/home/voice-preview` | `{"voice":"Cherry"}`，返回`audio/wav` |
| POST | `/api/v1/sessions` | `{"deviceId":"手机安装ID","avatarId":"anchor","voice":"Cherry"}`，开始统一对话 |

试听使用上游独立TTS接口，不创建数字人会话。只展示当前TTS引擎的系统音色，屏蔽上游其他账号的自定义声音。形象名称、图片地址来自既有目录；首次选择为空，客户端可以选择第一项。建会话未传形象/音色时优先恢复上次选择，再补默认项；失效选择返回400并要求重新选择。音色与服务器当前TTS引擎保持一致。

会话统一从`free`进入；`scenarioId`不作为新建会话的用户练习问卷。具体目标和练习内容在对话中确认或切换。内部设备索引由账号与安装ID共同构成，同一手机更换账号不能影响另一人的会话或记忆。

主界面待机调用顺序：创建会话 → 订阅 SSE → 查询 `GET /api/v1/sessions/ice-config` → `POST /api/v1/sessions/{sessionId}/offer`，初次音视频为只接收。只有用户点击开始且实际连接就绪后，才调用 `POST /api/v1/sessions/{sessionId}/opening`；待机不调用开场或上行语音接口。首次主动语音在原媒体连接上再次调用 offer 接口，切为双向音频。未开始 60 秒、后台或退出时沿用关闭会话接口释放。首次默认候选不持久化，确认/应用通过既有选择接口保存，历史有效保存选择视为已确认。

## 我的

| 方法 | 路径 | 请求体或参数 |
| --- | --- | --- |
| GET | `/api/v1/me` | 返回`profile/english/preferences` |
| PATCH | `/api/v1/me/nickname` | `{"nickname":"小林"}` |
| PUT | `/api/v1/me/display` | `{"subtitles":true,"translation":"on_demand"}` |
| PUT | `/api/v1/me/preferences` | `{"key":"learning_goal","type":"GOAL","content":"练习旅行交流"}` |
| DELETE | `/api/v1/me/preferences?key=learning_goal&type=GOAL` | 删除目标 |

兴趣的type为`PREFERENCE`，key为`preference:<兴趣标识>`，内容为用户确认的陈述。接口只允许管理目标和兴趣，不允许改写内部能力等级。修改和删除同时推进历史遗忘边界，旧原文不再参与上下文，旧回合迟到写入不能恢复被删除事实。用户以后重新明确陈述仍可重新保存。

`profile`含头像地址、昵称、账号和已保存选择；头像地址为空时客户端显示默认头像。本阶段只支持修改昵称。`english.status=learning`时显示“正在了解”；近期30天最近10场内至少3场、20条有效独立回答才返回`ready`及自然语言能力描述，不返回等级或分数。跟读、低置信度、重复回合不计入依据。展示不受教学升档消耗证据影响。

字幕偏好是客户端呈现设置，SSE照常发送字幕，客户端按设置决定是否显示；翻译仅支持`on_demand`和`off`，无自动翻译。

## 统一对话

继续使用既有`offer/events/opening/voice/interrupt/close`接口。语音上传为AEC后的16kHz单声道s16le PCM，字幕和播报状态从SSE接收。每个会话子接口都先检查账号归属，包括评测和回合查询。

按需翻译调用`POST /api/v1/sessions/{sessionId}/turns/{turnId}/translation`，只翻译属于当前会话、已经定稿的数字人回复，返回`turnId/text`。翻译使用独立短模型会话，不播报、不写教学历史或能力画像。关闭翻译偏好时请求返回400；无定稿回复时等待说完再请求。

开场字幕没有教学回合，调用`POST /api/v1/sessions/{sessionId}/opening/translation`，翻译服务端已冻结并提交播报的首句，返回`turnId=0/text`；归属和翻译偏好检查与普通回复一致。

## Android页面接入

客户端位于相邻`android`仓库。登录和注册仅填写账号、密码；登录令牌由Android Keystore加密保存在本机，绑定服务器地址，密码不保存。所有REST与SSE请求使用同一登录快照，旧账号迟到的401或成功响应不能覆盖新账号状态。

首页提供数字人图片、名称、音色选择与试听；选择自动保存到账号，开始聊天前再次确认。底部“我的”支持昵称、能力描述、目标和兴趣管理，以及字幕和按需翻译偏好。退出或修改密码后清理页面资料和对话；退出前先关闭当前会话。麦克风权限仅在开始聊天时申请。离开首页或进入后台会停止试听，释放播放器并删除临时音频。

调试包构建命令：`gradlew.bat :core-net:testDebugUnitTest :app:assembleDebug`。网络测试覆盖REST/SSE鉴权一致、跨服务器不发送令牌、401失效、旧401不影响新账号、旧成功响应丢弃。构建通过不代表实机语音链路已验证；完整联调需要先部署新后端和V15迁移，再验证试听、WebRTC、收音、字幕、打断和翻译。

## Java账户代码分层

参考其他领域的目录约定，账户领域使用`adapter/repository`和`adapter/port`定义存储与媒体契约，`model/valobj`承载独立值对象，`service`定义服务接口、`service/impl`实现业务规则。`service/security`处理密码与令牌摘要，`support/AccountContext`负责请求身份上下文。HTTP入口依赖服务接口，原来的嵌套资料、登录结果、选择等对象已拆出，JSON字段保持兼容。

基础设施沿用`adapter/repository → dao → mybatis/mapper`结构：`AccountRepository`、`ProfileRepository`组织事务和PO/VO转换；`IAccountDao`、`IAccountProfileDao`及对应Mapper承担SQL，`dao/po`提供数据库查询投影。发令牌与改密共用凭证行锁，资料修改与遗忘边界共用画像行锁，均由仓储事务控制。媒体实现仍位于`adapter/port`，通过现有Gateway访问上游服务。

## 验证与上线

新增Flyway `V15__account_phase_one.sql`，复用已有账户表并新增持久令牌及呈现偏好列。业务数据库迁移和服务重启尚未执行；上线前客户端必须先接入Bearer认证，旧版匿名客户端会得到401。

定向测试需显式传`-DskipTests=false`，因为项目默认跳过测试。`AccountDatabaseIntegrationTest`只有传`-DaccountDbIntegration=true`才连接开发MySQL；它创建随机`account_verify_*`库，运行全部迁移并在结束时仅删除自己的库，不操作业务库。

## 文本课程与练习模式（2026-10-14）

这些接口直接返回 JSON，不使用旧 Agent 管理接口的 Response 外壳。全部要求 Bearer；会话路径沿用 `sessionId` 归属拦截，访问他人会话返回 404。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/textbooks` | 本地教材、来源、许可、版本及课目录 |
| GET | `/api/v1/textbooks/{textbookId}/lessons/{lessonId}` | 课标题、目标、完整题目和项目教学辅助 |
| GET | `/api/v1/sessions/{sessionId}/practice` | 当前模式、版本、最近课和待选择状态 |
| POST | `/api/v1/sessions/{sessionId}/practice` | 按钮直接切换；不创建新媒体连接 |

课程请求示例：

```json
{
  "mode": "COURSE",
  "textbookId": "bandready-daily",
  "lessonId": "food-cooking",
  "operationId": "每次用户动作生成的新 UUID",
  "expectedVersion": 0
}
```

自由对话传 `mode: FREE`、操作标识与期望版本，教材/课编号省略。成功返回实际 PracticeState：`mode/textbookId/lessonId/lessonTitle/version/pendingContinue/recentTextbookId/recentLessonId/recentLessonTitle`。空字段可能省略，客户端应按可空处理。当前 FREE 不表示清除最近课程。

成功操作重试必须使用相同标识和相同参数；不会重复取消、保存或播报。同标识异参数和旧版本均返回 409，客户端重新查询实际状态，不自行重复写入。不存在的课程返回 404，超预算课程返回 413。数据库失败不提交新模式；状态已成功但第一题播报失败时，SSE 错误说明实际情况，不能把已生效选择回滚成假状态。

新增 SSE：`practice.changed` 的 data 为完整 PracticeState；客户端仅接受不旧于本地版本的状态。`partner.changed` 的 data 为保存成功的 `avatarId/voice`；手机等本轮 `assistant.done`（上游 speech.ended）再应用、静默准备新连接。

新会话默认自由对话。有最近课时，后端冻结开场询问；只有对应播报完成才使 `pendingContinue=true`。被打断、回复编号不符或版本改变的询问不再生效。继续课程只恢复课，不恢复上次题号。

Agent 的工具参数没有账号、会话、轮次、操作版本或操作标识；可信能力由服务端每轮捕获并通过 Spring AI ToolContext 传递，不依赖 HTTP 线程变量。查询工具返回实际目录/状态；写工具走 Case，主体/活性/版本责任链检查后执行。工具结构化回执和系统标识不进入 TTS。Agent 负责自然语言调用，规则只对当前任务中的明确短选择兜底，失败不自动重试。

开发库已应用 V16 最近课程迁移。本次本机验证实例为 8092，旧 8091 仍在运行；新 Android 的课程功能须连接新版本后端。生产上线和迁移执行不属于本次交付。
