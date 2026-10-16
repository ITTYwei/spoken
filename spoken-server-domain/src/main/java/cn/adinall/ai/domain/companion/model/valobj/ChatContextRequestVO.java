package cn.adinall.ai.domain.companion.model.valobj;

/**
 * 功能：隔离当前规则与历史内容，作为上下文提供者的统一输入。
 * <p>体系架构 / 运行过程：</p>
 * <pre>case -> request -> providers -> reducer -> model message</pre>
 * <p>案例：本轮 L2/QUESTION 独立传入 SYSTEM，不从历史复制旧 L5/READ。</p>
 * <p>设计要点：</p>
 * <ul><li>userText 是引用数据。</li><li>subjectKey 由身份端口产生。</li></ul>
 */
public record ChatContextRequestVO(
        /**
         * 可信主体。
         */
        String subjectKey,
        /** 业务会话。 */
        String sessionId,
        /** 本轮用户原话，用于相关性筛选。 */
        String userText,
        /** 必须保留的本轮原话与状态摘要。 */
        String currentInstruction,
        /** 单独进入 SYSTEM 的可信策略，仅用于预留输入容量，不追加到 USER。 */
        String systemPolicy) {
    /**
     * 兼容不带动态系统策略的上下文调用。
     */
    public ChatContextRequestVO(String subjectKey, String sessionId, String userText, String currentInstruction) {
        this(subjectKey, sessionId, userText, currentInstruction, "");
    }
}
