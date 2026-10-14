package cn.adinall.ai.domain.account.model.valobj;

/** 登录结果；令牌仅返回给当前客户端，服务端只持久化摘要。 */
public record LoginResultVO(String token, java.time.Instant expiresAt, String userNo, String account) { }
