package cn.adinall.ai.domain.coaching.practice;

import cn.adinall.ai.domain.content.model.valobj.LessonBriefVO;
import cn.adinall.ai.domain.content.model.valobj.TextLessonVO;
import cn.adinall.ai.domain.content.model.valobj.TextbookCatalogVO;
import cn.adinall.ai.domain.content.model.valobj.TextbookVO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import cn.adinall.ai.domain.content.model.valobj.SpeakingPartEnum;
import cn.adinall.ai.domain.content.service.TextbookService;
import cn.adinall.ai.infrastructure.adapter.repository.ResourceTextbookRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** 以发布原始快照核对完整口语题库，而非只断言导入后的实现字段。 */
public class CompleteSpeakingCatalogTest {
    /** 项目真实资源适配器，无模型、数据库或网络依赖。 */
    private final ObjectMapper mapper = new ObjectMapper();
    private final TextbookService textbooks = new TextbookService(new ResourceTextbookRepository(mapper));

    /** 每张原题卡必须有对应课程；不允许混入口语以外的数据或丢失 Part 3 后半段题目。 */
    @Test public void everyPublishedSpeakingCardIsPreservedWithoutOtherSubjects() throws Exception {
        HashMap<String, List<String>> bySource = new HashMap<String,List<String>>();
        List<TextbookVO> books = textbooks.list();
        assertEquals(4, books.size());
        for (TextbookVO book : books) for (TextLessonVO lesson : book.lessons()) {
            bySource.computeIfAbsent(lesson.sourceCardId(), key -> new ArrayList<>()).addAll(lesson.questions());
            assertEquals(lesson, textbooks.lesson(book.id(), lesson.id()));
        }
        assertEquals(496, bySource.size());
        int[] parts = new int[4];
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ClassPathResource(
                "textbooks/bandready-speaking/speaking_cards.source.jsonl").getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank()) continue;
                JsonNode card = mapper.readTree(line);
                int part = card.get("part").asInt(); parts[part]++;
                JsonNode payload = card.get("payload_json");
                ArrayList<String> expected = new ArrayList<String>();
                if (part == 2) {
                    JsonNode cue = payload.get("cue_card"); expected.add(cue.get("topic").asText());
                    addStrings(expected,cue.get("bullets")); addStrings(expected,cue.get("rounding_off"));
                } else if (part == 3) {
                    for (JsonNode theme : payload.get("part3_themes")) {
                        addStrings(expected,theme.get("questions"));
                        if (theme.hasNonNull("counter_probe")) expected.add(theme.get("counter_probe").asText());
                    }
                } else addStrings(expected,payload.get("questions"));
                assertEquals(card.get("id").asText(),expected,bySource.get(card.get("id").asText()));
            }
        }
        assertArrayEquals(new int[]{0,280,108,108},parts);
        assertEquals(606,books.stream().mapToInt(b->b.lessons().size()).sum());
        assertEquals(SpeakingPartEnum.PART_TWO, books.get(2).part());
    }

    /** 目录不泄漏整库原题；详情保留完整原题，工具候选有界且诚实给出总量。 */
    @Test public void browsingAndAgentQueriesDoNotLoadEntireBankIntoOneTurn() throws Exception {
        List<TextbookCatalogVO> catalog = textbooks.catalog();
        String directory = mapper.writeValueAsString(catalog);
        assertFalse(directory.contains("\"questions\""));
        assertTrue(directory.contains("\"questionCount\""));
        assertEquals(606,catalog.stream().mapToInt(b->b.lessonCount()).sum());
        List<TextbookCatalogVO> candidates = textbooks.agentCatalog("我想选择课程");
        assertTrue(candidates.stream().mapToInt(b->b.lessons().size()).sum()<=12);
        assertEquals(606,candidates.stream().mapToInt(b->b.lessonCount()).sum());
        List<LessonBriefVO> target = textbooks.agentCatalog("food and cooking").get(0).lessons();
        assertTrue(target.stream().anyMatch(l->l.id().equals("food-cooking")));
        assertTrue(textbooks.lesson("bandready-daily","food-cooking").questions().size()>0);
    }

    /** 保持原题顺序，null 表示源题卡未声明可选收尾内容。 */
    private static void addStrings(List<String> target, JsonNode values) {
        if(values!=null) values.forEach(value->target.add(value.asText()));
    }
}
