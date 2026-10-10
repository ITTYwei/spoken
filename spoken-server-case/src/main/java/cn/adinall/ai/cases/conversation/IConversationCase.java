package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.api.dto.CreateConversationSessionRequestDTO;
import cn.adinall.ai.api.dto.IceServerResponseDTO;
import cn.adinall.ai.api.dto.OpeningResponseDTO;
import cn.adinall.ai.api.dto.SayRequestDTO;
import cn.adinall.ai.api.dto.ServerEventDTO;
import cn.adinall.ai.api.dto.SessionResponseDTO;
import cn.adinall.ai.api.dto.SpeakRequestDTO;
import cn.adinall.ai.api.dto.TurnResponseDTO;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 会话用例（App 的一次请求 = 这里的一个方法）
 * <p>
 * 功能：把 HTTP 入参翻译成领域服务调用，再把领域结果翻回 api 模块的 DTO；
 * 「等多久 / 轮询几次 / 流怎么拼」这些编排决策也落在这一层。
 * 这一层只有「流程」没有「规则」—— 规则（会话能干什么、状态怎么变）在领域服务与聚合里。
 * <pre>
 *   create()      建会话：先关旧会话 -> 等槽位释放 -> 建新会话 -> 轮询就绪
 *   get()         查会话状态（顺手向上游纠偏一次），App 靠它轮询 QUEUED -> READY
 *   iceServers()  取 WebRTC 的 ICE 服务器配置
 *   offer()       SDP 交换：offer 换 answer
 *   speak()       文本驱动数字人说话（走 LLM 生成轨）
 *   say()         脚本轨：让数字人原样念出这段文字，不经过 LLM
 *   opening()     开场：让数字人说出本场景的开场白（幂等，只真正下发一次）
 *   voice()       语音上行（裸 PCM / s16le 单声道），返回识别文本
 *   interrupt()   打断当前这句
 *   close()       关会话并释放上游资源
 *   turns()       取本场会话的历史回合
 *   events()      SSE 事件流：流头状态 + 上游事件 + 本地事件 + 15 秒心跳
 * </pre>
 * 体系架构（它两侧的边界）：
 * <pre>
 *   SessionController  @RequestMapping("/api/v1/sessions")，只做转发
 *        |
 *        | 对外只认 api 模块 DTO：CreateConversationSessionRequestDTO /
 *        | SpeakRequestDTO / SayRequestDTO / SessionResponseDTO / ServerEventDTO
 *        v
 *   IConversationCase（本接口：只声明会话动作，不含编排实现）
 *        |
 *        | 对内只认领域类型：StartConversationCommandEntity / SpeakCommandEntity /
 *        | SayCommandEntity / ConversationSessionAggregate / ConversationTurnAggregate
 *        v
 *   IConversationService（领域服务：会话状态机唯一入口，不 sleep、不轮询）
 * </pre>
 * 案例：
 * <pre>
 *   输入: POST /api/v1/sessions
 *         CreateConversationSessionRequestDTO{deviceId="dev-001", avatarId=null,
 *         model=null, voice=null, scenarioId="interview"}
 *   处理: create(request) -> 关掉 dev-001 的旧会话 -> 建新会话 ->
 *         轮询最多 appConfig.readyWaitMs()（配置里 15000ms）等它就绪
 *   输出: SessionResponseDTO{sessionId="s-3f2a9c8b1d4e6f70", status="READY",
 *         voice="en-US-JennyNeural", startedAt=1735468800000}
 *         （请求里没给 avatarId / model，聚合里存的就是 null，而 SessionResponseDTO 标了
 *          NON_NULL，所以这两个键在响应里不出现；形象与模型的配置兜底只作用于上游调用那一步）
 *   超时: 上游仍在排队时 status 返回 "QUEUED"（不抛异常），由 App 继续轮询或听 SSE
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>接口只暴露 DTO：SessionController 因此薄得像张纸，领域类型不会泄漏到 HTTP 边界之外。</li>
 *   <li>「等」留在实现类里（sleep / 轮询），领域服务保持纯同步、可单测、不阻塞线程。</li>
 *   <li>opening() 刻意做成幂等并返回 spoken / reason 而不是报错：
 *       客户端超时重试和用户连点都会打到它，而重复开场会让数字人把第一句话说两遍，代价不可撤销。</li>
 * </ul>
 */
public interface IConversationCase {

    /**
     * 建会话。
     * 流程：先把该设备的旧会话关掉并等一小会儿（上游单 GPU 槽的释放是异步的）->
     * 建新会话 -> 轮询最多 readyWaitMs 等它就绪（超时不报错，返回 QUEUED 让 App 继续轮询/听 SSE）。
     */
    SessionResponseDTO create(CreateConversationSessionRequestDTO request);

    /** 查会话状态（顺手向上游纠偏一次）。App 靠它轮询 QUEUED -> READY。 */
    SessionResponseDTO get(String sessionId);

    /** 取 WebRTC 的 ICE 服务器配置。 */
    List<IceServerResponseDTO> iceServers();

    /** WebRTC SDP 交换：offer 换 answer。 */
    String offer(String sessionId, String sdp);

    /** 文本驱动数字人说话。 */
    void speak(String sessionId, SpeakRequestDTO request);

    /**
     * 脚本轨：让数字人原样念出这段文字，不经过 LLM。
     * 内容已定的话（开场白、题目、讲解、判分反馈）走这里。
     */
    void say(String sessionId, SayRequestDTO request);

    /**
     * 开场：让数字人主动说出本场景的开场白（脚本轨，不经过 LLM）。
     *
     * <b>幂等</b>：同一场会话只真正下发一次。重复调用返回
     * {@code spoken=false / reason=already_spoken} 而不是报错 ——
     * 客户端超时重试、用户连点两次都会走到这里，而重复开场会让数字人把
     * 第一句话说两遍，代价不可撤销。
     */
    OpeningResponseDTO opening(String sessionId);

    /**
     * 语音上行（裸 PCM / s16le 单声道）。返回识别文本；用户台词也会走 SSE 的 stt.final。
     */
    String voice(String sessionId, byte[] pcm, int sampleRate);

    /** 打断当前这句。 */
    void interrupt(String sessionId);

    /** 关会话并释放上游资源。 */
    void close(String sessionId);

    /** 取本场会话的历史回合。 */
    List<TurnResponseDTO> turns(String sessionId);

    /**
     * SSE 事件流：流头一条当前状态，之后是上游事件 + 本地事件（stt.final / 打断回执）+ 15 秒心跳。
     * 心跳有两个作用：顶住 Servlet 容器的 async 超时，以及让 App 判断链路还活着。
     */
    Flux<ServerEventDTO> events(String sessionId);
}