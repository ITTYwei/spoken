# HTTP 接口迁移说明（2026-10-16）

早期 Nginx 通用 Agent 聊天示例已停用。它不经过口语陪练 Case，不构成用户会话和聊天记录的正式入口。旧 Controller、IAgentService、专用请求响应 DTO 和 Response 外壳均已删除。

| 已删除接口 | 当前入口 |
| --- | --- |
| GET /api/v1/query_ai_agent_config_list | 伙伴选择使用 GET /api/v1/home 与 /api/v1/catalog/* |
| GET、POST /api/v1/create_session | POST /api/v1/sessions |
| POST /api/v1/chat、/api/v1/chat_stream | 使用会话的 /voice 或 /speak 输入，/events 接收结果；两种 SSE 事件协议不能直接互换 |
| GET /api/v1/sessions/{sessionId}/turns | GET /api/v1/chat-history/sessions/{sessionId}/turns，返回分页对象 |
| POST /api/v1/sessions/{sessionId}/pronunciation | 跟读随 /voice 主链路评测，结果在 assessment 中返回 |
| GET /api/v1/sessions/{sessionId}/pronunciation | 独立评测记录查询入口删除；聊天记录使用 chat-history |
| POST /api/v1/sessions/{sessionId}/say | 内部媒体脚本播放能力保留；客户端使用 /speak 或 /voice |
| GET /api/v1/pronunciation/languages、/health；POST /speech2text、/phonemes、/tts | 独立调试与工具 HTTP 入口删除 |
| POST /api/v1/training/translate | Android 已删除该失效声明；使用所属回合 /turns/{turnId}/translation 或 /opening/translation |

所有私有请求使用当前账号 Bearer token，不能传入 userId 选择查询主体。创建会话需要 deviceId，并由服务端保存的伙伴选择和用例协调媒体资源。

历史会话列表使用 GET /api/v1/chat-history/sessions?limit=20。响应包含 items、nextCursor；续页同时传 beforeStartedAt 和 beforeSessionId。档案详情使用 /chat-history/sessions/{sessionId}，聊天记录使用其 /turns?afterTurnId=0&limit=20，续页使用服务端返回的 nextCursor。已关闭会话不需要恢复媒体资源即可查询。

Android 设置页的“聊天记录”已接入上述接口。部署需同步升级服务端与 Android；旧版本客户端不能继续调用被删除的无分页入口。数据库迁移和完整边界说明见 [领域重构说明](../wiki/ddd-refactoring-proposal.md)。
