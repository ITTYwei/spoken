package cn.adinall.ai.domain.conversation.model.valobj;

/**
 * 情境跟读的只读快照：只暴露实际播完的英文目标，不让手机从整段字幕猜题。
 * 会话关闭即失效；version 在模式或已播任务改变时递增，用于忽略迟到的 HTTP/SSE 快照。
 * correctionRetries 是已经邀请的纠音重试次数，首次示范为零；第三次重读后必须换句。
 */
public record ReadingModeVO(boolean enabled, String target, int correctionRetries,
                            int maxCorrections, long version) { }
