package cn.adinall.ai.domain.learner;

import cn.adinall.ai.domain.learner.model.valobj.*;
import cn.adinall.ai.domain.learner.service.level.LearnerProgressService;
import org.junit.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

/**
 * 功能：验证升级需要跨场独立回答，不把几句高分变为全英文。
 * <p>体系架构 / 运行过程：</p>
 * <pre>evidence window -> pure promotion policy -> at most one level</pre>
 * <p>案例：20条但只有两场 -> 不升级。</p>
 * <p>设计要点：</p>
 * <ul><li>重复turn不计数。</li><li>最近场次必须一致支持。</li></ul>
 */
public class LearnerProgressTest {
    private List<LearningEvidenceVO> evidence(int count,int sessions,boolean eligible) {
        List<LearningEvidenceVO> result=new ArrayList<>();
        for(int i=0;i<count;i++) result.add(new LearningEvidenceVO("device:d","s"+(i%sessions),(long)i,"ANSWERED",EnglishLevelEnum.L6,.95,eligible,Instant.now().minusSeconds(count-i)));
        return result;
    }
    @Test public void aFewGoodAnswersNeverUpgrade() { assertEquals(EnglishLevelEnum.L2,LearnerProgressService.promotion(EnglishLevelEnum.L2,evidence(4,3,true))); }
    @Test public void oneLongSessionDoesNotReplaceThreeSessions() { assertEquals(EnglishLevelEnum.L2,LearnerProgressService.promotion(EnglishLevelEnum.L2,evidence(100,1,true))); }
    @Test public void sufficientEvidenceMovesOnlyOneLevel() { assertEquals(EnglishLevelEnum.L3,LearnerProgressService.promotion(EnglishLevelEnum.L2,evidence(21,3,true))); }
    @Test public void readingEvidenceDoesNotUpgrade() { assertEquals(EnglishLevelEnum.L2,LearnerProgressService.promotion(EnglishLevelEnum.L2,evidence(100,3,false))); }
    @Test public void duplicatedTurnIdsDoNotCountTwice() {
        var source=evidence(10,3,true); var duplicated=new ArrayList<>(source); duplicated.addAll(source);
        assertEquals(EnglishLevelEnum.L2,LearnerProgressService.promotion(EnglishLevelEnum.L2,duplicated));
    }
    @Test public void refusalsAreNotAbilityEvidence() {
        var source=evidence(21,3,true).stream().map(e -> new LearningEvidenceVO(e.subjectKey(),e.sessionId(),e.turnId(),"DECLINED",e.candidate(),e.confidence(),true,e.observedAt())).toList();
        assertEquals(EnglishLevelEnum.L2,LearnerProgressService.promotion(EnglishLevelEnum.L2,source));
    }
}
