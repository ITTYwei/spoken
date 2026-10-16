package cn.adinall.ai.domain.learning;

import cn.adinall.ai.domain.learning.service.level.LearningEvidencePolicy;
import cn.adinall.ai.domain.learning.model.valobj.*;
import org.junit.Test;
import java.time.Instant;
import static org.junit.Assert.*;

public class LearningEvidencePolicyTest {
    private LearningEvidenceVO evidence(String text, double confidence, boolean independent) {
        return LearningEvidencePolicy.evaluate("user:7","s",10L,text,"ANSWERED","L6",confidence,independent,Instant.EPOCH);
    }
    @Test public void shortAndMixedAnswersCannotProveAdvancedExpression() {
        assertEquals(EnglishLevelEnum.L3,evidence("I like tea",.95,true).candidate());
        assertEquals(EnglishLevelEnum.L4,evidence("I really like to drink tea every single morning",.95,true).candidate());
        assertEquals(EnglishLevelEnum.L3,evidence("我喜欢喝茶 I really like to drink tea every single morning",.95,true).candidate());
    }
    @Test public void lowConfidenceDependentAndEmptyAnswersAreNotUpgradeEvidence() {
        assertFalse(evidence("I like tea",.84,true).eligible());
        assertFalse(evidence("I like tea",.95,false).eligible());
        assertFalse(evidence(null,.95,true).eligible());
        assertFalse(evidence("Yes",.95,true).eligible());
        assertTrue(evidence("I like tea",.95,true).eligible());
    }
}
