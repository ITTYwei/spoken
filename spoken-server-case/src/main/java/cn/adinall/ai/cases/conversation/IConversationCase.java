package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.cases.conversation.model.*;
import cn.adinall.ai.domain.coaching.conversation.model.entity.SpeakCommandEntity;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 经过认证的应用入口；传输协议转换由输入适配器负责。
 */
public interface IConversationCase {
    /**
     * 校验创建身份并建立会话，同一账号设备的旧会话按创建策略释放。
     *
     * @param command 创建参数，设备标识会在应用入口按账号隔离
     * @return 初始会话状态，排队时不保证媒体已经就绪
     */
    SessionResult create(CreateConversationCommand command);

    /**
     * 校验当前账号的会话归属后返回会话状态。
     */
    SessionResult get(String sessionId);

    /**
     * 读取媒体连接使用的 ICE 服务器配置。
     */
    List<IceServerVO> iceServers();

    /**
     * 校验会话归属后交换 SDP 描述。
     *
     * @param sessionId 当前账号拥有的会话标识
     * @param sdp       客户端生成的媒体连接描述
     * @return 上游返回的媒体连接应答
     */
    String offer(String sessionId, String sdp);

    /**
     * 在指定会话中提交播报命令。
     */
    void speak(String sessionId, SpeakCommandEntity command);

    /**
     * 按冻结的开场决策播放首句，重复请求不重复播报。
     */
    OpeningResult opening(String sessionId);

    /**
     * 处理用户语音，记录识别与发音评估结果并触发回复；评估失败时允许降级对话。
     *
     * @param sessionId    当前账号拥有的会话标识
     * @param pcm          原始 PCM 音频字节
     * @param sampleRate   音频采样率，单位为赫兹
     * @param expectedText 可选的发音参考文本
     * @return 本轮用户文本、回合标识及可用的发音评估
     */
    ConversationTurnResultVO voice(String sessionId, byte[] pcm, int sampleRate, String expectedText);

    /**
     * 为已开启的跟读模式准备并播放首个示范。
     */
    void startGuidedReading(String sessionId);

    /**
     * 打断当前回复，取消生成和播放，并清除未播出的待提交状态。
     */
    void interrupt(String sessionId);

    /**
     * 校验会话归属后关闭会话，取消生成与播放并释放连接和运行状态。
     */
    void close(String sessionId);

    /**
     * 校验会话归属后提供业务事件流；会话关闭时结束，不包含传输协议心跳。
     *
     * @param sessionId 当前账号拥有的会话标识
     * @return 会话状态、字幕和教学状态等业务事件
     */
    Flux<ConversationEventVO> events(String sessionId);
}
