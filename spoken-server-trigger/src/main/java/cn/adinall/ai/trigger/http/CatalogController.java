package cn.adinall.ai.trigger.http;

import cn.adinall.ai.api.dto.*;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.content.adapter.repository.IScenarioRepository;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * <b>功能</b>：提供形象、模型、媒体能力和场景的独立只读查询。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>GET catalog item -> domain port / repository -> HTTP DTO mapping -> JSON</pre>
 * <p><b>案例</b>：GET /api/v1/catalog/avatars -> [{id:anchor,name:伙伴,online:true}]。</p>
 * <b>设计要点</b>
 * <ul><li>每个请求只调用一个领域契约，无业务编排时不经过 Case。</li>
 * <li>保留 Android 使用的路径和字段，DTO 转换只在 HTTP 边界发生。</li></ul>
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
    private final IAvatarDriverPort avatars;
    private final IScenarioRepository scenarios;

    /** 创建 {@code CatalogController}，初始化其依赖与状态。 */
    public CatalogController(IAvatarDriverPort avatars, IScenarioRepository scenarios) {
        this.avatars = avatars;
        this.scenarios = scenarios;
    }

    /** 查询可用数字人形象目录。 */
    @GetMapping("/avatars")
    public List<AvatarResponseDTO> avatars() {
        return avatars.listAvatars().stream().map(CatalogResponseAssembler::avatar).toList();
    }

    /** 查询提供方支持的数字人模型。 */
    @GetMapping("/models")
    public List<ModelResponseDTO> models() {
        return avatars.listModels().stream().map(CatalogResponseAssembler::model).toList();
    }

    /** 查询当前数字人提供方支持的能力。 */
    @GetMapping("/capabilities")
    public CapabilitiesResponseDTO capabilities() {
        return CatalogResponseAssembler.capability(avatars.capabilities());
    }

    /** 查询开场场景配置。 */
    @GetMapping("/scenarios")
    public List<ScenarioResponseDTO> scenarios() {
        return scenarios.listAll().stream().map(CatalogResponseAssembler::scenario).toList();
    }
}
