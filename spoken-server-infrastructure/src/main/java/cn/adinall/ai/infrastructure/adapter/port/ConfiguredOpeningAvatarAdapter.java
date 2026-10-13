package cn.adinall.ai.infrastructure.adapter.port;

import cn.adinall.ai.domain.conversation.adapter.port.IOpeningAvatarPort;
import cn.adinall.ai.infrastructure.config.OpeningAvatarProperties;
import cn.adinall.ai.domain.conversation.service.opening.OpeningTextPolicy;

/**
 * 功能：解析与当前选择绑定的名称，未指定形象时沿用驱动的默认形象。
 * <p>体系架构 / 运行过程：</p>
 * <pre>requested ID / driver default -> configured name -> validated display identity</pre>
 * <p>案例：选anchor且未配名 -> anchor/null；选avatarB -> 只查avatarB。</p>
 * <p>设计要点：</p>
 * <ul><li>省略未知名称。</li><li>禁止名称注入槽位、问句或压力词。</li></ul>
 */
public class ConfiguredOpeningAvatarAdapter implements IOpeningAvatarPort {
    /** 明确配置的名称映射。 */ private final OpeningAvatarProperties properties;
    /** 与数字人驱动相同的默认形象。 */ private final String defaultId;
    public ConfiguredOpeningAvatarAdapter(OpeningAvatarProperties properties,String defaultId) { this.properties=properties; this.defaultId=defaultId; }
    public String effectiveId(String requested) { return requested==null || requested.isBlank() ? defaultId : requested.trim(); }
    public String name(String avatarId) {
        String name=properties.getAvatarNames().get(avatarId);
        return name!=null && name.length()<=32 && OpeningTextPolicy.safeMaterial(name)
                && name.matches("[\\p{L}0-9 '\\-]+") ? name.trim() : null;
    }
}
