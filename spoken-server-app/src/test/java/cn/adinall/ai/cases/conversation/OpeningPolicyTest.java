package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.opening.*;
import cn.adinall.ai.domain.identity.model.valobj.OpeningSubjectVO;
import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;
import cn.adinall.ai.domain.scenario.model.valobj.ScenarioVO;
import org.junit.Test;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

/**
 * 功能：验证主体首次介绍、可靠续聊、名称绑定、轮换及开场红线。
 * <p>体系架构 / 运行过程：</p>
 * <pre>fixed subject/history/encounter -> opening policy -> text and evidence assertions</pre>
 * <p>案例：介绍已完成但没有发言 -> DEFAULT；敏感话题 -> 普通招呼。</p>
 * <p>设计要点：</p>
 * <ul><li>不调用模型。</li><li>首次一个开始动作，再次唯一问句。</li>
 * <li>替代旧的天数、计划、连续练习优先级。</li></ul>
 */
public class OpeningPolicyTest {
    @Test public void avatarNamesUseSelectedIdAndOmitUnconfiguredOrUnsafeNames() {
        var properties=new cn.adinall.ai.infrastructure.config.OpeningAvatarProperties();
        properties.setAvatarNames(Map.of("a","小安","b","小白","bad","{name}"));
        var port=new cn.adinall.ai.infrastructure.adapter.port.ConfiguredOpeningAvatarAdapter(properties,"a");
        assertEquals("a",port.effectiveId(null)); assertEquals("小白",port.name(port.effectiveId("b")));
        assertNull(port.name("unnamed")); assertNull(port.name("bad"));
    }
    /** 固定时刻，保证轮换和日期可复现。 */ private final Instant now=Instant.parse("2026-10-13T09:00:00Z");
    /** 当前可信匿名主体。 */ private final OpeningSubjectVO subject=new OpeningSubjectVO("device",null,ZoneId.of("Asia/Shanghai"));
    /** 故意带旧场景角色名，验证不会被开场使用。 */
    private final ScenarioVO scenario=new ScenarioVO("free","Free","Talk","Amy","advanced","Hi, I'm Amy. Ready?","Cherry",
            "你好，我是Amy。准备好了吗？","Hi, I'm Amy. Ready?","Hi, I'm Amy. Ready?");
    private OpeningFactsVO facts(boolean speech,OpeningMaterialVO topic) {
        return new OpeningFactsVO(true,speech,speech?LocalDate.of(2026,10,12):null,List.of(),List.of(),topic);
    }
    private OpeningDecisionVO decide(OpeningFactsVO facts,OpeningEncounterVO encounter,String id,String name) {
        return new OpeningPolicy().decide(subject,scenario,facts,now,LearnerLevelEnum.LOW,encounter,id,name);
    }
    @Test public void firstIntroductionHasFourSentencesAndOneStartAction() {
        var result=decide(facts(false,null),OpeningEncounterVO.unseen(),"anchor",null);
        assertEquals("FIRST",result.context());
        assertEquals("你好，我是你的英语口语搭子。你可以和我随便聊聊，也可以告诉我想练什么。没听懂、不会表达，或者想换话题，都可以直接告诉我。先打个招呼吧，中文或英文都可以。",result.text());
        assertEquals(4,result.text().chars().filter(c -> c=='。').count());
        assertFalse(result.text().contains("Amy")); assertFalse(result.text().contains("anchor"));
        assertTrue(OpeningTextPolicy.valid(result.text(),"FIRST"));
    }
    @Test public void completedIntroductionSurvivesNoUserSpeech() {
        var result=decide(facts(false,null),new OpeningEncounterVO(true,true,"anchor",null),"anchor",null);
        assertEquals("DEFAULT",result.context()); assertFalse(result.text().contains("没听懂"));
        assertTrue(result.text().contains("回来") || result.text().contains("再见") || result.text().contains("又见面"));
    }
    @Test public void legacyActualConversationCountsAsReturning() {
        assertEquals("DEFAULT",decide(facts(true,null),OpeningEncounterVO.unseen(),"anchor",null).context());
    }
    @Test public void firstOverridesTopicAndDifficultyCannotForceEnglish() {
        var topic=new OpeningMaterialVO("旅行","travel","turn:1");
        var result=new OpeningPolicy().decide(subject,scenario,facts(false,topic),now,LearnerLevelEnum.HIGH,OpeningEncounterVO.unseen(),"a",null);
        assertEquals("FIRST",result.context()); assertTrue(result.text().startsWith("你好"));
    }
    @Test public void verifiedNeutralDeferredTopicOffersContinueOrChange() {
        var topic=OpeningTopicPolicy.deferred("下次聊旅行","turn:1");
        var result=decide(facts(true,topic),OpeningEncounterVO.unseen(),"anchor",null);
        assertEquals("RESUME",result.context()); assertTrue(result.text().contains("旅行"));
        assertTrue(result.text().endsWith("今天想接着聊，还是换个话题？"));
    }
    @Test public void sensitiveOrUnconfirmedTopicsNeverEnterOpening() {
        for(var topic:List.of(new OpeningMaterialVO("我的病历","medical history","turn:1"),new OpeningMaterialVO("旅行",null,"turn:1"),
                new OpeningMaterialVO("旅行","travel",""),new OpeningMaterialVO("{topic}","travel","turn:1")))
            assertEquals("DEFAULT",decide(facts(true,topic),OpeningEncounterVO.unseen(),"a",null).context());
    }
    @Test public void deferredTopicNeedsExplicitUserSentence() {
        assertNotNull(OpeningTopicPolicy.deferred("我们下次再聊音乐。","turn:1"));
        for(String text:List.of("我喜欢旅行","下次聊我的病历","引用：下次聊旅行","下次聊旅行和隐私","下次聊旅行？"))
            assertNull(text,OpeningTopicPolicy.deferred(text,"turn:1"));
        assertTrue(OpeningTopicPolicy.closed("旅行聊完了")); assertTrue(OpeningTopicPolicy.closed("不要提这个隐私话题"));
        assertTrue(OpeningTopicPolicy.closed("换个话题"));
    }
    @Test public void historyOrEncounterFailureDoesNotRepeatFullIntroduction() {
        var result=decide(OpeningFactsVO.unavailable(),OpeningEncounterVO.unseen(),"a",null);
        assertEquals("DEFAULT",result.context()); assertFalse(result.text().contains("又见面"));
        assertEquals("DEFAULT",decide(facts(false,null),OpeningEncounterVO.unavailable(),"a",null).context());
    }
    @Test public void changedAvatarGetsOnlyItsOwnShortNameIntroduction() {
        var result=decide(facts(true,null),new OpeningEncounterVO(true,true,"a","小安"),"b","小白");
        assertTrue(result.text().startsWith("嗨，我是小白。")); assertFalse(result.text().contains("小安"));
        assertFalse(result.text().contains("没听懂")); assertEquals("b",result.avatarId());
    }
    @Test public void sameAvatarAndNoNameNeverIntroduceInventedName() {
        var result=decide(facts(true,null),new OpeningEncounterVO(true,true,"a","小安"),"a","小安");
        assertFalse(result.text().contains("我是"));
        var unnamed=decide(facts(true,null),new OpeningEncounterVO(true,true,"a",null),"b",null);
        assertFalse(unnamed.text().contains("我是"));
    }
    @Test public void allTemplatesMeetActionAndTextRedLines() {
        var topic=new OpeningMaterialVO("旅行","travel","turn:1");
        for(var level:LearnerLevelEnum.values()) for(String context:List.of("FIRST","DEFAULT","RESUME")) for(int i=0;i<5;i++) {
            String text=OpeningTemplates.render(context,level,i,null,false,topic);
            assertTrue(context+"/"+level+"/"+i,OpeningTextPolicy.valid(text,context));
        }
    }
    @Test public void fiveDayRotationIsStableAndDoesNotRepeat() {
        Set<Integer> indexes=new HashSet<>(); var date=LocalDate.of(2026,10,13);
        for(int i=0;i<5;i++) indexes.add(OpeningPolicy.variant("d","free","DEFAULT",date.plusDays(i)));
        assertEquals(5,indexes.size()); assertEquals(OpeningPolicy.variant("d","free","DEFAULT",date),OpeningPolicy.variant("d","free","DEFAULT",date));
    }
    @Test public void pressureAndEmptySlotsAreRejected() {
        for(String text:List.of("你退步了，今天聊什么？","坚持打卡好吗？","Hi {name}, ready?","Hi null, ready?","What? Why?"))
            assertFalse(text,OpeningTextPolicy.valid(text));
        assertFalse(OpeningTextPolicy.valid("先打个招呼吧，中文或英文都可以。然后跟读好吗？","FIRST"));
    }
    @Test public void explicitDisabledOpeningRemainsDisabled() {
        var disabled=new ScenarioVO("off","Off","Talk",null,"beginner",null,null);
        assertNull(new OpeningPolicy().decide(subject,disabled,facts(false,null),null,now).text());
    }
}
