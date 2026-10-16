package cn.adinall.ai.domain.identity.model.valobj;

/** 请求认证后的可信身份，不接收客户端指定的用户ID。 */
public record AccountPrincipalVO(long id, String userNo, String account) { }
