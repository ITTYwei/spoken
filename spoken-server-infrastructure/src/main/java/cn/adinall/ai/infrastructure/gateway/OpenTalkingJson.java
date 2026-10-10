package cn.adinall.ai.infrastructure.gateway;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * 宽松 JSON 解析工具（gateway 包内共用的静态工具：读不到就当空，绝不抛异常）
 * <p>
 * 功能：提供三个宽容取值方法，让上游字段漂移或返回非 JSON 时不把整条链路炸掉。
 * <pre>
 *   MAPPER                        全局共用的宽松 ObjectMapper（FAIL_ON_UNKNOWN_PROPERTIES=false）
 *   read(raw)                     原始文本 -> JsonNode；null / 空白 / 解析失败都返回 MissingNode
 *   text(node, names...)          按多个候选字段名依次取第一个非空文本，全没有返回 null
 *   bool(node, name, default)     取布尔值，缺字段或为 null 时返回 default
 * </pre>
 * 体系架构（三处共用同一份宽容语义）：
 * <pre>
 *   OpenTalkingGateway
 *        |  send()     : OpenTalkingJson.read(response.body())
 *        |  writeJson(): OpenTalkingJson.MAPPER.writeValueAsString(body)
 *        |  merge()    : read(payload) 判断 SSE 的 data 是不是合法 JSON
 *        v
 *   OpenTalkingJson（本类，全静态、不进 Spring 容器）
 *        ^
 *        |  OpenTalkingEventTranslator : read / text / bool 逐字段取
 *        |  OpenTalkingAvatarDriver    : text(item, "id", "avatar_id", "avatarId") 逐候选名取
 *        |
 *   MAPPER --> Jackson JsonNode（只当松散树用，不做 POJO 绑定）
 * </pre>
 * 运行过程（一次字段名漂移的上游响应）：
 * <pre>
 *   上游返回 {"avatar_id":"anchor","displayName":"Amy"}
 *        |
 *        v
 *   OpenTalkingAvatarDriver.listAvatars()
 *        |
 *        +--> OpenTalkingJson.text(item, "id", "avatar_id", "avatarId")
 *        |        "id" 没有 -> "avatar_id" 命中 -> "anchor"
 *        +--> OpenTalkingJson.text(item, "name", "title", "display_name", "displayName")
 *        |        前三个没有 -> "displayName" 命中 -> "Amy"
 *        v
 *   AvatarProfileVO(id="anchor", name="Amy", coverUrl=null, previewVideoUrl=null, online=true)
 * </pre>
 * 案例：
 * <pre>
 *   输入: raw = {"statuses":[{"id":"flashhead","connected":true}]}
 *   处理: root = OpenTalkingJson.read(raw)
 *         OpenTalkingJson.text(root, "default_model", "defaultModel")        -> null
 *         OpenTalkingJson.bool(root, "connected", false)                     -> false
 *         OpenTalkingJson.bool(root.path("statuses").get(0), "connected", false) -> true
 *   输出: null / false / true
 *   边界: read("not-json") 返回 MissingNode.getInstance()；
 *         text(MissingNode, "id") 返回 null —— 两者都不抛异常
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>放在 gateway 包里被网关、翻译器、适配器共用：三处都需要「读不到就当空」的宽容解析，
 *       各写一份迟早出现「这里宽容那里严格」的不一致。</li>
 *   <li>不用 Spring 注入的 ObjectMapper：网关要的是一份「失败也返回空而不是抛异常」的方言，
 *       而容器里那个 ObjectMapper 是给对外 JSON 用的，两边诉求不同，共用只会互相绑手绑脚。</li>
 *   <li>text 接收可变个数的候选字段名：上游同一含义的字段在不同版本里叫法不同
 *       （id / avatar_id / avatarId），把兼容逻辑收在一个调用点比散落的 if 更可控。</li>
 *   <li>MAPPER 是 public static final 且被共享：它无状态、线程安全，不必每个组件自己 new；
 *       但别在上面挂自定义序列化配置，会串到所有使用者。</li>
 *   <li>read 对非 JSON 返回 MissingNode 而不是抛异常：上游偶发返回 HTML 错误页时，
 *       让调用方走「读不到」的分支，比让整条链路炸掉好。</li>
 * </ul>
 */
public final class OpenTalkingJson {

    /** 全局共用的宽松 ObjectMapper：未知字段不报错，这是与上游对字段漂移共存的底线。 */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 工具类私有构造，防止被实例化（这个类只提供静态方法）。
     */
    private OpenTalkingJson() {
    }

    /**
     * 把一段原始 JSON 文本解析成 JsonNode。
     * 流程：null/空白直接返回 MissingNode -> 解析；解析失败也返回 MissingNode 而不是抛异常。
     * 调用方用 isMissingNode() 判断「没解析出来」，这样上游偶发返回非 JSON 时不会把整条链路炸掉。
     */
    public static JsonNode read(String raw) {
        if (raw == null || raw.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }

    /** 依次尝试多个字段名，返回第一个非空的文本值。 */
    public static String text(JsonNode node, String... names) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v != null && !v.isNull() && v.isValueNode()) {
                String s = v.asText();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * 按字段名读一个布尔值，缺失或为 null 时返回默认值。
     */
    public static boolean bool(JsonNode node, String name, boolean defaultValue) {
        if (node == null || node.isMissingNode()) {
            return defaultValue;
        }
        JsonNode v = node.get(name);
        return (v == null || v.isNull()) ? defaultValue : v.asBoolean(defaultValue);
    }
}