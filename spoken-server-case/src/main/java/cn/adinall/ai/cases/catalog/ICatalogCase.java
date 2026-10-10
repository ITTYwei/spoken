package cn.adinall.ai.cases.catalog;

import cn.adinall.ai.api.dto.AvatarResponseDTO;
import cn.adinall.ai.api.dto.CapabilitiesResponseDTO;
import cn.adinall.ai.api.dto.ModelResponseDTO;
import cn.adinall.ai.api.dto.ScenarioResponseDTO;

import java.util.List;

/**
 * 目录用例（App 启动期的只读查询入口）
 * <p>
 * 功能：给「还没建会话的 App」问清单 —— 有哪些形象、哪些驱动模型、这个后端能干什么、有哪些练习场景。
 * 全是只读查询，没有状态变更，所以它连会话领域服务都不需要，只读驱动端口和场景目录。
 * <pre>
 *   listAvatars()     列可用数字人形象（适配器内部已兜底，不会因为上游抖动返回空）
 *   listModels()      列可用驱动模型及其连接状态（connected=false 时 App 把该模型置灰）
 *   capabilities()    后端能力声明：App 据此决定「要不要显示麦克风 / 画面位」
 *   listScenarios()   列口语训练场景（剧本）
 * </pre>
 * 体系架构（调用方与本接口的分工）：
 * <pre>
 *   CatalogController  @RequestMapping("/api/v1/catalog")
 *        |
 *        | GET /avatars       -> listAvatars()
 *        | GET /models        -> listModels()
 *        | GET /capabilities  -> capabilities()
 *        | GET /scenarios     -> listScenarios()
 *        v
 *   ICatalogCase（本接口：只声明能力，不含实现）
 *        |
 *        v
 *   CatalogCaseImpl
 *        |
 *        +--> IAvatarDriverPort    形象 / 模型 / 能力声明
 *        +--> IScenarioRepository  场景目录
 *        v
 *   AvatarResponseDTO / ModelResponseDTO / CapabilitiesResponseDTO / ScenarioResponseDTO
 * </pre>
 * 案例：
 * <pre>
 *   输入: 无参数（GET /api/v1/catalog/models）
 *   处理: CatalogCaseImpl.listModels() 读 IAvatarDriverPort.listModels()，逐个映射成 DTO
 *   输出: [ModelResponseDTO{id="flashhead", label="FlashHead 1.3B（实时）", connected=true,
 *          reason=null, isDefault=true, estimatedChunkMs=300}]
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>单独成一个用例上下文而不是并进 IConversationCase：它的读者是「还没建会话的 App」，
 *       生命周期与会话完全不同，混在一起会让会话用例白背一堆无状态查询。</li>
 *   <li>接口只声明能力、不声明实现：换数字人后端时只换 IAvatarDriverPort 的适配器，
 *       本接口与 CatalogController 一行都不用改。</li>
 *   <li>形象列表的兜底放在适配器里而不是这里：用例层不做「上游挂了怎么办」的判断，
 *       否则每个实现都要重复写一遍兜底策略。</li>
 * </ul>
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