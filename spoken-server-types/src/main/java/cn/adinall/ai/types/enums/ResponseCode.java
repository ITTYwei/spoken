package cn.adinall.ai.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Agent 脚手架的响应码枚举（写进 Response.code 的那套码）
 * <p>
 * 功能：给「智能体装配与对话」这条链路提供统一的业务码和默认文案，
 * 由 AppException 携带、由 AgentServiceController 塞进 Response。
 * <pre>
 *   SUCCESS            "0000"   成功
 *   UN_ERROR           "0001"   未知失败（Controller 的兜底 catch 用它）
 *   ILLEGAL_PARAMETER  "0002"   非法参数（装配期配置缺失时用）
 *   NOT_FOUND_METHOD   "0003"   不存在的方法（MCP 工厂取不到方法时用）
 *   E0001              "E0001"  智能体ID不存在
 *   E0002              "E0002"  智能体MCP配置不在可加载范围
 *   getCode()          取 code 字面量
 *   getInfo()          取 info 文案
 * </pre>
 * 体系位置（本枚举与 ResponseCodeEnum 是两套码，不要混用）：
 * <pre>
 *   ChatService.createSession()
 *        | agentId 取不到 AiAgentRegisterVO
 *        v
 *   throw new AppException(ResponseCode.E0001.getCode())
 *        |
 *        v
 *   AgentServiceController 的 catch (AppException e)
 *        |
 *        v
 *   Response{code="E0001", info="E0001", data=null}   （code 与 info 都取自异常）
 * </pre>
 * 案例：
 * <pre>
 *   输入: POST /api/v1/create_session {"agentId":"9999","userId":"u-001"}
 *   处理: ChatService 里 getAiAgentRegisterVO("9999") 为 null -> 抛 AppException("E0001")
 *   输出: {"code":"E0001","info":"E0001","data":null}
 *         装配期 runner.agentName 为空: AppException("0002","非法参数") -> {"code":"0002","info":"非法参数"}
 *         其它异常: Controller 兜底 {"code":"0001","info":"未知失败","data":null}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>这套码与 {@code ResponseCodeEnum}（success / session_not_found ...）是两套：前者是 Agent
 *       脚手架的契约（配 Response 外壳、HTTP 恒 200），后者是数字人对话的契约（配 HTTP 状态码），
 *       混用会让 App 的判码分支彻底对不上。</li>
 *   <li>code 与 info 都是对外契约：App 按 code 分支、按 info 展示，改字面量等于改接口，
 *       所以这里只做「收拢魔法字符串」，不随手重命名常量。</li>
 *   <li>E0002 目前在全仓库没有调用点（预留码）：加新错误码前先确认是否已有语义相同的码，
 *       否则码会越积越多而无人清理。</li>
 *   <li>枚举常量名可以改（改名不改对外行为），但 code 不能改 —— 判等一律用常量，不要写字符串。</li>
 * </ul>
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
