package cn.adinall.ai.infrastructure.dao.po;

import lombok.Data;

/** app_user、user_identity和user_profile的资料查询投影，不包含画像业务规则。 */
@Data
public class AccountProfilePO {
    private String userNo;
    private String account;
    private String nickname;
    private String avatarUrl;
    private String avatarId;
    private String voice;
    private boolean subtitles;
    private String translation;
}
