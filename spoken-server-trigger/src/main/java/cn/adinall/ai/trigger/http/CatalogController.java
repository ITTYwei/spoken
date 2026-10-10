package cn.adinall.ai.trigger.http;

import cn.adinall.ai.api.dto.AvatarResponseDTO;
import cn.adinall.ai.api.dto.CapabilitiesResponseDTO;
import cn.adinall.ai.api.dto.ModelResponseDTO;
import cn.adinall.ai.api.dto.ScenarioResponseDTO;
import cn.adinall.ai.cases.catalog.ICatalogCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 目录接口：App 启动时问「有哪些形象、哪些驱动模型、这个后端能干什么、有哪些练习场景」。
 * 全部是只读查询，所以 Controller 就是一层转发。
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final ICatalogCase catalogCase;

    public CatalogController(ICatalogCase catalogCase) {
        this.catalogCase = catalogCase;
    }

    /**
     * 列可用数字人形象。上游拉不到时用例会给出配置里的兜底形象，所以这里不会返回空列表。
     */
    @GetMapping("/avatars")
    public List<AvatarResponseDTO> avatars() {
        return catalogCase.listAvatars();
    }

    /**
     * 列可用的驱动模型及其连接状态。
     * 每个模型带 connected 和 reason，App 据此把连不上的模型置灰
     * （就是「当前驱动模型未连接」那句话的来源）。
     */
    @GetMapping("/models")
    public List<ModelResponseDTO> models() {
        return catalogCase.listModels();
    }

    /** 后端能力：App 据此决定「要不要显示麦克风 / 画面位」，不用把实现写死在前端。 */
    @GetMapping("/capabilities")
    public CapabilitiesResponseDTO capabilities() {
        return catalogCase.capabilities();
    }

    /** 列口语训练场景（剧本）。 */
    @GetMapping("/scenarios")
    public List<ScenarioResponseDTO> scenarios() {
        return catalogCase.listScenarios();
    }
}