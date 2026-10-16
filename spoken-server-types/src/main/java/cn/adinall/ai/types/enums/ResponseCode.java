package cn.adinall.ai.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * <b>功能</b>：保存 Agent 装配和执行过程使用的内部错误码。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * armory / gateway -> AppException(error code) -> case
 *                  -> GlobalExceptionHandler -> HTTP error
 * </pre>
 * <p><b>案例</b>：找不到 agentId=9999 的装配结果 -> AppException(E0001)，HTTP 入口使用统一错误体。</p>
 * <b>设计要点</b>
 * <ul><li>早期 HTTP 200 + Response 外壳及通用 Agent 路由已删除。</li>
 * <li>保留内部装配错误码，避免改变既有网关与日志判码。</li></ul>
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    NOT_FOUND_METHOD("0003", "不存在的方法"),

    E0001("E0001", "智能体ID不存在"),
    E0002("E0002", "智能体MCP配置不在可加载范围"),

    ;

    private String code;
    private String info;

}
