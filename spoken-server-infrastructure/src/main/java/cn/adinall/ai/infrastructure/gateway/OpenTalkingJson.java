package cn.adinall.ai.infrastructure.gateway;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * 宽松 JSON 解析工具：对端字段偶尔变化时不要让整个请求炸掉。
 *
 * 放在 gateway 包里、被翻译器和适配器共用：三处都需要「读不到就当空」的宽容解析，
 * 各写一份迟早出现「这里宽容那里严格」的不一致。
 *
 * 为什么不用 Spring 注入的 ObjectMapper：网关要的是一份「失败也返回空而不是抛异常」的方言
 * （见下面每个方法都不抛异常），而容器里那个 ObjectMapper 是给对外 JSON 用的，
 * 两边诉求不同，共用只会互相绑手绑脚。
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