package cn.adinall.ai.domain.companion.model.valobj;
/**
 * <b>功能</b>：定义可信工具调用使用的非模型上下文键。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * trusted invocation -> server context key -> tool boundary
 * translation -> tools-disabled policy -> text-only model request
 * </pre>
 * <p><b>案例</b>：翻译一段已完成字幕 -> 禁用业务工具，不能顺带修改课程或偏好。</p>
 * <b>设计要点</b>
 * <ul><li>仅为服务端契约，不让模型指定用户身份。</li><li>基础设施负责把协议标记转换为 SDK ToolContext。</li></ul>
 */
public final class TrustedInvocationKeys {
    public static final String KEY = "spoken.trustedInvocation";
    public static final String TEXT_ONLY_POLICY = "\nSERVER_TOOLS_DISABLED=true";
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private TrustedInvocationKeys() { }
}
