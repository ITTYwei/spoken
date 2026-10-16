package cn.adinall.ai.cases.overview;

import java.util.List;

import cn.adinall.ai.cases.overview.IProfileService;

import cn.adinall.ai.cases.security.ICurrentActor;
import cn.adinall.ai.domain.identity.service.security.ProfilePolicy;
import cn.adinall.ai.domain.identity.adapter.repository.IProfileRepository;
import cn.adinall.ai.domain.identity.model.valobj.AccountProfileVO;
import cn.adinall.ai.domain.identity.model.valobj.PracticePreferenceVO;
import cn.adinall.ai.domain.learning.model.valobj.AbilityDescriptionVO;
import cn.adinall.ai.domain.identity.model.valobj.AccountOverviewVO;

import cn.adinall.ai.domain.identity.adapter.port.IAccountLearningOverviewPort;
import cn.adinall.ai.domain.learning.model.valobj.*;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * <b>功能</b>：编排账号资料、明确偏好及学习能力描述，形成“我的”页面结果。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * trusted account -> profile + explicit facts + recent learning evidence
 *                 -> learning description policy -> account overview
 * profile edit -> domain validation -> transactional repository -> refreshed overview
 * </pre>
 * <p><b>案例</b>：用户修改昵称为小明 -> 保存资料，页面仍根据真实学习证据显示“正在了解”。</p>
 * <b>设计要点</b>
 * <ul><li>跨能力展示在 Case 组合，能力规则由 Learning 持有。</li>
 * <li>异步工具使用捕获账号，不从模型参数或异步 ThreadLocal 取得身份。</li></ul>
 */
@Service
public class ProfileService implements IProfileService {
    /**
     * 账户资料及事实事务仓储，更正/删除同步推进既有遗忘边界。
     */
    private final IProfileRepository profiles;
    private final ICurrentActor actors;
    /**
     * 当前明确事实和近期能力证据的只读查询投影。
     */
    private final IAccountLearningOverviewPort learning;

    /** 创建 {@code ProfileService}，初始化其依赖与状态。 */
    public ProfileService(IProfileRepository profiles, IAccountLearningOverviewPort learning, ICurrentActor actors) {
        this.actors=actors;
        this.profiles = profiles;
        this.learning = learning;
    }

    /** 读取当前登录账号的个人信息与学习概况。 */
    public AccountOverviewVO me() {
        return meFor(actors.require().id());
    }

    /**
     * 异步工具只访问捕获账号，明确事实的过期和确认状态继续沿用既有仓储规则。
     */
    @Override
    public AccountOverviewVO meFor(long id) {
        String subject = "user:" + id;
        // 只展示用户明确陈述的目标和兴趣；不把模型猜测作为个人事实。
        List<PracticePreferenceVO> facts = learning.explicitPreferences(subject, Instant.now());
        return new AccountOverviewVO(profiles.get(id), cn.adinall.ai.domain.learning.service.level.AbilityDescriptionPolicy.describe(learning.descriptionEvidence(subject, Instant.now().minus(Duration.ofDays(30)))), facts);
    }

    /** 更新账号昵称。 */
    public void nickname(String value) {
        profiles.nickname(actors.require().id(), ProfilePolicy.nickname(value));
    }

    /** 更新字幕显示和翻译偏好。 */
    public void display(boolean subtitles, String translation) {
        profiles.display(actors.require().id(), subtitles, ProfilePolicy.translation(translation));
    }

    /**
     * 仅允许管理目标和兴趣，避免写入内部能力或教学规则。content为空表示删除。
     */
    public void fact(String key, String type, String content) {
        factFor(actors.require().id(), key, type, content);
    }

    /**
     * 异步工具和 HTTP 共用事实规则，捕获身份不依赖 ThreadLocal；事务仍由仓储统一执行。
     */
    @Override
    public void factFor(long accountId, String key, String type, String content) {
        ProfilePolicy.fact(key, type, content);
        profiles.manageFact(accountId, key, type, content);
    }

    /**
     * 只读取捕获账号的已保存伙伴，不能用模型参数任意查询其他资料。
     */
    @Override
    public AccountProfileVO profileFor(long accountId) {
        return profiles.get(accountId);
    }
}
