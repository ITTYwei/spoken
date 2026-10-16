package cn.adinall.ai.cases.security;

import cn.adinall.ai.domain.identity.model.valobj.AccountPrincipalVO;

/** 输入适配器提供已认证主体；异步任务应捕获返回值，不读取请求线程上下文。 */
public interface ICurrentActor {
    /** 取得已认证主体；未登录时拒绝调用。 */
    AccountPrincipalVO require();
}
