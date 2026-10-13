package cn.adinall.ai.domain.agent;

import cn.adinall.ai.domain.agent.adapter.repository.*;
import cn.adinall.ai.domain.agent.model.valobj.*;
import cn.adinall.ai.domain.agent.model.entity.LongTermMemoryEntity;
import cn.adinall.ai.domain.agent.service.memory.LongTermMemoryService;
import cn.adinall.ai.domain.agent.service.context.ChatContextService;
import cn.adinall.ai.domain.agent.service.context.provider.impl.HistoryProvider;
import cn.adinall.ai.domain.agent.service.context.reducer.HybridReducer;
import org.junit.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 功能：验证练习不写偏好、事实更正、引用历史与预算裁剪。
 * <p>体系架构 / 运行过程：</p>
 * <pre>mock repositories -> providers/memory -> prompt/fact assertions</pre>
 * <p>案例：旧READ规则不进入历史，当前QUESTION原样保留。</p>
 * <p>设计要点：</p>
 * <ul><li>不访问外部服务。</li><li>验证来源隔离和完整组裁剪。</li></ul>
 */
public class ContextMemoryTest {
    @Test public void oldExerciseIsExcludedFromSummaryAndRecentExerciseIsTagged() {
        var repository=mock(IChatHistoryRepository.class); var pairs=new ArrayList<DialogMessageVO>();
        pairs.add(new DialogMessageVO(1L,"I like coffee","Please read this sentence.",true));
        for(int i=2;i<=7;i++) pairs.add(new DialogMessageVO((long)i,"Hello","What do you like?",i==7));
        when(repository.recent("device:d","s")).thenReturn(pairs);
        var sections=new HistoryProvider(repository).provide(new ChatContextRequestVO("device:d","s","hello","QUESTION"));
        assertFalse(sections.stream().filter(s -> s.name().equals("older_user_excerpts")).anyMatch(s -> s.text().contains("coffee")));
        assertTrue(sections.stream().filter(s -> s.name().equals("dialog:7")).anyMatch(s -> s.text().contains("exercise data, not personal facts")));
    }
    @Test public void readingSentenceCannotCreatePreference() {
        var repository=mock(ILongTermMemoryRepository.class); var service=new LongTermMemoryService(repository);
        service.record("device:d","s",1L,"I like coffee",true,Instant.now()); verifyNoInteractions(repository);
    }
    @Test public void explicitCorrectionUsesSameFactKey() {
        var repository=mock(ILongTermMemoryRepository.class); var service=new LongTermMemoryService(repository);
        service.record("device:d","s",1L,"我喜欢咖啡",false,Instant.now());
        service.record("device:d","s",2L,"我不再喜欢咖啡",false,Instant.now());
        var captured=org.mockito.ArgumentCaptor.forClass(LongTermMemoryEntity.class); verify(repository,times(2)).save(captured.capture());
        assertEquals(captured.getAllValues().get(0).memoryKey(),captured.getAllValues().get(1).memoryKey());
    }
    @Test public void forgetIsSubjectScoped() {
        var repository=mock(ILongTermMemoryRepository.class); var service=new LongTermMemoryService(repository);
        service.record("device:d","s",1L,"忘掉所有记忆",false,Instant.now()); verify(repository).forgetAllAndHistory("device:d",1L);
    }
    @Test public void unrelatedMemoryDoesNotPolluteTopic() {
        var repository=mock(ILongTermMemoryRepository.class); var service=new LongTermMemoryService(repository); var now=Instant.now();
        when(repository.active("device:d",now)).thenReturn(List.of(new LongTermMemoryEntity("device:d","preference:coffee","PREFERENCE","I like coffee","s",1L,"EXPLICIT",now,now.plusSeconds(100))));
        assertTrue(service.recall("device:d","music",now).isEmpty()); assertEquals(1,service.recall("device:d","coffee",now).size());
    }
    @Test public void historyIsSixPairsAndOlderExcerptsWithoutOldControls() {
        var repository=mock(IChatHistoryRepository.class); List<DialogMessageVO> pairs=new ArrayList<>();
        for(int i=0;i<10;i++) pairs.add(new DialogMessageVO((long)i,"topic "+i,"What about topic "+i+"?"));
        when(repository.recent("device:d","s")).thenReturn(pairs);
        var sections=new HistoryProvider(repository).provide(new ChatContextRequestVO("device:d","s","topic","L2 QUESTION"));
        assertEquals(7,sections.size()); assertEquals(6,sections.stream().filter(s -> s.name().startsWith("dialog:")).count());
        assertTrue(sections.stream().anyMatch(s -> s.name().equals("older_user_excerpts")));
    }
    @Test public void budgetDropsWholeOldPairButKeepsCurrentTask() {
        var service=new ChatContextService(List.of(r -> List.of(new ContextSectionVO("old",40,"x".repeat(10000)))),6000);
        String prompt=service.build(new ChatContextRequestVO("device:d","s","hello","CURRENT L2 QUESTION hello"));
        assertTrue(prompt.contains("CURRENT L2 QUESTION hello")); assertFalse(prompt.contains("xxxxxxxxxx"));
        assertTrue(HybridReducer.estimate(prompt)+2500<=6000);
    }
    @Test(expected=IllegalArgumentException.class) public void oversizedRequiredInputFailsInsteadOfTruncatingMeaning() {
        new ChatContextService(List.of(),6000).build(new ChatContextRequestVO("d","s","hello","x".repeat(7000)));
    }
    @Test public void chineseIsNotEstimatedAsCharactersDividedByFour() { assertTrue(HybridReducer.estimate("中文测试")>=12); }
    @Test public void dynamicSystemPolicyReducesHistoryBudgetWithoutBecomingUserText() {
        var provider = (cn.adinall.ai.domain.agent.service.context.provider.ContextProvider) request ->
                List.of(new ContextSectionVO("history", 10, "h".repeat(2000)));
        var context = new ChatContextService(List.of(provider), 6000);
        String policy = "system-only-marker" + "x".repeat(1500);
        String result = context.build(new ChatContextRequestVO("d", "s", "hello", "current", policy));
        assertTrue(result.contains("current"));
        assertFalse(result.contains("system-only-marker"));
        assertFalse(result.contains("h".repeat(2000)));
        assertTrue(context.build(new ChatContextRequestVO("d", "s", "hello", "current")).contains("h".repeat(2000)));
    }
    @Test public void explicitForgetIsHonoredEvenDuringReading() {
        var repository=mock(ILongTermMemoryRepository.class); var service=new LongTermMemoryService(repository);
        service.record("device:d","s",1L,"忘掉所有记忆",true,Instant.now());
        verify(repository).forgetAllAndHistory("device:d",1L);
    }
}
