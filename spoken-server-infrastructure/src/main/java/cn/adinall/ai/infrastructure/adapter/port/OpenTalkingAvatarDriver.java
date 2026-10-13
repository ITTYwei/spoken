package cn.adinall.ai.infrastructure.adapter.port;

import com.fasterxml.jackson.databind.JsonNode;
import cn.adinall.ai.domain.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.conversation.model.entity.CreateSessionCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.SpeakCommandEntity;
import cn.adinall.ai.domain.conversation.model.valobj.AvatarProfileVO;
import cn.adinall.ai.domain.conversation.model.valobj.ConversationEventVO;
import cn.adinall.ai.domain.conversation.model.valobj.DriverCapabilityVO;
import cn.adinall.ai.domain.conversation.model.valobj.DriverModelVO;
import cn.adinall.ai.domain.conversation.model.valobj.IceServerVO;
import cn.adinall.ai.domain.conversation.model.valobj.SessionHandleVO;
import cn.adinall.ai.domain.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.infrastructure.config.OpenTalkingProperties;
import cn.adinall.ai.infrastructure.gateway.OpenTalkingEventTranslator;
import cn.adinall.ai.infrastructure.gateway.OpenTalkingGateway;
import cn.adinall.ai.infrastructure.gateway.OpenTalkingJson;
import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * IAvatarDriverPort 的 OpenTalking 适配器（防腐层：上游长什么样 与 领域要什么 之间的那一层）
 * <p>
 * 功能：把领域命令翻译成 OpenTalking 的 HTTP/SSE 调用，再把上游响应规整回领域类型。
 * <pre>
 *   id()                                实现标识 "opentalking"，取值对应 spoken-ai.provider.avatar
 *   capabilities()                      声明 WebRTC 视频 / 上行音频 / 打断 / 文本驱动 / 脚本轨直念 / 可用模型
 *   listAvatars()                       列形象：GET /avatars；上游报错或结果为空时退到 fallbackAvatarIds
 *   listModels()                        列模型及连接状态：GET /models，只暴露 exposeModels 里的模型
 *   createSession(command)              POST /sessions 建会话，返回 SessionHandleVO
 *   status(providerSessionId)           GET /sessions/{id} 查状态；上游 404 当成 CLOSED
 *   iceServers()                        GET /sessions/webrtc/ice-config；dropStun 打开时摘掉 stun
 *   exchangeOffer(id, offerSdp)         POST /sessions/{id}/webrtc/offer；带重试换回 answer SDP
 *   speak(id, command)                  POST /speak：文本是「用户说的话」，上游走 LLM 生成回复
 *   say(id, command)                    POST /say：脚本轨直念，文本直通 TTS，不经过 LLM
 *   interrupt(id)                       POST /interrupt：上游只回 200 不发事件；
 *                                        领域服务 ConversationServiceImpl.interrupt() 随后补发本地 INTERRUPTED
 *   close(id)                           DELETE /sessions/{id}：释放 GPU 槽位，已关闭视为成功
 *   transcribeAudio(id, pcm, sampleRate) 语音上行识别；识别结果为空时抛 ot_voice_empty
 *   events(id)                          订阅 SSE 并逐条过 OpenTalkingEventTranslator
 * </pre>
 * 体系架构（本类吸收的三类差异）：
 * <pre>
 *   ConversationServiceImpl / CatalogCaseImpl（领域与用例层，只认 IAvatarDriverPort）
 *        |
 *        v
 *   OpenTalkingAvatarDriver（本类）
 *        |  (a) 字段名漂移: session_id / sessionId / id, sdp / answer / answer_sdp
 *        |      urls 是数组还是字符串, status / state
 *        |  (b) 状态文案: queued / worker_ready / ... -> SessionStatusEnum
 *        |  (c) 错误码: ot_http_404 区分「worker 未就绪」与「会话已被关闭」
 *        |
 *        +--> OpenTalkingGateway             HTTP/SSE 调用（唯一讲 OpenTalking 方言的类）
 *        +--> OpenTalkingJson                宽松取值，读不到返回 null / 默认值
 *        +--> OpenTalkingEventTranslator     SSE 帧 -> ConversationEventVO
 *        +--> OpenTalkingProperties          默认形象/模型、超时、dropStun、exposeModels
 *        |
 *        v
 *   OpenTalking 数字人后端（单 GPU 槽位调度）
 * </pre>
 * 运行过程（建会话 -> 换 SDP，含排队重试）：
 * <pre>
 *   ConversationServiceImpl.createSession(command)
 *        |
 *        | (1) avatarId 为空 -> props.getDefaultAvatarId()（anchor）；model 为空 -> props.getDefaultModel()
 *        |
 *        | (2) gateway.createSession(avatarId, model, command.voice(), command.llmSystemPrompt())
 *        |         POST /sessions {avatar_id, model, tts_voice, llm_system_prompt}
 *        |        |
 *        |        +--> 响应里取不到 session_id / sessionId / id --> 抛 AppException("ot_bad_response")
 *        |        v
 *        |     mapCreateStatus(status)：queued/pending/created 或缺省 -> QUEUED
 *        |                                 ready/ok/success -> READY
 *        v
 *   SessionHandleVO(sessionId, QUEUED)
 *        |
 *        v
 *   exchangeOffer(providerSessionId, offerSdp)
 *        |
 *        | offerWithRetry(): POST /sessions/{id}/webrtc/offer
 *        |        +--> ot_http_404 / ot_http_409 且 sessionGone() 为 false
 *        |        |        -> Thread.sleep(offerRetryIntervalMs) 后重试
 *        |        +--> sessionGone() 为 true（CLOSING/CLOSED/ERROR）
 *        |        |        -> 抛 AppException(SESSION_NOT_FOUND, "会话已被上游关闭...")
 *        |        +--> 已到 deadline（readyTimeoutMs）
 *        |                 -> 原样抛 ot_http_404
 *        v
 *   从 sdp / answer / answer_sdp 取 answer；取不到抛 ot_bad_answer
 * </pre>
 * 案例：
 * <pre>
 *   输入: command = CreateSessionCommandEntity{avatarId=null, model=null,
 *                voice="en-US-AriaNeural", llmSystemPrompt="You are Amy..."}
 *   处理: 形象兜底为 "anchor"、模型兜底为 "flashhead" -> POST /sessions
 *         -> 上游返回 {"session_id":"ot-7c1f2a","status":"queued"}
 *   输出: SessionHandleVO{providerSessionId="ot-7c1f2a", status=QUEUED}
 *         副作用: 上游起了一个数字人会话并占用 GPU 槽位；本类打一条 session created 日志
 *   后续: status("ot-7c1f2a") 读 {"state":"worker_ready"} -> READY
 *         exchangeOffer("ot-7c1f2a", sdp) 若 worker 还没挂上槽位，按每 1000ms 重试、最多 180000ms
 *         events("ot-7c1f2a") 收到 {"event":"speech.ended","data":{"text":"..."}}
 *             -> ConversationEventVO{type=ASSISTANT_DONE, data="..."}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>上游字段名的漂移只在本类吸收，领域服务拿到的永远是干净的领域类型；
 *       出问题先看这里，不要改领域模型去迁就上游。</li>
 *   <li>404 有两种完全不同的含义必须分开：worker 还没挂上槽位（值得等）与会话已被上游关掉
 *       （再等也不会好）。不区分的话第二种会把整个 readyTimeoutMs（默认 180s）烧完，
 *       用户看到「转三分钟然后报错」。</li>
 *   <li>events() 必须用 handle 而不是 map：Reactor 的 map 不允许返回 null，
 *       遇到不认识的事件会抛 NPE 把整条 SSE 打断，App 侧表现为「事件流中断」。</li>
 *   <li>events() 里故意不 startWith(SESSION_READY)：用例层已经按真实状态在流头发过一条 ready/queued，
 *       两边都发会让 App 收到重复事件。</li>
 *   <li>close() 只吞上游的 AppException：单 GPU 槽调度下「已经关掉了」是常见且正常的情况，
 *       不该因此让 App 的关会话请求失败；其它异常照常向上抛，不会被静默。</li>
 *   <li>扩展点：换数字人后端 = 新写一个 IAvatarDriverPort 实现 + 改 spoken-ai.provider.avatar，
 *       本类与领域层都不用动。</li>
 * </ul>
 */
public class OpenTalkingAvatarDriver implements IAvatarDriverPort {
    @Override
    public reactor.core.publisher.Mono<Void> streamReply(String providerSessionId, String replyId,
            String voice, Flux<String> text) {
        return gateway.streamReply(providerSessionId, replyId, voice, text);
    }

    private static final Logger log = LoggerFactory.getLogger(OpenTalkingAvatarDriver.class);

    private final OpenTalkingGateway gateway;
    private final OpenTalkingProperties props;

    public OpenTalkingAvatarDriver(OpenTalkingGateway gateway, OpenTalkingProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    /** 返回实现标识 "opentalking"，对应配置项 spoken-ai.provider.avatar 的取值。 */
    @Override
    public String id() {
        return "opentalking";
    }

    /**
     * 声明这个后端的能力：有 WebRTC 视频、支持上行音频识别、支持打断、支持文本驱动、
     * 支持脚本轨直念（上游 /say 已实现），可用的模型来自配置 exposeModels。
     */
    @Override
    public DriverCapabilityVO capabilities() {
        return new DriverCapabilityVO("opentalking", true, true, true, true, true, props.getExposeModels());
    }

    /**
     * 列上游可用的数字人形象。
     * 流程：调上游 /avatars -> 结果可能是数组也可能包在 items/avatars/data/list 里，逐个兼容取出
     * -> 字段名也可能是 id/avatar_id/avatarId 等多种写法，用宽松解析依次尝试 ->
     * 上游报错或结果为空时，退回到配置里的 fallbackAvatarIds（默认 anchor），保证列表不空。
     */
    @Override
    public List<AvatarProfileVO> listAvatars() {
        List<AvatarProfileVO> avatars = new ArrayList<>();
        try {
            JsonNode root = gateway.avatars();
            JsonNode items = root;
            if (root.isObject()) {
                for (String key : List.of("items", "avatars", "data", "list")) {
                    if (root.path(key).isArray()) {
                        items = root.path(key);
                        break;
                    }
                }
            }
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String id = OpenTalkingJson.text(item, "id", "avatar_id", "avatarId");
                    if (id == null) {
                        continue;
                    }
                    String name = OpenTalkingJson.text(item, "name", "title", "display_name", "displayName");
                    String cover = OpenTalkingJson.text(item, "cover_url", "coverUrl", "cover", "preview_url", "thumbnail");
                    String video = OpenTalkingJson.text(item, "preview_video_url", "previewVideoUrl", "video_url");
                    avatars.add(new AvatarProfileVO(id, name == null ? id : name, cover, video, true));
                }
            }
        } catch (Exception e) {
            log.warn("拉取 OpenTalking 形象列表失败，使用兜底列表: {}", e.toString());
        }
        if (avatars.isEmpty()) {
            for (String id : props.getFallbackAvatarIds()) {
                avatars.add(new AvatarProfileVO(id, id, null, null, true));
            }
        }
        return avatars;
    }

    /**
     * 列可用驱动模型及其连接状态。
     * 流程：调上游 /models（连不上就直接回一个 connected=false、reason=provider_unreachable 的占位项）->
     * 优先解析 statuses 数组（每个模型带 connected / reason），退化时解析简单的 models 字符串数组 ->
     * 过滤掉配置 exposeModels 之外的模型 -> 用 markDefault 标出默认项 -> 返回。
     * App 看到 connected=false 就显示「当前驱动模型未连接」，reason 供排查。
     */
    @Override
    public List<DriverModelVO> listModels() {
        List<DriverModelVO> out = new ArrayList<>();
        JsonNode root;
        try {
            root = gateway.models();
        } catch (Exception e) {
            log.warn("拉取 OpenTalking 模型列表失败: {}", e.toString());
            return List.of(new DriverModelVO(props.getDefaultModel(), props.getDefaultModel(), false,
                    "provider_unreachable", true, null));
        }
        String defaultModel = OpenTalkingJson.text(root, "default_model", "defaultModel");
        JsonNode statuses = root.path("statuses");
        if (statuses.isArray()) {
            for (JsonNode s : statuses) {
                String id = OpenTalkingJson.text(s, "id", "model");
                if (id == null || !exposed(id)) {
                    continue;
                }
                boolean connected = OpenTalkingJson.bool(s, "connected", false);
                String reason = OpenTalkingJson.text(s, "reason");
                out.add(new DriverModelVO(id, label(id), connected, reason, false, estimateChunkMs(id)));
            }
        } else {
            JsonNode models = root.path("models");
            if (models.isArray()) {
                for (JsonNode m : models) {
                    String id = m.asText();
                    if (!exposed(id)) {
                        continue;
                    }
                    out.add(new DriverModelVO(id, label(id), true, null, false, estimateChunkMs(id)));
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new DriverModelVO(props.getDefaultModel(), label(props.getDefaultModel()), false,
                    "no_model_exposed", true, estimateChunkMs(props.getDefaultModel())));
            return out;
        }
        return markDefault(out, defaultModel != null ? defaultModel : props.getDefaultModel());
    }

    /**
     * 谁是「默认模型」不能只看配置：配置里的模型可能压根没起
     * （例如只启动了 FlashTalk 14B、FlashHead 没起，或反过来）。
     * 这种情况下如果还把没起的那个标成默认，App 会自动选中一个未连接的模型，
     * 用户看到的就是「当前驱动模型未连接，请先启动对应模型服务」。
     *
     * 规则：优先用后端自报的默认值（且必须已连接），否则退到第一个已连接的模型。
     */
    private static List<DriverModelVO> markDefault(List<DriverModelVO> models, String preferred) {
        String picked = models.stream()
                .filter(m -> m.connected() && m.id().equals(preferred))
                .map(DriverModelVO::id)
                .findFirst()
                .orElseGet(() -> models.stream()
                        .filter(DriverModelVO::connected)
                        .map(DriverModelVO::id)
                        .findFirst()
                        .orElseGet(() -> models.get(0).id()));
        return models.stream()
                .map(m -> new DriverModelVO(m.id(), m.label(), m.connected(), m.reason(),
                        m.id().equals(picked), m.estimatedChunkMs()))
                .toList();
    }

    /**
     * 判断某个模型是否允许暴露给 App：exposeModels 为空表示全部暴露，否则必须在列表里。
     */
    private boolean exposed(String modelId) {
        List<String> expose = props.getExposeModels();
        return expose == null || expose.isEmpty() || expose.contains(modelId);
    }

    /**
     * 给模型 id 配一个给人看的名字（flashhead -> FlashHead 1.3B（实时），
     * flashtalk -> Fast-FlashTalk 14B（INT8）），认不出来就原样返回 id。
     */
    private static String label(String modelId) {
        return switch (modelId) {
            case "flashhead" -> "FlashHead 1.3B（实时）";
            case "flashtalk" -> "Fast-FlashTalk 14B（INT8）";
            default -> modelId;
        };
    }

    /** 给 UI 的粗略提示：Fast-FlashTalk 为 192×320、2 步配置预热后的 28 帧耗时，不包含首次编译和 TTS。 */
    private static Long estimateChunkMs(String modelId) {
        return switch (modelId) {
            case "flashhead" -> 300L;
            case "flashtalk" -> 900L;
            default -> null;
        };
    }

    /**
     * 在上游创建一场数字人会话。
     * 流程：形象/模型为空时用配置里的默认值兜底 -> POST /sessions -> 从响应里取 session_id（兼容 sessionId/id 两种字段名），
     * 取不到就抛 ot_bad_response -> 把上游状态映射成我方 SessionStatusEnum（见 mapCreateStatus）-> 返回句柄。
     */
    @Override
    public SessionHandleVO createSession(CreateSessionCommandEntity command) {
        String avatarId = command.avatarId() == null || command.avatarId().isBlank()
                ? props.getDefaultAvatarId() : command.avatarId();
        String model = command.model() == null || command.model().isBlank()
                ? props.getDefaultModel() : command.model();
        // TTS 引擎同样走配置兜底：音色是按引擎命名空间命名的，两者必须配套下发（见 OpenTalkingProperties）。
        String ttsProvider = props.getDefaultTtsProvider();
        JsonNode root = gateway.createSession(avatarId, model, ttsProvider, command.voice(),
                command.llmSystemPrompt());
        String sessionId = OpenTalkingJson.text(root, "session_id", "sessionId", "id");
        if (sessionId == null) {
            throw new AppException("ot_bad_response", "OpenTalking 未返回 session_id");
        }
        SessionStatusEnum status = mapCreateStatus(OpenTalkingJson.text(root, "status"));
        log.info("OpenTalking session created: {} avatar={} model={} status={} tts={}/{} promptChars={}",
                sessionId, avatarId, model, status,
                ttsProvider == null || ttsProvider.isBlank() ? "(上游默认)" : ttsProvider,
                command.voice() == null ? "(默认)" : command.voice(),
                command.llmSystemPrompt() == null ? 0 : command.llmSystemPrompt().length());
        return new SessionHandleVO(sessionId, status);
    }

    /**
     * 查上游会话状态。
     * 流程：GET /sessions/{id} -> 从 state/status 字段映射成我方枚举；上游返回 404 时按「会话已不存在」处理成 CLOSED，
     * 其余异常照抛。
     */
    @Override
    public SessionStatusEnum status(String providerSessionId) {
        try {
            JsonNode root = gateway.sessionState(providerSessionId);
            return mapState(OpenTalkingJson.text(root, "state", "status"));
        } catch (AppException e) {
            if ("ot_http_404".equals(e.code())) {
                return SessionStatusEnum.CLOSED;
            }
            throw e;
        }
    }

    /**
     * 取 WebRTC 的 ICE 配置。
     * 流程：GET 上游 /sessions/webrtc/ice-config -> urls 可能是数组也可能是字符串，两种都兼容（数组会拼成逗号分隔）
     * -> 逐个转成 IceServerVO（urls/username/credential）。
     */
    @Override
    public List<IceServerVO> iceServers() {
        JsonNode root = gateway.iceConfig();
        JsonNode arr = root.path("iceServers");
        if (!arr.isArray()) {
            arr = root.path("ice_servers");
        }
        List<IceServerVO> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode s : arr) {
                JsonNode urls = s.path("urls");
                String urlText;
                if (urls.isArray()) {
                    List<String> parts = new ArrayList<>();
                    urls.forEach(u -> parts.add(u.asText()));
                    urlText = String.join(",", parts);
                } else {
                    urlText = OpenTalkingJson.text(s, "urls", "url");
                }
                if (urlText != null) {
                    out.add(new IceServerVO(urlText,
                            OpenTalkingJson.text(s, "username"),
                            OpenTalkingJson.text(s, "credential")));
                }
            }
        }
        return props.isDropStun() ? withoutStun(out) : out;
    }

    /** STUN 只给局域网部署带不来可用的候选对，却会让非 trickle 的 App 多等一轮收集。 */
    private static boolean isStunUrl(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("stun:") || u.startsWith("stuns:");
    }

    /** 摘掉 stun / stuns；同一条 entry 里既有 STUN 又有 TURN 时只删 STUN 那部分。 */
    private static List<IceServerVO> withoutStun(List<IceServerVO> servers) {
        List<IceServerVO> out = new ArrayList<>(servers.size());
        for (IceServerVO s : servers) {
            String kept = Arrays.stream((s.urls() == null ? "" : s.urls()).split(","))
                    .map(String::trim)
                    .filter(u -> !u.isEmpty() && !isStunUrl(u))
                    .collect(Collectors.joining(","));
            if (!kept.isEmpty()) {
                out.add(new IceServerVO(kept, s.username(), s.credential()));
            }
        }
        if (out.size() != servers.size()) {
            log.info("按 drop-stun 配置摘掉 STUN：{} 条 -> {} 条 ICE 服务器", servers.size(), out.size());
        }
        return out;
    }

    /**
     * WebRTC SDP 交换：把 App 的 offer 交给上游，换回 answer。
     * 流程：offerWithRetry 带着重试去 POST /sessions/{id}/webrtc/offer -> 从响应里取 sdp（兼容 answer/answer_sdp 字段名），
     * 取不到就抛 ot_bad_answer。
     */
    @Override
    public String exchangeOffer(String providerSessionId, String offerSdp) {
        JsonNode root = offerWithRetry(providerSessionId, offerSdp);
        String answer = OpenTalkingJson.text(root, "sdp", "answer", "answer_sdp");
        if (answer == null) {
            throw new AppException("ot_bad_answer", "OpenTalking 未返回 answer SDP");
        }
        return answer;
    }

    /**
     * 会话刚建好时，worker 可能还没挂到 GPU 槽位上，offer 会返回
     * 404 "session not loaded (worker not ready yet?)"。
     *
     * FlashTalk 是 14B 模型，冷启动要几十秒到两分钟，这段时间里用户点「开始」就是会撞上它。
     * 别把 404 直接甩给 App —— App 那边看到的就是一句看不懂的报错。
     *
     * 但 404 有两种完全不同的含义，必须分开：
     *  - worker 还没挂上槽位 -> 等一会真会好，值得重试；
     *  - 会话已经被上游关掉 -> WebRTC 断开后上游会自动关会话并释放槽位，等到超时也不会好。
     * 不区分的话，第二种会把整个 readyTimeoutMs（默认 180s）烧完，用户看到的是
     * 「转三分钟然后报错」，上游日志里则是一串没完没了的 404。
     */
    private JsonNode offerWithRetry(String providerSessionId, String offerSdp) {
        long deadline = System.currentTimeMillis() + props.getReadyTimeoutMs();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return gateway.offer(providerSessionId, offerSdp);
            } catch (AppException e) {
                boolean retryable = "ot_http_404".equals(e.code()) || "ot_http_409".equals(e.code());
                if (!retryable) {
                    throw e;
                }
                // 上游说这个会话已经没了就别等了 —— 它的 providerSessionId 已经作废。
                if (sessionGone(providerSessionId)) {
                    throw new AppException(ResponseCodeEnum.SESSION_NOT_FOUND,
                            "会话已被上游关闭（WebRTC 断开后释放了 GPU 槽位），请重新开始一次对话", e);
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw e;
                }
                if (attempt == 1) {
                    log.info("OpenTalking 会话 {} 还没挂上槽位，开始等待（最多 {}ms）",
                            providerSessionId, props.getReadyTimeoutMs());
                }
                try {
                    Thread.sleep(props.getOfferRetryIntervalMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AppException("ot_offer_interrupted", "等待 worker 就绪时被中断", ie);
                }
            }
        }
    }

    /**
     * 上游这个会话是不是已经不存在或已收尾。
     *
     * 只在 offer 撞到 404/409 时调用，用来把「在排队」和「已关闭」分开：
     * 排队要等，已关闭要立刻告诉调用方重新建会话。
     *
     * 查状态本身失败（网络抖动等）时按「还在」处理 —— 宁可多等一轮，
     * 也不要因为一次查不到就把好会话判死、让用户白重建一次。
     */
    private boolean sessionGone(String providerSessionId) {
        try {
            SessionStatusEnum status = status(providerSessionId);
            return status == SessionStatusEnum.CLOSING
                    || status == SessionStatusEnum.CLOSED
                    || status == SessionStatusEnum.ERROR;
        } catch (RuntimeException e) {
            log.warn("确认上游会话状态失败，按「还在排队」继续重试: {}", e.toString());
            return false;
        }
    }

    /** 让上游用文本驱动数字人说话：POST /sessions/{id}/speak。只负责下发，不等结果。 */
    @Override
    public void speak(String providerSessionId, SpeakCommandEntity command) {
        gateway.speak(providerSessionId, command.text(), command.voice(), props.getDefaultTtsProvider());
    }

    /**
     * 脚本轨：让数字人原样念出这段文本，不经过 LLM。
     * 上游 /say 直通 TTS —— 实测首块音频 ~1.0s；同样的文本走 /speak 要约 6.9s（还得等 LLM）。
     *
     * TTS 引擎每次都随请求下发：上游 speak/say 是请求级解析引擎的，不会继承建会话时传的那个。
     */
    @Override
    public void say(String providerSessionId, SayCommandEntity command) {
        gateway.say(providerSessionId, command.text(), command.voice(),
                props.getDefaultTtsProvider(), command.commandId());
    }

    /**
     * 让上游中断当前这句：POST /sessions/{id}/interrupt。
     * 注意上游只回 HTTP 200、不发任何事件，「已打断」这条事件由领域服务自己补。
     */
    @Override
    public void interrupt(String providerSessionId) {
        gateway.interrupt(providerSessionId);
    }

    /**
     * 把一整段 PCM 推给上游做语音识别，返回识别文本。
     * 流程：gateway.transcribeAudio(...) 走 HTTP multipart POST /sessions/{id}/speak_audio
     * （pcm 为 null 时只发 source=uplink 字段，让上游用它自己 WebRTC 上行缓冲里的音频；
     * pcm 非空时包成 WAV 以 multipart 文件上传）-> 结果 trim ->
     * 空文本时抛 ot_voice_empty（翻译成用户能看懂的一句话：没有识别出有效语音，请再说一遍）。
     */
    @Override
    public String transcribeAudio(String providerSessionId, byte[] pcm, int sampleRate) {
        String text = gateway.transcribeAudio(providerSessionId, pcm, sampleRate);
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new AppException("ot_voice_empty", "没有识别出有效语音，请再说一遍");
        }
        return trimmed;
    }

    /**
     * 上传一段 WAV 做识别，**不触发数字人播报**。
     *
     * 流程：gateway.transcribeUploaded(...) 走 HTTP multipart POST /sessions/{id}/transcribe
     * （上游那条路由是「只识别不说话」的）-> 结果 trim ->
     * 空文本时抛 ot_voice_empty（翻译成用户能看懂的一句话：没有识别出有效语音，请再说一遍）。
     *
     * 与 {@link #transcribeAudio} 的分工见 IAvatarDriverPort 的同名方法：
     * 那条是「上游自己 STT+LLM+TTS 一条龙」，本条只换文本、说话权在我方。
     */
    @Override
    public String transcribeUploaded(String providerSessionId, byte[] wav) {
        String text = gateway.transcribeUploaded(providerSessionId, wav);
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new AppException(ResponseCodeEnum.NO_SPEECH, "没有识别出有效语音，请再说一遍");
        }
        return trimmed;
    }

    /**
     * 关闭上游会话并释放 GPU 槽位。
     * 流程：DELETE /sessions/{id}；只捕获 AppException 并降级成 debug 日志 ——
     * 单槽调度下「已经关掉了」是常见且正常的情况，不该因此让 App 的关会话请求失败；
     * 其它异常照常向上抛。
     */
    @Override
    public void close(String providerSessionId) {
        try {
            gateway.close(providerSessionId);
        } catch (AppException e) {
            // 已经关掉的会话是正常情况（单槽调度下尤其常见），不要向上抛
            log.debug("close session {} 返回 {}: {}", providerSessionId, e.code(), e.getMessage());
        }
    }

    /**
     * 订阅上游的会话事件流，并翻译成我方领域事件。
     * 流程：交给网关读 SSE -> 每条原始帧过 OpenTalkingEventTranslator ->
     * 用 handle 而不是 map 过滤不认识的事件（map 不允许返回 null，遇到新事件会抛 NPE 把整条流打断，App 侧就表现为事件流中断）。
     * 这里故意不再补 session.ready —— 用例层已经按真实状态在流头发过一条，两边都发会让 App 收到重复事件。
     */
    @Override
    public Flux<ConversationEventVO> events(String providerSessionId) {
        // 不要在这里 startWith(SESSION_READY)：用例层已经按真实状态
        // 在流头补了一条 ready/queued，两边都发会让 App 收到重复事件。
        return gateway.eventStream(providerSessionId)
                // 必须用 handle 而不是 map + filter：Reactor 的 map 不允许返回 null，
                // 遇到不认识的事件会直接抛 NPE 把整条 SSE 流打断（App 侧表现是「事件流中断」）。
                .handle((payload, sink) -> {
                    ConversationEventVO event = OpenTalkingEventTranslator.translate(payload);
                    if (event != null) {
                        sink.next(event);
                    }
                });
    }

    /**
     * 把上游「建会话」返回的状态文案映射成我方枚举：
     * queued/pending/created 以及缺省都算 QUEUED，ready/ok/success 算 READY，其余一律保守地当 QUEUED。
     */
    private static SessionStatusEnum mapCreateStatus(String raw) {
        if (raw == null) {
            return SessionStatusEnum.QUEUED;
        }
        return switch (raw.toLowerCase()) {
            case "queued", "pending", "created" -> SessionStatusEnum.QUEUED;
            case "ready", "ok", "success" -> SessionStatusEnum.READY;
            default -> SessionStatusEnum.QUEUED;
        };
    }

    /**
     * 把上游会话状态文案映射成我方枚举：
     * created/queued/pending -> QUEUED；worker_ready/ready -> READY；active/running/speaking/listening -> ACTIVE；
     * closing -> CLOSING；closed/expired -> CLOSED；error/failed -> ERROR。
     * 缺省或认不出来时返回 READY（宁可认为可用，让后续 offer 去真实试探）。
     */
    private static SessionStatusEnum mapState(String raw) {
        if (raw == null) {
            return SessionStatusEnum.READY;
        }
        return switch (raw.toLowerCase()) {
            case "created", "queued", "pending" -> SessionStatusEnum.QUEUED;
            case "worker_ready", "ready" -> SessionStatusEnum.READY;
            case "active", "running", "speaking", "listening" -> SessionStatusEnum.ACTIVE;
            case "closing" -> SessionStatusEnum.CLOSING;
            case "closed", "expired" -> SessionStatusEnum.CLOSED;
            case "error", "failed" -> SessionStatusEnum.ERROR;
            default -> SessionStatusEnum.READY;
        };
    }
}
