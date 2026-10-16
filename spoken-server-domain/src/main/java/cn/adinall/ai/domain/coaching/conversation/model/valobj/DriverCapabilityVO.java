package cn.adinall.ai.domain.coaching.conversation.model.valobj;

import java.util.List;

/**
 * 驱动后端能力声明值对象（业务层只看声明决定行为，不按实现类名判断）
 * <p>
 * 功能：声明某个驱动实现支持哪些能力；接口层把它原样透给 App，
 * App 据此决定「要不要显示麦克风 / 画面位」。
 * <pre>
 *   providerId        实现标识，与 IAvatarDriverPort.id() 一致（OpenTalking = "opentalking"）
 *   webrtcVideo       有没有 WebRTC 视频画面
 *   upstreamAudio     收不收上行音频；false 时 transcribeAndRecord 抛 upstream_audio_unsupported
 *   interrupt         能不能打断
 *   textDriven        能不能文本驱动（speak）
 *   scriptedSay       能不能脚本轨直念（say）；false 时用例层不要调 say
 *   supportedModels   支持哪些模型，取自配置 exposeModels（默认 flashhead、flashtalk）
 * </pre>
 * 数据流：
 * <pre>
 *   OpenTalkingAvatarDriver.capabilities()
 *        |
 *        v
 *   DriverCapabilityVO
 *        +--> ConversationServiceImpl.transcribeAndRecord 先看 upstreamAudio()
 *        +--> 用例层决定开场白走 say 还是退化成 speak 触发词
 *        +--> 接口层 --> App 决定显示麦克风 / 画面位
 * </pre>
 * 案例：
 * <pre>
 *   输入: OpenTalkingAvatarDriver.capabilities()
 *   处理: 固定声明 + 配置 exposeModels
 *   输出: DriverCapabilityVO{providerId="opentalking", webrtcVideo=true, upstreamAudio=true,
 *         interrupt=true, textDriven=true, scriptedSay=true,
 *         supportedModels=["flashhead", "flashtalk"]}
 *   对照: 某后端 scriptedSay=false 时，用例层的开场白不能调 say，
 *         只能改用触发词让 LLM 生成（慢但能work）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>把能力做成数据而不是接口方法：业务层写 capabilities().upstreamAudio() 比
 *       instanceof / 类名判断更容易替换实现，也更容易在测试里造假数据。</li>
 *   <li>supportedModels 是「对外暴露集合」而不是上游全部模型：适配器会把不在
 *       exposeModels 里的模型过滤掉，避免用户选到一个这边根本没配好的模型。</li>
 *   <li>scriptedSay 是最容易被忽略的一项：它决定「内容已定的话」能不能走高可控低延迟的脚本轨，
 *       实现方声明 false 时必须让用例层看得到，而不是等到运行时静默退化。</li>
 * </ul>
 */
public record DriverCapabilityVO(
        String providerId,
        boolean webrtcVideo,
        boolean upstreamAudio,
        boolean interrupt,
        boolean textDriven,
        /**
         * 能否走脚本轨直念（{@code IAvatarDriverPort#say}）：文本直通 TTS、不经过 LLM。
         * 为 false 时用例层不要调 say，改用触发词让 LLM 生成（慢但能work）。
         */
        boolean scriptedSay,
        List<String> supportedModels
) {
}