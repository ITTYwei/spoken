package cn.adinall.ai.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Data;
import java.util.Map;
import java.util.HashMap;

/**
 * 功能：提供与真实形象ID绑定的明确名称配置，当前默认全部未命名。
 * <p>体系架构 / 运行过程：</p>
 * <pre>spoken-ai.opening.avatar-names -> ID/name map -> opening identity port</pre>
 * <p>案例：avatar-names.anchor=小安 -> anchor开场介绍小安。</p>
 * <p>设计要点：</p>
 * <ul><li>无配置不读ID或场景角色名。</li><li>无需客户端传入自由名称。</li></ul>
 */
@Data
@ConfigurationProperties(prefix="spoken-ai.opening")
public class OpeningAvatarProperties {
    /** 明确的数字人名称；键必须与当前选中的avatarId一致，默认空映射。 */
    private Map<String,String> avatarNames=new HashMap<>();
}
