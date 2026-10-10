package cn.adinall.ai.api.dto;

import java.util.List;

/**
 * 当前数字人后端的能力声明（GET /api/v1/catalog/capabilities 的出参）
 * <p>
 * 功能：把「这个后端能干什么」明明白白告诉 App —— 它是领域层 {@code IAvatarDriverPort}
 * 能力值对象的对外形状，换一个数字人实现就换一份声明，App 不用改。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   providerId        后端实现标识；avatar 配置为 opentalking 时就是 "opentalking"
 *   webrtcVideo       是否提供 WebRTC 视频（false 时 App 不摆画面位）
 *   upstreamAudio     是否收上行音频（false 时 App 不显示麦克风）
 *   interrupt         是否支持打断当前这句
 *   textDriven        是否支持「不经过麦克风、直接给文本让数字人说」
 *   scriptedSay       能否走脚本轨直念（文本直通 TTS、不经过 LLM）
 *   supportedModels   可用模型 id 列表，如 ["flashhead","flashtalk"]
 * </pre>
 * 运行过程：
 * <pre>
 *   GET /api/v1/catalog/capabilities
 *        |
 *        v
 *   CatalogController.capabilities()
 *        |
 *        v
 *   CatalogCaseImpl.capabilities()
 *        |
 *        | (1) avatarDriver.capabilities() --> DriverCapabilityVO
 *        | (2) new CapabilitiesResponseDTO(...) 按组件顺序原样搬字段，不做任何加工
 *        v
 *   {"providerId":"opentalking","webrtcVideo":true,"upstreamAudio":true,
 *    "interrupt":true,"textDriven":true,"scriptedSay":true,
 *    "supportedModels":["flashhead","flashtalk"]}
 * </pre>
 * 案例：
 * <pre>
 *   输入: 无（GET，不带参数）
 *   处理: OpenTalkingAvatarDriver.capabilities() 返回
 *         DriverCapabilityVO("opentalking", true, true, true, true, true, exposeModels)
 *   输出: {"providerId":"opentalking","webrtcVideo":true,"upstreamAudio":true,"interrupt":true,
 *          "textDriven":true,"scriptedSay":true,"supportedModels":["flashhead","flashtalk"]}
 *         mock 实现（spoken-ai.provider.avatar=mock）时会返回一份能力更少的声明
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>做成「后端自述」而不是写死在前端：换数字人实现时 App 只需要少显示几个按钮，
 *       不必发版；这也是 mock 实现能跑通全流程的前提。</li>
 *   <li>布尔字段全部用基本类型 boolean，没有包装类型：能力声明必须给确定答案，
 *       缺失（null）在这里没有语义，等于「这个后端没表态」。</li>
 *   <li>注意本接口是裸对象返回，没有包 Response 外壳（和 /api/v1/* 下的智能体接口不一致）：
 *       目录类接口的历史契约如此，动它等于同时改 App 的解析代码。</li>
 * </ul>
 */
public record CapabilitiesResponseDTO(
        String providerId,
        boolean webrtcVideo,
        boolean upstreamAudio,
        boolean interrupt,
        boolean textDriven,
        /** 能否走脚本轨直念（文本直通 TTS、不经过 LLM）。 */
        boolean scriptedSay,
        List<String> supportedModels
) {
}