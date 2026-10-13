package cn.adinall.ai.domain.agent.service.memory;

import cn.adinall.ai.domain.agent.service.ILongTermMemoryService;
import cn.adinall.ai.domain.agent.adapter.repository.ILongTermMemoryRepository;
import cn.adinall.ai.domain.agent.model.entity.LongTermMemoryEntity;
import org.springframework.stereotype.Service;
import org.slf4j.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 功能：用明确陈述规则管理学习偏好，沿用参考项目 memory/service/repository 结构。
 * <p>体系架构 / 运行过程：</p>
 * <pre>practice gate -> explicit preference/goal/language -> replace key -> relevant top3</pre>
 * <p>案例：“我不再喜欢咖啡”覆盖旧偏好；“忘掉咖啡”删除该键。</p>
 * <p>设计要点：</p>
 * <ul><li>仅保存明确的简单陈述，复杂或条件表达不推断。</li>
 * <li>不保存“今天很累”等临时状态；关键词匹配失败宁可不召回。</li></ul>
 */
@Service
public class LongTermMemoryService implements ILongTermMemoryService {
    /** 事实存储端口。 */ private final ILongTermMemoryRepository repository;
    /** 降级日志，不包含私有原话。 */ private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);
    /** 只匹配整条简单偏好，避免把引用或角色扮演变为事实。 */
    private static final Pattern PREFERENCE = Pattern.compile("^(?:记住[，,：:]?\\s*)?(?:我(?:不再喜欢|不喜欢|喜欢)|I (?:no longer like|don't like|do not like|like))\\s*([\\p{L} ]{1,40})[。.!！]?$", Pattern.CASE_INSENSITIVE);
    public LongTermMemoryService(ILongTermMemoryRepository repository) { this.repository = repository; }
    @Override public void record(String subject, String session, Long turn, String text, boolean practice, Instant now) {
        if (text == null || text.isBlank() || text.length() > 300) return;
        String input = text.trim();
        try {
            if (input.equals("忘掉所有记忆") || input.equalsIgnoreCase("forget all memories")) {
                repository.forgetAllAndHistory(subject,turn); return;
            }
            if (input.startsWith("忘掉") || input.toLowerCase(Locale.ROOT).startsWith("forget ")) {
                String topic = input.startsWith("忘掉") ? input.substring(2).trim() : input.substring(7).trim();
                if(!topic.isBlank()) repository.forgetMatchingAndHistory(subject,topic.toLowerCase(Locale.ROOT),turn);
                return;
            }
            if (practice) return;
            var matcher = PREFERENCE.matcher(input);
            String key = null, type = null;
            if (matcher.matches()) { key = "preference:" + matcher.group(1).trim().toLowerCase(Locale.ROOT); type = "PREFERENCE"; }
            else if (input.startsWith("我的学习目标是") || input.toLowerCase(Locale.ROOT).startsWith("my learning goal is ")) { key="learning_goal"; type="GOAL"; }
            else if (input.equals("以后请用中文帮助我") || input.equalsIgnoreCase("please help me in Chinese")) { key="language_support"; type="LANGUAGE"; }
            if (key != null) repository.save(new LongTermMemoryEntity(subject,key,type,input,session,turn,"EXPLICIT",now,
                    now.plus("LANGUAGE".equals(type)?30:180,ChronoUnit.DAYS)));
        } catch (RuntimeException e) { log.warn("记忆写入降级 type={}",e.getClass().getSimpleName()); }
    }
    @Override public List<LongTermMemoryEntity> recall(String subject, String topic, Instant now) {
        try {
            String query = topic == null ? "" : topic.toLowerCase(Locale.ROOT);
            return repository.active(subject,now).stream().filter(m -> "EXPLICIT".equals(m.confirmation()))
                    .filter(m -> "LANGUAGE".equals(m.type()) || relevant(query,m))
                    .sorted(Comparator.comparing(LongTermMemoryEntity::confirmedAt).reversed()).limit(3).toList();
        } catch (RuntimeException e) { log.warn("记忆召回降级 type={}",e.getClass().getSimpleName()); return List.of(); }
    }
    private boolean relevant(String query, LongTermMemoryEntity memory) {
        if ("GOAL".equals(memory.type())) return query.contains("学习") || query.contains("learn") || query.contains("goal");
        String key = memory.memoryKey().replace("preference:","");
        return !key.isBlank() && query.contains(key);
    }
}
