package cn.adinall.ai.trigger.http;

import cn.adinall.ai.domain.account.model.valobj.SubtitleTranslationVO;

import cn.adinall.ai.domain.account.service.IConversationTranslationService;
import org.springframework.web.bind.annotation.*;

/** 对话字幕翻译入口；sessionId由统一拦截器检查归属，服务层再次校验。 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}")
public class ConversationTranslationController {
    private final IConversationTranslationService translations;
    public ConversationTranslationController(IConversationTranslationService translations) { this.translations = translations; }
    @PostMapping("/turns/{turnId}/translation") public SubtitleTranslationVO translate(@PathVariable String sessionId, @PathVariable long turnId) {
        return translations.translate(sessionId, turnId);
    }
    /** 首句使用独立入口，仍由拦截器和领域服务双重检查会话归属。 */
    @PostMapping("/opening/translation") public SubtitleTranslationVO opening(@PathVariable String sessionId) {
        return translations.translateOpening(sessionId);
    }
}
