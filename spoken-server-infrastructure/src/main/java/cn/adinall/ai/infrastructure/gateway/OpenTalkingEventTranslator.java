package cn.adinall.ai.infrastructure.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import cn.adinall.ai.domain.business.conversation.model.valobj.ConversationEventVO;
import cn.adinall.ai.domain.business.conversation.model.valobj.EventTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 反腐蚀层：把 OpenTalking 的 SSE 事件翻译成我们自己的领域事件。
 *
 * 输入是 {@link OpenTalkingGateway#eventStream} 拼好的
 * {"event": "<名字>", "data": {...}}（事件名已按真实流量校准）。
 *
 * 仍然保留「精确表 + 关键词兜底」的保守策略：识别不了的只记日志、不往下传 ——
 * 猜错比漏掉更糟，App 会显示出一句语义完全不对的字幕。
 */
public final class OpenTalkingEventTranslator {

    private static final Logger log = LoggerFactory.getLogger(OpenTalkingEventTranslator.class);

    /**
     * 工具类私有构造，防止实例化（只提供静态方法）。
     */
    private OpenTalkingEventTranslator() {
    }

    /** 已知事件名 -> 我们的类型。 */
    private static EventTypeEnum exact(String name) {
        return switch (name) {
            case "session.queued", "session.queue" -> EventTypeEnum.SESSION_QUEUED;
            case "session.ready", "session.started", "session.created" -> EventTypeEnum.SESSION_READY;
            case "session.closed", "session.close", "session.expired" -> EventTypeEnum.SESSION_CLOSED;
            case "stt.partial", "transcript.partial", "asr.partial" -> EventTypeEnum.STT_PARTIAL;
            case "stt.final", "transcript.final", "asr.final", "transcript" -> EventTypeEnum.STT_FINAL;
            case "assistant.delta", "llm.delta", "response.delta" -> EventTypeEnum.ASSISTANT_DELTA;
            case "assistant.done", "llm.done", "response.done" -> EventTypeEnum.ASSISTANT_DONE;
            // 下面两条是 OpenTalking 实际在用的事件名
            case "tts.start", "audio.start", "speech.started" -> EventTypeEnum.TTS_START;
            // speech.ended 的 payload 带整段回复文本（{"text": "..."}），是这一轮字幕的权威定稿。
            // 丢掉它的两个后果都真机复现过：字幕只剩拼接出来的碎片（"Hello!How can..."），
            // 而且永远等不到 assistant.done，App 的「正在回复」一直不收尾。
            // 注意必须写在 fuzzy 之前，否则会被 speech+end 规则判成 null 直接丢弃。
            case "speech.ended", "speech.stopped" -> EventTypeEnum.ASSISTANT_DONE;
            case "speech.media_started" -> EventTypeEnum.TTS_START;
            case "subtitle.chunk" -> EventTypeEnum.ASSISTANT_DELTA; // is_final 时改判 done，见 resolve()
            case "video.first_frame", "frame.first" -> EventTypeEnum.VIDEO_FIRST_FRAME;
            case "session.interrupted", "interrupted" -> EventTypeEnum.INTERRUPTED;
            case "heartbeat", "ping" -> EventTypeEnum.HEARTBEAT;
            case "error", "session.error" -> EventTypeEnum.ERROR;
            default -> null;
        };
    }

    /**
     * 有些事件一个名字承载两种语义，只能看 payload 才分得清。
     * OpenTalking 的 subtitle.chunk 就是这样：is_final=false 是边说边出的一段，
     * is_final=true 才是这一句的定稿。
     */
    private static EventTypeEnum resolve(String name, JsonNode payload) {
        if ("subtitle.chunk".equals(name)) {
            return OpenTalkingJson.bool(payload, "is_final", false)
                    ? EventTypeEnum.ASSISTANT_DONE
                    : EventTypeEnum.ASSISTANT_DELTA;
        }
        EventTypeEnum exactType = exact(name);
        return exactType != null ? exactType : fuzzy(name);
    }

    /** 兜底：按关键词猜。宁可漏也不要错。 */
    private static EventTypeEnum fuzzy(String name) {
        String n = name.toLowerCase();
        // 顺序要紧："speech.started" 必须先被这里接住，
        // 否则会被下面的 started 规则误判成「会话就绪」。
        if (n.contains("speech") || n.contains("tts") || n.contains("audio")) {
            return (n.contains("end") || n.contains("stop")) ? null : EventTypeEnum.TTS_START;
        }
        if (n.contains("subtitle") || n.contains("caption")) {
            return EventTypeEnum.ASSISTANT_DELTA;
        }
        if (n.contains("queue") || n.contains("排队")) {
            return EventTypeEnum.SESSION_QUEUED;
        }
        if (n.contains("ready") || n.contains("started") || n.contains("worker")) {
            return EventTypeEnum.SESSION_READY;
        }
        if (n.contains("closed") || n.contains("expired") || n.contains("released")) {
            return EventTypeEnum.SESSION_CLOSED;
        }
        if (n.contains("partial")) {
            return n.contains("stt") || n.contains("asr") || n.contains("transcript")
                    ? EventTypeEnum.STT_PARTIAL : null;
        }
        if (n.contains("stt") || n.contains("asr") || n.contains("transcript") || n.contains("user_text")) {
            return EventTypeEnum.STT_FINAL;
        }
        if (n.contains("delta")) {
            return EventTypeEnum.ASSISTANT_DELTA;
        }
        if (n.contains("llm") || n.contains("assistant") || n.contains("response")) {
            return EventTypeEnum.ASSISTANT_DONE;
        }
        if (n.contains("first_frame") || n.contains("frame")) {
            return EventTypeEnum.VIDEO_FIRST_FRAME;
        }
        if (n.contains("interrupt")) {
            return EventTypeEnum.INTERRUPTED;
        }
        if (n.contains("error") || n.contains("fail")) {
            return EventTypeEnum.ERROR;
        }
        return null;
    }

    /** 返回 null 表示这条事件我们不需要推给 App。 */
    public static ConversationEventVO translate(String jsonPayload) {
        JsonNode root = OpenTalkingJson.read(jsonPayload);
        if (root.isMissingNode()) {
            log.debug("skip unparsable SSE payload: {}", abbreviate(jsonPayload));
            return null;
        }
        String name = OpenTalkingJson.text(root, "type", "event", "event_type", "name");
        if (name == null) {
            // 有些实现是 { "session.ready": {...} } 这种单键形式
            if (root.fieldNames().hasNext()) {
                name = root.fieldNames().next();
            } else {
                return null;
            }
        }
        // 事件名在外层，内容在 data 里；有些实现是平铺的，所以两者都兼容
        JsonNode payload = root.path("data").isObject() ? root.path("data") : root;
        EventTypeEnum type = resolve(name, payload);
        if (type == null) {
            log.debug("unmapped OpenTalking event: {}", name);
            return null;
        }
        return ConversationEventVO.of(type, extractData(type, payload));
    }

    /**
     * 按事件类型从原始 payload 里抽出要传给上层的载荷。
     * 流程：文本类事件（stt.final / assistant.delta / assistant.done 等）取 text|content|delta|transcript|message
     * 的一个字符串；TTS_START 包一个可能带 text 的 map；ERROR 归一成 {code, message}（字段缺失给默认值）；
     * 其余事件统一包成 {status, message} 的 map，取不到就返回空 map。
     */
    private static Object extractData(EventTypeEnum type, JsonNode root) {
        switch (type) {
            case STT_PARTIAL, STT_FINAL, ASSISTANT_DELTA, ASSISTANT_DONE -> {
                String text = OpenTalkingJson.text(root, "text", "content", "delta", "transcript", "message");
                return text == null ? "" : text;
            }
            case TTS_START -> {
                Map<String, Object> m = new HashMap<>();
                String text = OpenTalkingJson.text(root, "text", "content");
                if (text != null) {
                    m.put("text", text);
                }
                return m;
            }
            case ERROR -> {
                Map<String, Object> m = new HashMap<>();
                JsonNode err = root.path("error");
                String code = OpenTalkingJson.text(err, "code");
                String message = OpenTalkingJson.text(err, "message");
                if (code == null) {
                    code = OpenTalkingJson.text(root, "code");
                }
                if (message == null) {
                    message = OpenTalkingJson.text(root, "message", "detail");
                }
                m.put("code", code == null ? "unknown" : code);
                m.put("message", message == null ? "未知错误" : message);
                return m;
            }
            default -> {
                Map<String, Object> m = new HashMap<>();
                String status = OpenTalkingJson.text(root, "status", "state");
                if (status != null) {
                    m.put("status", status);
                }
                String message = OpenTalkingJson.text(root, "message");
                if (message != null) {
                    m.put("message", message);
                }
                return m;
            }
        }
    }

    /**
     * 日志用的截断工具：超过 200 字符就截断加省略号，避免一条超长 payload 把日志刷爆。
     */
    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}