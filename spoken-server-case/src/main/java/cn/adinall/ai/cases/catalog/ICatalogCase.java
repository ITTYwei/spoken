package cn.adinall.ai.cases.catalog;

import cn.adinall.ai.api.dto.AvatarResponseDTO;
import cn.adinall.ai.api.dto.CapabilitiesResponseDTO;
import cn.adinall.ai.api.dto.ModelResponseDTO;
import cn.adinall.ai.api.dto.ScenarioResponseDTO;

import java.util.List;

/**
 * 目录用例：App 启动时问「有哪些形象、哪些驱动模型、这个后端能干什么、有哪些练习场景」。
 *
 * 全是只读查询，没有状态变更 —— 所以它连会话领域服务都不需要，只读驱动端口和场景目录。
 * 单独成一个上下文是因为它的读者是「还没建会话的 App」，生命周期与会话完全不同。
 */
public interface ICatalogCase {

    /**
     * 列可用数字人形象。
     * 适配器内部已经做了兜底（拉不到上游就给配置里的默认形象），所以这里不会因为上游抖动而返回空。
     */
    List<AvatarResponseDTO> listAvatars();

    /**
     * 列可用的驱动模型及其连接状态。
     * App 看到 connected=false 就把该模型置灰 —— 就是「当前驱动模型未连接」那句话的来源。
     */
    List<ModelResponseDTO> listModels();

    /** 后端能力：App 据此决定「要不要显示麦克风 / 画面位」，不用把实现写死在前端。 */
    CapabilitiesResponseDTO capabilities();

    /** 列口语训练场景（剧本）。 */
    List<ScenarioResponseDTO> listScenarios();
}