package cn.adinall.ai.cases.conversation.model;

public record OpeningResult(boolean spoken, String text, String reason) {
    /** 构造已提交播报的开场结果。 */
    public static OpeningResult spoken(String text) { return new OpeningResult(true, text, null); }
    /** 构造未播报的开场结果并保留跳过原因。 */
    public static OpeningResult skipped(String reason) { return new OpeningResult(false, null, reason); }
}
