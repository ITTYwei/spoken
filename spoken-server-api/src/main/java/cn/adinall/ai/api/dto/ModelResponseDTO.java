package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 驱动模型项（GET /api/v1/catalog/models 的列表元素）
 * <p>
 * 功能：列「这个后端能跑哪些驱动模型」以及每个模型此刻连不连得上，
 * App 据此把连不上的模型置灰（就是「当前驱动模型未连接」那句提示的来源）。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   id                模型 id，建会话时填进 CreateConversationSessionRequestDTO.model
 *   label             给人看的名字，如 "FlashHead 1.3B（实时）"
 *   connected         此刻是否连得上；false 时 App 置灰该模型
 *   reason            连不上的原因，供排查；connected=true 时通常为 null
 *   isDefault         是否是后端默认选的模型（JSON 名字由 @JsonProperty 钉死）
 *   estimatedChunkMs  预估单块推理耗时（毫秒），只用于 UI 提示，可为 null
 * </pre>
 * 体系架构 / 运行过程：
 * <pre>
 *   GET /api/v1/catalog/models
 *        |
 *        v
 *   CatalogController.models()
 *        |
 *        v
 *   IAvatarDriverPort.listModels()
 *        |
 *        v
 *   CatalogResponseAssembler.model(...) -> ModelResponseDTO -> JSON
 * </pre>
 * 案例：
 * <pre>
 *   输入: 无（GET，不带参数）
 *   处理: exposeModels=[flashhead,flashtalk]，上游只报了 flashhead 在线
 *   输出: [ {"id":"flashhead","label":"FlashHead 1.3B（实时）","connected":true,
 *           "isDefault":true,"estimatedChunkMs":300},
 *          {"id":"flashtalk","label":"FlashTalk 14B（高画质，慢）","connected":false,
 *           "reason":"provider_unreachable","estimatedChunkMs":110000} ]
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>isDefault 上写了 @JsonProperty("isDefault")：record 里 isXxx 形式的布尔组件会被
 *       Jackson 当成 getter 风格序列化成 "default"，钉死名字才能守住对外契约。</li>
 *   <li>「可用性」放在列表项里而不是整份列表上：一个模型连不上不该让整份列表变成错误，
 *       前端逐个置灰比整页报错更有用。</li>
 *   <li>reason 是给人看的排查线索（如 provider_unreachable），App 拿它做分支时
 *       应当只做「显示」，判断可用性一律看 connected。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelResponseDTO(
        String id,
        String label,
        boolean connected,
        String reason,
        /** 显式钉住 JSON 名字：record 里 isXxx 这种布尔组件容易被序列化成 default */
        @JsonProperty("isDefault") boolean isDefault,
        /** 预估单块推理耗时（毫秒），仅用于 UI 提示，可为空 */
        Long estimatedChunkMs
) {
}