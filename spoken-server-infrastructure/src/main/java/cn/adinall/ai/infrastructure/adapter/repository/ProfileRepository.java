package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.account.adapter.repository.IProfileRepository;
import cn.adinall.ai.domain.account.model.valobj.AccountProfileVO;
import cn.adinall.ai.infrastructure.dao.IAccountProfileDao;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 资料仓储：负责模型转换和画像修改事务，不在仓储内拼接SQL。 */
@Repository
public class ProfileRepository implements IProfileRepository {
    private final IAccountProfileDao dao;
    public ProfileRepository(IAccountProfileDao dao) { this.dao = dao; }
    @Override public AccountProfileVO get(long id) {
        var row = dao.profile(id);
        if (row == null) throw new IllegalStateException("账户资料不存在");
        return new AccountProfileVO(row.getUserNo(), row.getAccount(), row.getNickname(), row.getAvatarUrl(),
                row.getAvatarId(), row.getVoice(), row.isSubtitles(), row.getTranslation());
    }
    @Override public void nickname(long id, String name) { dao.nickname(id, name); }
    @Override public void display(long id, boolean subtitles, String translation) { dao.display(id, subtitles, translation); }
    @Override public void selection(long id, String avatar, String voice) { dao.selection(id, avatar, voice); }

    /** 手动管理事实和推进遗忘边界必须一起提交，避免删除后旧回答重新写入。 */
    @Override @Transactional
    public void manageFact(long id, String key, String type, String content) {
        String subject = "user:" + id;
        dao.ensureLearner(subject);
        dao.lockLearner(subject);
        // 包含正在生成的用户回合，且Mapper只允许边界前进，不能被并发操作倒退。
        long turn = dao.latestTurn(id);
        dao.resetContext(subject, turn);
        if (content == null) dao.deleteFact(subject, key);
        // 用户编辑就是明确事实，不伪装成练习回答，也不影响能力证据。
        else dao.saveFact(subject, key, type, content, turn);
    }
}
