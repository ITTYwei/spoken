package cn.adinall.ai.trigger.http;

import cn.adinall.ai.api.dto.*;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.domain.content.model.valobj.ScenarioVO;

/**
 * <b>功能</b>：将领域目录值对象转换成已有 HTTP 响应，供目录和首页共用。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>domain catalog VO -> field mapping -> API DTO -> JSON</pre>
 * <p><b>案例</b>：AvatarProfileVO(anchor,伙伴,...) -> AvatarResponseDTO(id=anchor,name=伙伴,...)。</p>
 * <b>设计要点</b>
 * <ul><li>只映射字段，不查询数据、不组合业务流程。</li><li>包内工具，不注册成额外服务或用例。</li></ul>
 */
final class CatalogResponseAssembler {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private CatalogResponseAssembler() { }

    /** 将形象目录项转换为 HTTP 响应。 */
    static AvatarResponseDTO avatar(AvatarProfileVO row) {
        return new AvatarResponseDTO(row.id(), row.name(), row.coverUrl(), row.previewVideoUrl(), row.online());
    }

    /** 将模型目录项转换为 HTTP 响应。 */
    static ModelResponseDTO model(DriverModelVO row) {
        return new ModelResponseDTO(row.id(), row.label(), row.connected(), row.reason(), row.isDefault(), row.estimatedChunkMs());
    }

    /** 将能力目录项转换为 HTTP 响应。 */
    static CapabilitiesResponseDTO capability(DriverCapabilityVO row) {
        return new CapabilitiesResponseDTO(row.providerId(), row.webrtcVideo(), row.upstreamAudio(),
                row.interrupt(), row.textDriven(), row.scriptedSay(), row.supportedModels());
    }

    /** 将场景目录项转换为 HTTP 响应。 */
    static ScenarioResponseDTO scenario(ScenarioVO row) {
        return new ScenarioResponseDTO(row.id(), row.title(), row.goal(), row.systemPromptHint(),
                row.level(), row.openingLine(), row.voice());
    }
}
