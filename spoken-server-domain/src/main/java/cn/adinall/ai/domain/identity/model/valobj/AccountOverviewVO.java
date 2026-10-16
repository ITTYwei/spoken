package cn.adinall.ai.domain.identity.model.valobj;

import cn.adinall.ai.domain.identity.model.valobj.AccountProfileVO;
import cn.adinall.ai.domain.identity.model.valobj.PracticePreferenceVO;
import cn.adinall.ai.domain.learning.model.valobj.AbilityDescriptionVO;

/** 我的页面所需的资料、能力描述与偏好。 */
public record AccountOverviewVO(AccountProfileVO profile, AbilityDescriptionVO english, java.util.List<PracticePreferenceVO> preferences) { }
