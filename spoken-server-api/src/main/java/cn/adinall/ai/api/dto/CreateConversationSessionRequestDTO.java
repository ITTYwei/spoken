package cn.adinall.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 建会话请求（POST /api/v1/sessions 的入参，数字人对话链路的入口）
 * <p>
 * 功能：把「哪台设备、用哪个形象/模型/音色、按哪个剧本」一次交给服务端，
 * 未指定的一律由后端配置兜底。deviceId 由 App 生成（MVP 不接账号体系）。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   deviceId    必填（@NotBlank）；App 生成的设备标识，服务端按它做「一台设备只留一场会话」
 *   avatarId    形象 id，见 GET /api/v1/catalog/avatars；为空时用 default-avatar-id（默认 anchor）
 *   model       驱动模型 id；为空时用 default-model（默认 flashhead）
 *   voice       TTS 音色；为空时用场景配置的音色，再退到后端默认
 *   scenarioId  口语训练场景 id（见 GET /api/v1/catalog/scenarios）；
 *               决定「这场会话按什么剧本走」——包括开场白说什么。为空时用默认场景
 * </pre>
 * 运行过程：
 * <pre>
 *   POST /api/v1/sessions
 *        body = {"deviceId":"dev-001","avatarId":"anchor","model":"flashhead","scenarioId":"free"}
 *        |
 *        v
 *   SessionController.create(request)    &lt;-- @Valid 校验 deviceId 非空
 *        |
 *        v
 *   ConversationCaseImpl.create()
 *        |
 *        | (1) closeSessionsOfDevice(deviceId)：真关了旧会话才 sleepQuietly(slotReleaseSettleMs)
 *        | (2) 建新会话：形象/模型/音色为空时由驱动适配器用配置兜底
 *        | (3) awaitReady：仅当前是 QUEUED 才轮询（每 400ms），最多 readyWaitMs
 *        | (4) ConversationAssembler.toResponse(session)
 *        v
 *   SessionResponseDTO{sessionId="s-001", status="READY", avatarId="anchor", model="flashhead", ...}
 * </pre>
 * 案例：
 * <pre>
 *   输入: {"deviceId":"dev-001","scenarioId":"free"}
 *   处理: dev-001 已有旧会话 -> 先关掉并等 800ms -> 建新会话 -> 15 秒内等到 READY
 *   输出: {"sessionId":"s-001","status":"READY","voice":"en-US-AriaNeural",
 *          "startedAt":1767225600000}
 *          （body 没给 avatarId / model，聚合里就是 null，SessionResponseDTO 标了 NON_NULL，
 *           所以 JSON 里没有这两个键 —— 形象/模型的配置兜底只作用于上游调用那一步；
 *           voice 用的是场景 free 的 en-US-AriaNeural）
 *         上游排队未就绪: status="QUEUED"（不是错误，App 继续轮询或听 SSE）
 *         deviceId 缺失: 400 {"code":"invalid_request","message":"deviceId must not be blank","ts":...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>形象/模型/音色全部可空是刻意的：App 只要能给一个 deviceId 就能开会话，
 *       默认值集中在后端配置（改默认值不必发版）。</li>
 *   <li>账号归属来自Bearer认证，不从请求体读取userId；deviceId只用于账号内的设备索引。
 *       建新会话只关闭同账号、同设备的旧会话。</li>
 *   <li>第一阶段HTTP入口统一从自由对话进入；练习内容在聊天中确认或切换。
 *       scenarioId保留用于兼容内部用例调用，不作为注册或首页的学习问卷。</li>
 *   <li>返回 READY 还是 QUEUED 取决于上游 GPU 槽位：建会话最多阻塞 readyWaitMs（默认 15 秒），
 *       超时照样返回，由 App 继续等 —— 接口不该为了「等到就绪」无限占着连接。</li>
 * </ul>
 */
public record CreateConversationSessionRequestDTO(
        @NotBlank String deviceId,
        String avatarId,
        String model,
        String voice,
        /**
         * 口语训练场景 id（见 GET /catalog/scenarios）。
         * 决定「这场会话按什么剧本走」——包括开场白说什么。为空时用默认场景。
         */
        String scenarioId
) {
}
