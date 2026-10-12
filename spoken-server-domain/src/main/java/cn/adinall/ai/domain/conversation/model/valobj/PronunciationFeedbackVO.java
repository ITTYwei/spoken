package cn.adinall.ai.domain.conversation.model.valobj;

import java.util.List;

/** 陪练教学需要的评测摘要，由用例层从评测领域映射。 */
public record PronunciationFeedbackVO(Double score, List<IssueVO> errors) {
    public PronunciationFeedbackVO {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public record IssueVO(String word, String expected, String actual) {
    }
}
