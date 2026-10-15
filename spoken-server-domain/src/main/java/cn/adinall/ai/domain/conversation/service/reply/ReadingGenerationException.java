package cn.adinall.ai.domain.conversation.service.reply;

/** 可有限重生成的跟读校验异常；网络、权限、取消及超长异常不借此自动重试。 */
public final class ReadingGenerationException extends IllegalStateException {
    /** 失败原因由领域校验产生，不允许从模型文本或异常堆栈构造修正指令。 */
    private final ReadingGenerationErrorEnum reason;
    public ReadingGenerationException(ReadingGenerationErrorEnum reason) {
        super("跟读生成结果：" + reason.correction());
        this.reason = reason;
    }
    /** 返回明确业务原因，替代依赖异常文案前缀进行重试判断。 */
    public ReadingGenerationErrorEnum reason() { return reason; }
}
