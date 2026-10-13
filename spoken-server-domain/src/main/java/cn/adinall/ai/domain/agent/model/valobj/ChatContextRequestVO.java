package cn.adinall.ai.domain.agent.model.valobj;

/**
 * 功能：隔离当前规则与历史内容，作为上下文提供者的统一输入。
 * <p>体系架构 / 运行过程：</p>
 * <pre>case -> request -> providers -> reducer -> model message</pre>
 * <p>案例：本轮 L2/QUESTION 只出现一次，不从历史复制旧 L5/READ。</p>
 * <p>设计要点：</p>
 * <ul><li>userText 是引用数据。</li><li>subjectKey 由身份端口产生。</li></ul>
 */
public record ChatContextRequestVO(
        /** 可信主体。 */ String subjectKey,
        /** 业务会话。 */ String sessionId,
        /** 本轮用户原话，用于相关性筛选。 */ String userText,
        /** 本轮唯一有效控制规则与当前输入。 */ String currentInstruction) {}
