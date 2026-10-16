package cn.adinall.ai.trigger.http;

import cn.adinall.ai.domain.identity.model.valobj.SubtitleTranslationVO;

import cn.adinall.ai.cases.conversation.translation.IConversationTranslationService;
import org.springframework.web.bind.annotation.*;

/** 对话字幕翻译入口；sessionId由统一拦截器检查归属，服务层再次校验。 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}")
public class ConversationTranslationController {
    private final IConversationTranslationService translations;
    /** 创建 {@code ConversationTranslationController}，初始化其依赖与状态。 */
    public ConversationTranslationController(IConversationTranslationService translations) { this.translations = translations; }
    /** 校验当前账号的会话归属后翻译指定回合的助手字幕。 */
    @PostMapping("/turns/{turnId}/translation") public SubtitleTranslationVO translate(@PathVariable String sessionId, @PathVariable long turnId) {
        return translations.translate(sessionId, turnId);
    }
    /** 首句使用独立入口，仍由拦截器和领域服务双重检查会话归属。 */
    @PostMapping("/opening/translation") public SubtitleTranslationVO opening(@PathVariable String sessionId) {
        return translations.translateOpening(sessionId);
    }
}
