package cn.adinall.ai.infrastructure.adapter.port;

import cn.adinall.ai.domain.identity.model.valobj.AccountPrincipalVO;

import cn.adinall.ai.domain.identity.adapter.port.IOpeningIdentityPort;
import cn.adinall.ai.domain.identity.model.valobj.OpeningSubjectVO;
import java.time.ZoneId;

/**
 * 请求身份适配器，类名保留兼容既有装配；账号入口捕获认证主体，内部调用可保留设备主体。
 * <p>
 * 功能：为当前匿名会话入口提供设备主体，预留认证适配替换点。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   设备请求 -> 匿名主体 -> 同设备历史
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：d1 -> userId=null, timezone=Asia/Shanghai
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>不通过 user_device 猜登录人。</li>
 *   <li>仅读取认证上下文中的用户ID，后续异步过程复用会话捕获的主体。</li>
 * </ul>
 */
public class GuestOpeningIdentityAdapter implements IOpeningIdentityPort {
    /** 解析当前开场主体，区分账号身份与匿名设备。 */
    public OpeningSubjectVO currentSubject(String deviceId) {
        // HTTP入口已认证；领域测试和非HTTP内部调用仍可保留设备主体。
        // 不通过设备绑定表猜测登录人，防止共用手机串号。
        AccountPrincipalVO principal = cn.adinall.ai.domain.identity.support.AccountContext.current();
        return new OpeningSubjectVO(deviceId, principal == null ? null : principal.id(), ZoneId.of("Asia/Shanghai"));
    }
}
