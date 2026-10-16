package cn.adinall.ai.domain.identity.model.valobj;

import cn.adinall.ai.domain.identity.model.valobj.AccountPrincipalVO;

/** 用于校验和并发版本比较的账户凭证。 */
public record AccountCredentialVO(AccountPrincipalVO principal, String hash) { }
