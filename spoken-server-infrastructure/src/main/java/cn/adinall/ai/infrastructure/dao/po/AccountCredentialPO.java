package cn.adinall.ai.infrastructure.dao.po;

import lombok.Data;

/** 账户及凭证查询的数据库投影；仓储负责转换为领域身份和凭证。 */
@Data
public class AccountCredentialPO {
    private Long userId;
    private String userNo;
    private String account;
    private String secretHash;
}
