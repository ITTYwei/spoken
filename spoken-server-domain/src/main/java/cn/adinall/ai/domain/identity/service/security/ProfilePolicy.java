package cn.adinall.ai.domain.identity.service.security;

import java.util.Set;

/** 所有应用入口共同遵守的个人资料校验规则。 */
public final class ProfilePolicy {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private ProfilePolicy() { }
    /** 校验昵称并返回去除首尾空白后的有效内容。 */
    public static String nickname(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 64)
            throw new IllegalArgumentException("昵称需为1至64个字符");
        return value.trim();
    }
    /** 校验并规范化字幕翻译设置。 */
    public static String translation(String value) {
        if (!Set.of("on_demand", "off").contains(value == null ? "" : value))
            throw new IllegalArgumentException("翻译偏好无效");
        return value;
    }
    /** 校验个人事实标识、允许的事实类型和内容长度。 */
    public static void fact(String key, String type, String content) {
        boolean goal = "GOAL".equals(type) && "learning_goal".equals(key);
        boolean interest = "PREFERENCE".equals(type) && key != null && key.startsWith("preference:") && key.length() > 11 && key.length() <= 128;
        if (!goal && !interest) throw new IllegalArgumentException("仅允许管理练习目标和兴趣");
        if (content != null && (content.isBlank() || content.length() > 300))
            throw new IllegalArgumentException("偏好内容需为1至300个字符");
    }
}
