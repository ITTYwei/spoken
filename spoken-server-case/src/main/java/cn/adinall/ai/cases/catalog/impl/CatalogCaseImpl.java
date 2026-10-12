package cn.adinall.ai.cases.catalog.impl;

import cn.adinall.ai.cases.catalog.ICatalogCase;
import cn.adinall.ai.api.dto.AvatarResponseDTO;
import cn.adinall.ai.api.dto.CapabilitiesResponseDTO;
import cn.adinall.ai.api.dto.ModelResponseDTO;
import cn.adinall.ai.api.dto.ScenarioResponseDTO;
import cn.adinall.ai.domain.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.conversation.model.valobj.AvatarProfileVO;
import cn.adinall.ai.domain.conversation.model.valobj.DriverCapabilityVO;
import cn.adinall.ai.domain.conversation.model.valobj.DriverModelVO;
import cn.adinall.ai.domain.scenario.model.valobj.ScenarioVO;
import cn.adinall.ai.domain.scenario.adapter.repository.IScenarioRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 目录用例实现（领域值对象 -> 对外 DTO 的直译器）
 * <p>
 * 功能：实现 ICatalogCase 的四个只读查询，把领域 VO 逐个字段翻成 api 模块的 ResponseDTO，不做加工。
 * 形象 / 模型 / 能力声明来自驱动端口（换后端自动跟着变），场景来自场景仓储
 * —— 两类数据来源不同，但都只读、都不带会话状态。
 * <pre>
 *   listAvatars()    avatarDriver.listAvatars()   -> toAvatarResponse()    形象列表
 *   listModels()     avatarDriver.listModels()    -> toModelResponse()     模型与连接状态
 *   capabilities()   avatarDriver.capabilities()  -> new CapabilitiesResponseDTO(...)
 *   listScenarios()  scenarioRepository.listAll() -> toScenarioResponse()  场景列表
 * </pre>
 * 运行过程（以 capabilities() 为例，其余三个同构）：
 * <pre>
 *   CatalogController.capabilities()
 *        |
 *        v
 *   CatalogCaseImpl.capabilities()
 *        |
 *        | (1) avatarDriver.capabilities() --> DriverCapabilityVO
 *        |        （providerId / webrtcVideo / upstreamAudio / interrupt /
 *        |          textDriven / scriptedSay / supportedModels）
 *        |
 *        | (2) 逐个字段塞进 new CapabilitiesResponseDTO(...)，不裁剪、不补默认值
 *        v
 *   CapabilitiesResponseDTO 原样返回给 Controller
 * </pre>
 * 案例：
 * <pre>
 *   输入: 无参数（GET /api/v1/catalog/capabilities，当前适配器 OpenTalkingAvatarDriver）
 *   处理: avatarDriver.capabilities() 返回 DriverCapabilityVO{providerId="opentalking",
 *         webrtcVideo=true, upstreamAudio=true, interrupt=true, textDriven=true,
 *         scriptedSay=true, supportedModels=["flashhead","flashtalk"]}
 *   输出: CapabilitiesResponseDTO{providerId="opentalking", webrtcVideo=true,
 *         upstreamAudio=true, interrupt=true, textDriven=true, scriptedSay=true,
 *         supportedModels=["flashhead","flashtalk"]}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>转换写成私有静态方法（toAvatarResponse / toModelResponse / toScenarioResponse）：
 *       字段一一对应、无外部依赖，静态方法既省一个实例，也能被单测直接调用。</li>
 *   <li>capabilities() 只做「原样搬」这一件事：用例层不替实现做假设，
 *       否则将来新增一个驱动后端时会莫名收到一份被裁剪过的能力声明。</li>
 *   <li>构造期只保存接口引用（IAvatarDriverPort / IScenarioRepository）：
 *       当前生效的是哪个实现由 Spring 决定（现状为 OpenTalkingAvatarDriver /
 *       InMemoryScenarioRepository），本类不认识具体实现。</li>
 * </ul>
 */
@Service
public class CatalogCaseImpl implements ICatalogCase {

    private final IAvatarDriverPort avatarDriver;
    private final IScenarioRepository scenarioRepository;

    /**
     * 注入数字人驱动端口和场景仓储，构造期只保存引用。
     * Spring 会把当前生效的适配器（OpenTalking 或 mock）注进来，用例本身不认识具体实现。
     */
    public CatalogCaseImpl(IAvatarDriverPort avatarDriver, IScenarioRepository scenarioRepository) {
        this.avatarDriver = avatarDriver;
        this.scenarioRepository = scenarioRepository;
    }

    @Override
    public List<AvatarResponseDTO> listAvatars() {
        return avatarDriver.listAvatars().stream()
                .map(CatalogCaseImpl::toAvatarResponse)
                .toList();
    }

    @Override
    public List<ModelResponseDTO> listModels() {
        return avatarDriver.listModels().stream().map(CatalogCaseImpl::toModelResponse).toList();
    }

    /**
     * 后端能力：只有「把领域声明原样搬过去」一件事，不在这里替实现做任何假设。
     */
    @Override
    public CapabilitiesResponseDTO capabilities() {
        DriverCapabilityVO capability = avatarDriver.capabilities();
        return new CapabilitiesResponseDTO(capability.providerId(), capability.webrtcVideo(),
                capability.upstreamAudio(), capability.interrupt(), capability.textDriven(),
                capability.scriptedSay(), capability.supportedModels());
    }

    @Override
    public List<ScenarioResponseDTO> listScenarios() {
        return scenarioRepository.listAll().stream().map(CatalogCaseImpl::toScenarioResponse).toList();
    }

    private static AvatarResponseDTO toAvatarResponse(AvatarProfileVO avatar) {
        return new AvatarResponseDTO(avatar.id(), avatar.name(), avatar.coverUrl(),
                avatar.previewVideoUrl(), avatar.online());
    }

    private static ModelResponseDTO toModelResponse(DriverModelVO model) {
        return new ModelResponseDTO(model.id(), model.label(), model.connected(), model.reason(),
                model.isDefault(), model.estimatedChunkMs());
    }

    private static ScenarioResponseDTO toScenarioResponse(ScenarioVO scenario) {
        return new ScenarioResponseDTO(scenario.id(), scenario.title(), scenario.goal(),
                scenario.systemPromptHint(), scenario.level(), scenario.openingLine(), scenario.voice());
    }
}