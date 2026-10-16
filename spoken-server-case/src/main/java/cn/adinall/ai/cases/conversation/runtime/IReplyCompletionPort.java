package cn.adinall.ai.cases.conversation.runtime;

/**
 * <b>功能</b>：连接媒体完成回执与练习任务提交的 Case 内部契约。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>media done -> valid replyId -> case completion handler -> domain task commit</pre>
 * <p><b>案例</b>：实际播完第一道题 -> 提交待回答任务；仅生成文本不能触发此回调。</p>
 * <b>设计要点</b>
 * <ul><li>不属于领域向基础设施发起请求的端口，因此放在 Case 运行流程中。</li>
 * <li>回调保持原始 replyId，关闭事件同步清理练习运行态。</li></ul>
 */
public interface IReplyCompletionPort {
    /** 完整且仍有效的媒体结束事件；被打断或迟到旧回复不得进入此通知。 */
    void completed(String sessionId,String replyId,String spokenText);
    /** 自动媒体关闭也必须释放业务运行态，不依赖客户端一定能发送 DELETE。 */
    void closed(String sessionId);
}
