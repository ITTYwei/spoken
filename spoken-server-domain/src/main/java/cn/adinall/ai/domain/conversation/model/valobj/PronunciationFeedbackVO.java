package cn.adinall.ai.domain.conversation.model.valobj;

import java.util.List;

/**
 * 功能：传递教学所需的发音参考，不把总分当作综合英语水平。
 * <p>体系架构 / 运行过程：</p>
 * <pre>pronunciation result -> case -> feedback -> fixed-target correction policy</pre>
 * <p>案例：score=55、错词可信度缺失 -> 不编造发音问题。</p>
 * <p>设计要点：</p>
 * <ul><li>自由对话参考分不参与纠音和升级。</li><li>可信度未知保持 null。</li></ul>
 */
public record PronunciationFeedbackVO(
        /** 原始发音参考分，非综合能力分。 */ Double score,
        /** 具体错词候选，纠音最多使用一个。 */ List<IssueVO> errors) {
    public PronunciationFeedbackVO {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /**
     * 功能：保存一个可溯源的错词候选。
     * <p>体系架构 / 运行过程：</p>
     * <pre>原始错词 -> confidence gate -> 教学建议</pre>
     * <p>案例：confidence=null -> 不能作明确纠音依据。</p>
     * <p>设计要点：</p>
     * <ul><li>音素不直接进入 TTS 文本。</li><li>兼容旧的三参数调用。</li></ul>
     */
    public record IssueVO(
            /** 对照目标中的词。 */ String word,
            /** 参考音素，仅供内部判断。 */ String expected,
            /** 检测到的音素，可能为空。 */ String actual,
            /** 上游错词可信度，缺失不能当作确定。 */ Double confidence) {
        public IssueVO(String word, String expected, String actual) { this(word, expected, actual, null); }
    }
}
