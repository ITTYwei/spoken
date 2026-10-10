package cn.adinall.ai.cases.catalog;

import cn.adinall.ai.api.dto.AvatarResponseDTO;
import cn.adinall.ai.api.dto.CapabilitiesResponseDTO;
import cn.adinall.ai.api.dto.ModelResponseDTO;
import cn.adinall.ai.api.dto.ScenarioResponseDTO;
import cn.adinall.ai.domain.business.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.business.conversation.model.valobj.AvatarProfileVO;
import cn.adinall.ai.domain.business.conversation.model.valobj.DriverCapabilityVO;
import cn.adinall.ai.domain.business.conversation.model.valobj.DriverModelVO;
import cn.adinall.ai.domain.business.conversation.model.valobj.ScenarioVO;
import cn.adinall.ai.domain.business.conversation.adapter.repository.IScenarioRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 目录用例实现：把领域值对象翻成 DTO，不做任何加工。
 *
 * 形象和模型来自驱动端口（换后端自动跟着变），场景来自训练领域服务
 * —— 两类数据来源不同，但都只读、都不带会话状态。
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