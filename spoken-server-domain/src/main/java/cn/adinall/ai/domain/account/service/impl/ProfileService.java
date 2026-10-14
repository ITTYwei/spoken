package cn.adinall.ai.domain.account.service.impl;

import cn.adinall.ai.domain.account.service.IProfileService;

import cn.adinall.ai.domain.account.support.AccountContext;
import cn.adinall.ai.domain.account.adapter.repository.IProfileRepository;
import cn.adinall.ai.domain.account.model.valobj.AccountProfileVO;
import cn.adinall.ai.domain.account.model.valobj.PracticePreferenceVO;
import cn.adinall.ai.domain.account.model.valobj.AbilityDescriptionVO;
import cn.adinall.ai.domain.account.model.valobj.AccountOverviewVO;

import cn.adinall.ai.domain.account.adapter.port.IAccountLearningOverviewPort;
import cn.adinall.ai.domain.learner.model.valobj.*;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * 账户领域的资料、明确偏好管理及能力描述展示服务。
 * HTTP 入口先获取请求身份，异步 Agent 入口使用已校验的捕获账号，二者共享业务规则。
 * 学习资料通过只读投影端口获取，不直接访问其他领域仓储；事实更正事务由本领域仓储负责。
 * 注册不会采集画像，能力描述不会写入等级或消耗证据。例：证据不足时返回“正在了解”。
 */
@Service
public class ProfileService implements IProfileService {
    /** 账户资料及事实事务仓储，更正/删除同步推进既有遗忘边界。 */
    private final IProfileRepository profiles;
    /** 当前明确事实和近期能力证据的只读查询投影。 */
    private final IAccountLearningOverviewPort learning;

    public ProfileService(IProfileRepository profiles, IAccountLearningOverviewPort learning) {
        this.profiles = profiles;
        this.learning = learning;
    }

    public AccountProfileVO profile() {
        return profiles.get(AccountContext.require().id());
    }

    public AccountOverviewVO me() {
        return meFor(AccountContext.require().id());
    }
    /** 异步工具只访问捕获账号，明确事实的过期和确认状态继续沿用既有仓储规则。 */
    @Override public AccountOverviewVO meFor(long id) {
        String subject = "user:" + id;
        // 只展示用户明确陈述的目标和兴趣；不把模型猜测作为个人事实。
        var facts = learning.explicitPreferences(subject, Instant.now());
        return new AccountOverviewVO(profiles.get(id), ability(learning.descriptionEvidence(subject, Instant.now().minus(Duration.ofDays(30)))), facts);
    }

    /**
     * 近期30天、最近10场内至少3场20条有效独立回答，才产生能力描述。
     */
    public static AbilityDescriptionVO ability(List<LearningEvidenceVO> source) {
        var recent = source.stream().sorted(Comparator.comparing(LearningEvidenceVO::observedAt).reversed())
                .map(LearningEvidenceVO::sessionId).distinct().limit(10).toList();
        var valid = source.stream().filter(e -> recent.contains(e.sessionId()) && e.eligible() && "ANSWERED".equals(e.outcome())
                        && e.confidence() >= .85 && e.candidate() != null)
                .collect(java.util.stream.Collectors.toMap(LearningEvidenceVO::turnId, e -> e, (a, b) -> b)).values();
        if (valid.size() < 20 || valid.stream().map(LearningEvidenceVO::sessionId).distinct().count() < 3)
            return new AbilityDescriptionVO("learning", "正在了解");
        // 保守采用各场回答的低四分位，再取各场的最低结果；单场大量回答不能掩盖其他场的困难。
        int index = valid.stream().map(LearningEvidenceVO::sessionId).distinct().mapToInt(session -> {
            var levels = valid.stream().filter(e -> session.equals(e.sessionId())).mapToInt(e -> e.candidate().ordinal()).sorted().toArray();
            return levels[(levels.length - 1) / 4];
        }).min().orElse(1);
        String description = switch (EnglishLevelEnum.values()[index]) {
            case L1 -> "在提示帮助下，可以用单词或短语表达简单意思。";
            case L2 -> "在适当中文支持下，可以用短句回应日常问题。";
            case L3 -> "可以独立回答简单生活问题，表达日常需求。";
            case L4 -> "可以连续表达日常想法，并理解简单追问。";
            case L5 -> "可以解释原因、描述经历，并进行自然的日常交流。";
            case L6 -> "可以较完整地表达观点，并讨论较复杂的话题。";
        };
        return new AbilityDescriptionVO("ready", description);
    }

    public void nickname(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 64)
            throw new IllegalArgumentException("昵称需为1至64个字符");
        profiles.nickname(AccountContext.require().id(), value.trim());
    }

    public void display(boolean subtitles, String translation) {
        if (!Set.of("on_demand", "off").contains(translation == null ? "" : translation))
            throw new IllegalArgumentException("翻译偏好无效");
        profiles.display(AccountContext.require().id(), subtitles, translation);
    }

    /**
     * 仅允许管理目标和兴趣，避免写入内部能力或教学规则。content为空表示删除。
     */
    public void fact(String key, String type, String content) {
        factFor(AccountContext.require().id(),key,type,content);
    }

    /** 异步工具和 HTTP 共用事实规则，捕获身份不依赖 ThreadLocal；事务仍由仓储统一执行。 */
    @Override public void factFor(long accountId, String key, String type, String content) {
        boolean goal = "GOAL".equals(type) && "learning_goal".equals(key);
        boolean interest = "PREFERENCE".equals(type) && key != null && key.startsWith("preference:") && key.length() > 11 && key.length() <= 128;
        if (!goal && !interest) throw new IllegalArgumentException("仅允许管理练习目标和兴趣");
        if (content != null && (content.isBlank() || content.length() > 300))
            throw new IllegalArgumentException("偏好内容需为1至300个字符");
        profiles.manageFact(accountId, key, type, content);
    }
    /** 只读取捕获账号的已保存伙伴，不能用模型参数任意查询其他资料。 */
    @Override public AccountProfileVO profileFor(long accountId) { return profiles.get(accountId); }
}
