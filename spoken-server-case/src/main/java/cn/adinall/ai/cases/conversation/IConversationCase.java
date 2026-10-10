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
 * 会话用例：App 的一次请求 = 这里的一个方法。
 *
 * 这一层只有「流程」没有「规则」：
 * - 规则（会话能干什么、状态怎么变）在领域服务与聚合里；
 * - 流程（先关旧会话、等槽位释放、轮询就绪、拼 SSE）在这里。
 *
 * 它对外只认 DTO，对内只认领域类型，两边都不认识 HTTP —— 所以 Controller 薄得像张纸。
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