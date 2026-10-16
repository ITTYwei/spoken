package cn.adinall.ai.domain.identity.adapter.port;

import cn.adinall.ai.domain.identity.model.valobj.OpeningSubjectVO;

/**
 * 提供本次请求已认证的主体，避免通过客户端 userId 读取他人资料。
 * <p>
 * 功能：提供本次请求已认证的主体，避免通过客户端 userId 读取他人资料。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   会话用例 -> 身份端口 -> 匿名 / 认证适配器
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：currentSubject(d1) -> 匿名设备 d1、Asia/Shanghai
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>端口不承担登录。</li>
 *   <li>认证实现可替换默认匿名实现。</li>
 * </ul>
 */
public interface IOpeningIdentityPort {
    /** 解析当前开场主体，区分账号身份与匿名设备。 */
    OpeningSubjectVO currentSubject(String deviceId);
}
