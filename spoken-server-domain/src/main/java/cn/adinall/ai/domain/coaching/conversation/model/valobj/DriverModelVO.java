package cn.adinall.ai.domain.coaching.conversation.model.valobj;

/**
 * 驱动模型值对象（一个可选模型及其连接状态）
 * <p>
 * 功能：给 App 的模型选择界面提供选项与可用性；上游拉不到时返回一个 connected=false 的占位项。
 * <pre>
 *   id                模型 id（如 flashhead / flashtalk）
 *   label             展示名，由适配器按 id 生成
 *   connected         是否可用；false 时 App 显示「当前驱动模型未连接」
 *   reason            不可用原因（如 provider_unreachable / no_model_exposed），供排查
 *   isDefault         是否默认模型（按上游 default_model 或配置 defaultModel 标出）
 *   estimatedChunkMs  预估首块延迟（毫秒），null 表示未知
 * </pre>
 * 数据流：
 * <pre>
 *   OpenTalking GET /models
 *        |
 *        | OpenTalkingAvatarDriver.listModels：优先解析 statuses[]，退化解析 models[]，
 *        |   按配置 exposeModels 过滤，再用 default_model 标出默认项
 *        v
 *   List&lt;DriverModelVO&gt; --> 接口层 --> App 模型选择界面
 *   异常分支: /models 拉不到 --> [DriverModelVO{id="flashhead", label="flashhead",
 *             connected=false, reason="provider_unreachable", isDefault=true}]
 * </pre>
 * 案例：
 * <pre>
 *   输入: 上游返回 {"default_model":"flashhead","statuses":[
 *             {"id":"flashhead","connected":true},
 *             {"id":"flashtalk","connected":false,"reason":"worker_down"}]}
 *   处理: 过滤掉 exposeModels 之外的模型，把 default_model 对应的那条标 isDefault=true
 *   输出: [{id="flashhead", label="FlashHead 1.3B（实时）", connected=true, reason=null,
 *          isDefault=true, estimatedChunkMs=300},
 *         {id="flashtalk", label="FlashTalk 14B（高画质，慢）", connected=false,
 *          reason="worker_down", isDefault=false, estimatedChunkMs=110000}]
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>把 reason 透给 App 而不是在后端吞掉：这是现场唯一能区分「上游挂了吗」和
 *       「模型没暴露」的信息，日志里的 warn 用户看不到。</li>
 *   <li>上游拉不到时返回占位项，而不是空列表或抛异常：空列表会让 App 的模型选择界面变白，
 *       占位项至少还能明确显示「当前驱动模型未连接」。</li>
 *   <li>isDefault 这个组件名要留意：record 生成的访问器是 isDefault()，
 *       序列化字段名必须与 App 端约定一致，别按习惯改成 getDefault。</li>
 *   <li>isDefault 不能只按配置标：适配器的 markDefault 要求候选模型必须 connected，
 *       否则退到第一个已连接的模型 —— 配置里的模型可能压根没起，
 *       标错会让 App 自动选中一个未连接的模型。</li>
 * </ul>
 */
public record DriverModelVO(
        String id,
        String label,
        boolean connected,
        String reason,
        boolean isDefault,
        Long estimatedChunkMs
) {
}