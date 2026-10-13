package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.cases.conversation.impl.TeachingPreparation;
import cn.adinall.ai.domain.agent.service.*;
import cn.adinall.ai.domain.conversation.adapter.repository.ITeachingReplyRepository;
import cn.adinall.ai.domain.identity.adapter.port.IOpeningIdentityPort;
import cn.adinall.ai.domain.identity.model.valobj.OpeningSubjectVO;
import cn.adinall.ai.domain.learner.service.level.LearnerProgressService;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
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
    public static TeachingPreparation create() {
        var identity=mock(IOpeningIdentityPort.class);
        when(identity.currentSubject(anyString())).thenAnswer(i -> new OpeningSubjectVO(i.getArgument(0),null,null));
        var progress=mock(LearnerProgressService.class);
        when(progress.begin(anyString(),anyString(),any())).thenReturn(EnglishLevelEnum.L2);
        var context=mock(IChatContextService.class);
        when(context.build(any())).thenAnswer(i -> ((cn.adinall.ai.domain.agent.model.valobj.ChatContextRequestVO)i.getArgument(0)).currentInstruction());
        var ability=mock(IAbilityEvidenceService.class);
        when(ability.observe(anyString(),anyString(),anyString())).thenReturn(Mono.empty());
        return new TeachingPreparation(identity,progress,context,mock(ILongTermMemoryService.class),ability,mock(ITeachingReplyRepository.class));
    }
}
