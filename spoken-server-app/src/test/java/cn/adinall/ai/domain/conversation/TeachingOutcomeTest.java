package cn.adinall.ai.domain.conversation;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.reply.ReplyPromptBuilder;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import cn.adinall.ai.infrastructure.adapter.repository.TeachingReplyRepository;
import cn.adinall.ai.infrastructure.dao.ITeachingContextDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * 教学结果类型重构的边界回归：JSON、提示词和仓储继续使用既有文本码，内部使用枚举。
 * 不访问模型或数据库；重点防止序列化变成对象/序号、持久化写入枚举对象及未知码静默降级。
 */
public class TeachingOutcomeTest {
    /** 原有协议码往返保持一致，新增枚举不会改变手机或历史记录看到的取值。 */
    @Test public void stableCodesRoundTripThroughJson() throws Exception {
        var json = new ObjectMapper();
        // 这里的字面量是既有协议契约快照，刻意不从枚举生成，避免误改协议码后测试仍自洽通过。
        assertEquals(java.util.Set.of("RESPONDED", "DECLINED", "HELP", "NO_AUDIO", "READ_NOT_ATTEMPTED",
                "READ_UNCERTAIN", "NO_EVIDENCE", "READ_PASS", "READ_RETRY", "ENGLISH_QUESTION"),
                java.util.Arrays.stream(TeachingOutcomeEnum.values()).map(TeachingOutcomeEnum::code)
                        .collect(java.util.stream.Collectors.toSet()));
        for (var outcome : TeachingOutcomeEnum.values()) {
            String encoded = json.writeValueAsString(outcome);
            assertEquals("\"" + outcome.code() + "\"", encoded);
            assertSame(outcome, json.readValue(encoded, TeachingOutcomeEnum.class));
        }
    }

    /** 决策对象对外仍用稳定字符串码；模型状态码只在元数据中，不进入 SYSTEM 规则。 */
    @Test public void decisionAndPromptKeepTextualOutcome() throws Exception {
        var json = new ObjectMapper();
        for (var outcome : TeachingOutcomeEnum.values()) {
            var decision = new TeachingDecisionVO(EnglishLevelEnum.L2, TeachingTaskVO.question(""),
                    outcome, true, "", false, false);
            assertEquals(outcome.code(), json.readTree(json.writeValueAsString(decision)).get("outcome").asText());
            assertSame(outcome, json.readValue(json.writeValueAsString(decision), TeachingDecisionVO.class).outcome());
            String turn = ReplyPromptBuilder.buildManaged("原话", decision, null, null);
            String metadata = turn.substring(turn.indexOf('>') + 1, turn.indexOf("</session_context>"));
            assertEquals(outcome.code(), json.readTree(metadata).get("previous_outcome").asText());
            assertFalse(ReplyPromptBuilder.buildSystemPolicy(decision).contains(outcome.code()));
        }
    }

    /** 仓储显式转换字符串，不能依赖 MyBatis 恰好支持某种默认枚举处理器。 */
    @Test public void repositoryWritesStableStringsForEveryOutcome() {
        var dao = mock(ITeachingContextDao.class);
        var repository = new TeachingReplyRepository(dao);
        for (var outcome : TeachingOutcomeEnum.values()) {
            var decision = new TeachingDecisionVO(EnglishLevelEnum.L2, TeachingTaskVO.question(""),
                    outcome, true, "", false, false);
            repository.begin("user:1", "session", 1L, "reply", decision);
        }
        @SuppressWarnings("unchecked")
        var captured = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(dao, times(TeachingOutcomeEnum.values().length)).beginReply(captured.capture());
        for (int i = 0; i < captured.getAllValues().size(); i++)
            assertEquals(TeachingOutcomeEnum.values()[i].code(), captured.getAllValues().get(i).get("outcome"));
    }

    /** 未知码和空结果必须显式报错，不能把拼写错误当成一次正常回答或评测缺失。 */
    @Test public void invalidOutcomeIsRejectedAtBoundary() {
        for (String invalid : new String[]{null, "", "read_pass", "NOT_DEFINED"})
            assertThrows(IllegalArgumentException.class, () -> TeachingOutcomeEnum.fromCode(invalid));
        assertThrows(NullPointerException.class, () -> new TeachingDecisionVO(
                EnglishLevelEnum.L2, TeachingTaskVO.question(""), null, true, "", false, false));
    }
}
