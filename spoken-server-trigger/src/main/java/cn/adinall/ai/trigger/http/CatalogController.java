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
 * 目录接口入口（App 启动时问「有哪些形象、哪些驱动模型、这个后端能干什么、有哪些练习场景」）
 * <p>
 * 功能：把四类只读查询直接转发给目录用例，Controller 本身不含任何判断 ——
 * 全部是只读查询，所以这里就是一层转发。
 * <pre>
 *   GET /api/v1/catalog/avatars       列可用数字人形象
 *   GET /api/v1/catalog/models        列可用驱动模型及其连接状态
 *   GET /api/v1/catalog/capabilities  声明后端能力（App 据此决定显示哪些按钮）
 *   GET /api/v1/catalog/scenarios     列口语训练场景（剧本）
 * </pre>
 * 运行过程（以 models 为例）：
 * <pre>
 *   App 启动
 *        |
 *        | GET /api/v1/catalog/models
 *        v
 *   CatalogController.models()
 *        |
 *        v
 *   ICatalogCase.listModels()
 *        |
 *        | (1) avatarDriver.listModels() 拉上游 /models（连不上则造 connected=false 的占位项）
 *        | (2) 领域 VO -> ModelResponseDTO
 *        v
 *   HTTP 200 [ {"id":"flashhead","label":"FlashHead 1.3B（实时）","connected":true,"isDefault":true}, ... ]
 * </pre>
 * 案例：
 * <pre>
 *   输入: GET /api/v1/catalog/capabilities
 *   处理: catalogCase.capabilities() 把 DriverCapabilityVO 原样搬成 CapabilitiesResponseDTO
 *   输出: 200 {"providerId":"opentalking","webrtcVideo":true,"upstreamAudio":true,"interrupt":true,
 *          "textDriven":true,"scriptedSay":true,"supportedModels":["flashhead","flashtalk"]}
 *         形象接口: 200 [ {"id":"anchor","name":"anchor","online":true} ]（上游连不上时给配置兜底形象）
 *         异常时: 由 GlobalExceptionHandler 输出非 200 + {"code":"...","message":"...","ts":...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>零业务逻辑、零 try/catch：异常直接冒到 GlobalExceptionHandler（非 200 + 统一错误体），
 *       这样同一套用例将来换入口（任务、消息、gRPC）时不用把错误处理也复制一遍。</li>
 *   <li>返回裸对象 / 裸数组，没有包 Response 外壳 —— 与 AgentServiceController 那组接口不一致：
 *       目录类是既有契约（App 按裸 JSON 解析），统一外壳等于同时改 App。</li>
 *   <li>用构造器注入 ICatalogCase 而不是字段注入：依赖只有一个、必需且不可变，
 *       也让本类可以脱离 Spring 直接 new 出来测。</li>
 *   <li>四个接口都用 @GetMapping、没有请求参数：目录数据只读且与调用者无关，
 *       任何「按用户过滤」的需求都应新开接口，而不是给这里加参数。</li>
 * </ul>
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