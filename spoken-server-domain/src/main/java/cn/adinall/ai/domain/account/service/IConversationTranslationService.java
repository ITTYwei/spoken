package cn.adinall.ai.domain.account.service;

import cn.adinall.ai.domain.account.model.valobj.*;

/** 对已归属当前账号的数字人字幕提供按需翻译。 */
public interface IConversationTranslationService {
    /** 从服务端定稿回合读取原文，不接受客户端传入任意待译文本。 */
    SubtitleTranslationVO translate(String session, long turnId);
    /** 首句没有教学回合，使用服务器冻结的开场文本。 */
    SubtitleTranslationVO translateOpening(String session);
}
