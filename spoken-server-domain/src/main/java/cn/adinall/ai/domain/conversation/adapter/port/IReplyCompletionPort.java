package cn.adinall.ai.domain.conversation.adapter.port;

/**
 * 实际媒体播报完成的业务通知端口。保留原 replyId 后再规范化字幕，
 * 让 Case 激活与具体询问绑定的待选择任务，而非把文本生成完成当成播放完成。
 */
public interface IReplyCompletionPort {
    /** 完整且仍有效的媒体结束事件；被打断或迟到旧回复不得进入此通知。 */
    void completed(String sessionId,String replyId,String spokenText);
    /** 自动媒体关闭也必须释放业务运行态，不依赖客户端一定能发送 DELETE。 */
    void closed(String sessionId);
}
