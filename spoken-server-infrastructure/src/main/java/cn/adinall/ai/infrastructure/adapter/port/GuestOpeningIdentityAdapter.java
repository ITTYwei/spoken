package cn.adinall.ai.infrastructure.adapter.port;

import cn.adinall.ai.domain.identity.adapter.port.IOpeningIdentityPort;
import cn.adinall.ai.domain.identity.model.valobj.OpeningSubjectVO;
import java.time.ZoneId;

/**
 * 为当前匿名会话入口提供设备主体，预留认证适配替换点。
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
 *   <li>不读取账号私有资料。</li>
 * </ul>
 */
public class GuestOpeningIdentityAdapter implements IOpeningIdentityPort {
    public OpeningSubjectVO currentSubject(String deviceId) {
        return new OpeningSubjectVO(deviceId, null, ZoneId.of("Asia/Shanghai"));
    }
}
