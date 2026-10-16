package cn.adinall.ai.domain.companion.model.valobj;

/**
 * 功能：表示有效用户内容和完整回复组成的历史组，不保存旧控制提示词。
 * <p>体系架构 / 运行过程：</p>
 * <pre>业务回合仓储 -> pair -> reducer -> quoted history</pre>
 * <p>案例：被打断、assistantText为空的回合不作为完整问答组。</p>
 * <p>设计要点：</p>
 * <ul><li>成组裁剪，不留下孤立助手消息。</li><li>摘要不推断性格、偏好和能力。</li></ul>
 */
public record DialogMessageVO(
        /** 可追溯的用户回合 ID。 */ Long turnId,
        /** 用户原话。 */ String userText,
        /** 已成功提交的回复，不包括未播出的模型内容。 */ String assistantText,
        /** 用户输入是在完成跟读/角色扮演练习，不能据此推断现实偏好。 */ boolean practiceInput) {
    /** 兼容没有练习标记的旧调用。 */
    public DialogMessageVO(Long turnId,String userText,String assistantText) { this(turnId,userText,assistantText,false); }
}
