package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.domain.coaching.practice.model.aggregate.PracticeAggregate;
import cn.adinall.ai.domain.coaching.practice.service.PracticeService;
import cn.adinall.ai.domain.companion.service.IChatContextService;

import cn.adinall.ai.domain.assessment.expression.service.IAbilityEvidenceService;
import cn.adinall.ai.cases.teaching.TeachingPreparation;
import cn.adinall.ai.domain.companion.service.*;
import cn.adinall.ai.domain.coaching.teaching.adapter.repository.ITeachingReplyRepository;
import cn.adinall.ai.domain.identity.adapter.port.IOpeningIdentityPort;
import cn.adinall.ai.domain.identity.model.valobj.OpeningSubjectVO;
import cn.adinall.ai.domain.learning.service.level.LearnerProgressService;
import cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum;
import reactor.core.publisher.Mono;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 功能：提供无外部依赖的真实教学编排，用于case回归测试。
 * <p>体系架构 / 运行过程：</p>
 * <pre>mock identity/storage/model observation -> real teaching policy</pre>
 * <p>案例：任何设备从L2开始，当前规则直接送入mock Agent。</p>
 * <p>设计要点：</p>
 * <ul><li>不模拟任务策略本身。</li><li>只替换外部端口。</li></ul>
 */
public final class TeachingTestFixture {
    private TeachingTestFixture() {}
    public static TeachingPreparation create(TeachingService teachingStates) {
        IOpeningIdentityPort identity=mock(IOpeningIdentityPort.class);
        when(identity.currentSubject(anyString())).thenAnswer(i -> new OpeningSubjectVO(i.getArgument(0),null,null));
        LearnerProgressService progress=mock(LearnerProgressService.class);
        when(progress.begin(anyString(),anyString(),any())).thenReturn(EnglishLevelEnum.L2);
        IChatContextService context=mock(IChatContextService.class);
        when(context.build(any())).thenAnswer(i -> ((cn.adinall.ai.domain.companion.model.valobj.ChatContextRequestVO)i.getArgument(0)).currentInstruction());
        IAbilityEvidenceService ability=mock(IAbilityEvidenceService.class);
        when(ability.observe(anyString(),anyString(),anyString())).thenReturn(Mono.empty());
        PracticeService practice=mock(cn.adinall.ai.domain.coaching.practice.service.PracticeService.class);
        PracticeAggregate state = new cn.adinall.ai.domain.coaching.practice.model.aggregate.PracticeAggregate(null,null,null);
        when(practice.state(anyString())).thenReturn(state);
        return new TeachingPreparation(identity,progress,context,mock(ILongTermMemoryService.class),ability,mock(ITeachingReplyRepository.class),
                practice,
                mock(cn.adinall.ai.domain.content.service.TextbookService.class),
                mock(cn.adinall.ai.domain.coaching.conversation.adapter.repository.IOpeningHistoryRepository.class), CaseTestFixture.actors(), teachingStates);
    }
}
