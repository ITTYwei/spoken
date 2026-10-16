package cn.adinall.ai.domain.learning;

import cn.adinall.ai.domain.learning.model.valobj.EnglishLevelSettingVO;

import cn.adinall.ai.trigger.http.LearnerLevelController;
import cn.adinall.ai.api.dto.EnglishLevelRequestDTO;
import cn.adinall.ai.domain.identity.model.valobj.AccountPrincipalVO;
import cn.adinall.ai.domain.identity.support.AccountContext;
import cn.adinall.ai.domain.learning.adapter.repository.ILearnerProgressRepository;
import cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum;
import cn.adinall.ai.domain.learning.service.level.LearnerProgressService;
import cn.adinall.ai.infrastructure.adapter.repository.LearnerProgressRepository;
import cn.adinall.ai.infrastructure.dao.ITeachingContextDao;
import org.junit.*;
import java.time.Instant;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 手动陪练设置的业务边界：新会话优先、自动能力不变、恢复自动、认证主体隔离和非法值。 */
public class ManualEnglishLevelTest {
    @After public void clearIdentity() { AccountContext.clear(); }
    @Test public void manualLevelWinsForNewSessionWithoutPromotion() {
        ILearnerProgressRepository repo=mock(ILearnerProgressRepository.class);when(repo.manualLevel("user:7")).thenReturn(EnglishLevelEnum.L5);
        assertEquals(EnglishLevelEnum.L5,new LearnerProgressService(repo).begin("user:7","new-session",Instant.now()));
        verify(repo,never()).recent(anyString(),any());verify(repo,never()).promote(anyString(),any(),any(),anyString());
    }
    @Test public void settingsKeepAutomaticAndManualSeparate() {
        ILearnerProgressRepository repo=mock(ILearnerProgressRepository.class);when(repo.level("user:7")).thenReturn(EnglishLevelEnum.L2);
        when(repo.manualLevel("user:7")).thenReturn(EnglishLevelEnum.L6);
        LearnerProgressService service=new LearnerProgressService(repo);EnglishLevelSettingVO value=service.select("user:7",EnglishLevelEnum.L6);
        assertEquals(EnglishLevelEnum.L2,value.automaticLevel());assertEquals(EnglishLevelEnum.L6,value.effectiveLevel());
        verify(repo).setManualLevel("user:7",EnglishLevelEnum.L6);verify(repo,never()).record(any());
        when(repo.manualLevel("user:7")).thenReturn(null);
        value=service.select("user:7",null);assertNull(value.manualLevel());assertEquals(EnglishLevelEnum.L2,value.effectiveLevel());
        verify(repo).setManualLevel("user:7",null);
    }
    @Test public void controllerUsesOnlyAuthenticatedSubjectAndRejectsInvalidCodes() {
        ILearnerProgressRepository repo=mock(ILearnerProgressRepository.class);LearnerLevelController useCase=new LearnerLevelController(new LearnerProgressService(repo));
        assertThrows(cn.adinall.ai.types.exception.AppException.class,()->useCase.put(new EnglishLevelRequestDTO("L2")));
        AccountContext.bind(new AccountPrincipalVO(7,"u7","account7"));
        for(String value:new String[]{"L0","L7","l2","", "AUTO"})
            assertThrows(IllegalArgumentException.class,()->useCase.put(new EnglishLevelRequestDTO(value)));
        verifyNoInteractions(repo);
        useCase.put(new EnglishLevelRequestDTO("L3"));verify(repo).setManualLevel("user:7",EnglishLevelEnum.L3);
        AccountContext.bind(new AccountPrincipalVO(8,"u8","account8"));useCase.put(new EnglishLevelRequestDTO(null));verify(repo).setManualLevel("user:8",null);
    }
    @Test public void repositoryWritesOnlyManualColumnAndUsesSqlNullForAutomatic() {
        ITeachingContextDao dao=mock(ITeachingContextDao.class);LearnerProgressRepository repo=new LearnerProgressRepository(dao);
        repo.setManualLevel("user:7",EnglishLevelEnum.L1);repo.setManualLevel("user:7",null);
        verify(dao).setManualLevel("user:7","L1");verify(dao).setManualLevel("user:7",null);
        verify(dao,never()).promote(anyString(),anyString(),anyString(),anyString());
    }
}
