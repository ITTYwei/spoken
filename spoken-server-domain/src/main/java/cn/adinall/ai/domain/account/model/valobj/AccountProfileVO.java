package cn.adinall.ai.domain.account.model.valobj;

/** 基本资料、上次选择和页面呈现偏好。 */
public record AccountProfileVO(String userNo, String account, String nickname, String avatarUrl, String avatarId, String voice, boolean subtitles, String translation) { }
