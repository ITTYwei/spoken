package cn.adinall.ai.domain.conversation.adapter.port;

import cn.adinall.ai.domain.conversation.model.entity.CreateSessionCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.SpeakCommandEntity;
import cn.adinall.ai.domain.conversation.model.valobj.AvatarProfileVO;
import cn.adinall.ai.domain.conversation.model.valobj.ConversationEventVO;
import cn.adinall.ai.domain.conversation.model.valobj.DriverCapabilityVO;
import cn.adinall.ai.domain.conversation.model.valobj.DriverModelVO;
import cn.adinall.ai.domain.conversation.model.valobj.IceServerVO;
import cn.adinall.ai.domain.conversation.model.valobj.SessionHandleVO;
import cn.adinall.ai.domain.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 数字人驱动端口（整个系统里最重要的一个端口，防腐层的领域侧）
 * <p>
 * 功能：把「数字人怎么被驱动」这件事完全挡在领域外面；OpenTalking 只是它的一个适配器实现。
 * <pre>
 *   id()                                      实现标识，与配置项 spoken-ai.provider.avatar 对应
 *   capabilities()                            能力声明（视频 / 上行音频 / 打断 / 文本驱动 / 脚本轨）
 *   listAvatars()                             列可选形象，供 App 形象选择界面
 *   listModels()                              列可用驱动模型及连接状态
 *   createSession(CreateSessionCommandEntity) 建会话，返回上游句柄
 *   status(String)                            查上游会话状态
 *   iceServers()                              WebRTC ICE 配置
 *   exchangeOffer(String, String)             SDP 交换，返回 answer
 *   speak(String, SpeakCommandEntity)         文本驱动（上游用 LLM 生成回复再念）
 *   say(String, SayCommandEntity)             脚本轨直念（default 实现直接拒绝）
 *   transcribeAudio(String, byte[], int)      上行 PCM 服务端识别（default 实现直接拒绝）
 *   interrupt(String)                         打断当前正在说的内容
 *   close(String)                             关闭会话并释放资源
 *   events(String)                            会话事件流（热流，订阅即开始）
 * </pre>
 * 体系架构（领域与实现的边界）：
 * <pre>
 *   ConversationServiceImpl / ConversationCaseImpl
 *        | 只认领域类型：VO / 命令实体
 *        v
 *   IAvatarDriverPort（本接口）
 *        |
 *        v
 *   OpenTalkingAvatarDriver（id() = "opentalking"）
 *        | 翻译上游字段名漂移、状态文案、错误码
 *        v
 *   OpenTalking HTTP / SSE（session_id、worker_ready、ot_http_404 ...）
 * </pre>
 * 案例：
 * <pre>
 *   输入: createSession(new CreateSessionCommandEntity("anchor", "flashhead",
 *             "en-US-JennyNeural", "You are Alex, an interviewer ..."))
 *   处理: 适配器 POST 上游建会话，把上游 session_id 映射成 providerSessionId，
 *         把上游状态文案 "queued" 映射成 SessionStatusEnum.QUEUED
 *   输出: SessionHandleVO{providerSessionId="ot-7f3a", status=QUEUED}
 *         排队的会话不在适配器里阻塞等待 —— 等待策略属于用例编排，不该埋进适配器
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>端口只认领域类型（VO / 命令实体）：上游的 JSON、SSE 字段名、会话 id 格式一律在适配器里
 *       翻译完 —— 这就是防腐层，换供应商 = 新写一个实现类 + 改一行配置，领域服务一行不用动。</li>
 *   <li>say 与 transcribeAudio 给出「默认直接拒绝」的 default 实现而不是抽象方法：
 *       能力不支持的后端宁可在调用点报一句看得懂的错（scripted_say_unsupported /
 *       upstream_audio_unsupported），也不要静默退化成 LLM 路径或假装识别成功。</li>
 *   <li>行为判断一律看 capabilities() 的声明，不要按实现类名判断：
 *       否则每加一个后端就要在业务代码里加一个 if。</li>
 *   <li>close 对「已经关掉了」应当视为成功：重复关闭是正常路径（建会话前会无条件关旧会话），
 *       把上游的 404 当失败会直接挡住新建会话。</li>
 * </ul>
 */
public interface IAvatarDriverPort {

    /** 实现标识，与配置项 spoken-ai.provider.avatar 对应。 */
    String id();

    /**
     * 声明这个实现支持哪些能力（有没有视频、收不收上行音频、能不能打断、能不能文本驱动、支持哪些模型）。
     * 业务层只看这份声明决定行为，不要按实现类名去判断。
     */
    DriverCapabilityVO capabilities();

    /**
     * 列可用的数字人形象，供 App 的形象选择界面使用。
     * 实现应尽量不抛异常（拉不到就给兜底列表），否则上游抖一下 App 的形象列表就空白了。
     */
    List<AvatarProfileVO> listAvatars();

    /**
     * 列可用的驱动模型及各自连接状态。connected=false 时 App 会显示「当前驱动模型未连接」。
     */
    List<DriverModelVO> listModels();

    /**
     * 创建一场会话，返回上游句柄（含 providerSessionId 和初始状态）。
     * 实现应当在内部把「排队中」如实映射成 QUEUED，而不是在这里阻塞等待 ——
     * 等待策略属于用例编排，不该埋进适配器。
     */
    SessionHandleVO createSession(CreateSessionCommandEntity command);

    /**
     * 查会话当前状态。业务层用它把 QUEUED 纠偏成 READY/CLOSED 等。
     */
    SessionStatusEnum status(String providerSessionId);

    /**
     * 返回 WebRTC 的 ICE 服务器配置，供 App 建立媒体连接；直连场景可以是空列表。
     */
    List<IceServerVO> iceServers();

    /** WebRTC SDP 交换：返回 answer SDP（non-trickle）。 */
    String exchangeOffer(String providerSessionId, String offerSdp);

    /**
     * 用文本驱动数字人说话。只负责下发，说话进度通过事件流回报。
     *
     * 注意 text 是「**用户说的话**」，上游会用 LLM 生成回复再念出来；
     * 要让数字人原样念一句已定的话，用 {@link #say}。
     */
    void speak(String providerSessionId, SpeakCommandEntity command);

    /**
     * 脚本轨：让数字人**原样念出** command.text()，不经过 LLM。
     *
     * 和 {@link #speak} 的分工：
     * <ul>
     *   <li>speak —— 「用户说了这句话」，上游用 LLM 生成回复再念；</li>
     *   <li>say   —— 「数字人要说的就是这句」，文本直通 TTS。</li>
     * </ul>
     *
     * 开场白、题目、讲解、判分反馈这类**内容已定**的话必须走这里：
     * 措辞可控、可单测、不依赖 LLM 可用性，实测首块音频 ~1.0s（走 speak 约 6.9s）。
     *
     * 只有 capabilities().scriptedSay() 为 true 的实现才需要给出真实行为。
     * 默认直接拒绝 —— 宁可在调用点报一句看得懂的错，也不要静默退化成 LLM 路径
     * （那会让"可控 + 低延迟"这个前提在运行时无声失效）。
     */
    default void say(String providerSessionId, SayCommandEntity command) {
        throw new AppException(ResponseCodeEnum.SCRIPTED_SAY_UNSUPPORTED,
                "当前数字人后端（" + id() + "）不支持脚本轨直念，请改用触发词走 LLM 路径");
    }

    /**
     * 上行一段 PCM 做服务端识别，返回识别出的文本（已 trim）。
     *
     * 只有 capabilities().upstreamAudio() 为 true 的实现才需要给出真实结果。
     * 默认实现直接拒绝 —— 宁可在调用点报一句看得懂的错，也不要假装识别成功。
     *
     * @param sampleRate 采样率，目前约定 16000（单声道 s16le）
     */
    default String transcribeAudio(String providerSessionId, byte[] pcm, int sampleRate) {
        throw new AppException(ResponseCodeEnum.UPSTREAM_AUDIO_UNSUPPORTED,
                "当前数字人后端（" + id() + "）不支持上行音频识别");
    }

    /**
     * 打断当前正在说的内容。
     */
    void interrupt(String providerSessionId);

    /**
     * 关闭会话并释放资源；对「已经关掉了」这种情况应当视为成功而不是报错。
     */
    void close(String providerSessionId);

    /** 会话事件流（热流，订阅即开始）。 */
    Flux<ConversationEventVO> events(String providerSessionId);
}