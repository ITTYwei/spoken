package cn.adinall.ai.infrastructure.adapter.repository;

import java.util.Map;

import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IOpeningEncounterRepository;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningEncounterVO;
import cn.adinall.ai.infrastructure.dao.IOpeningFactsDao;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Date;

/**
 * 功能：保存独立于会话和发言的主体介绍记录。
 * <p>体系架构 / 运行过程：</p>
 * <pre>conversation media done -> scoped upsert -> next encounter lookup</pre>
 * <p>案例：FIRST完成且没有用户发言 -> 下场仍得到 introduced=true。</p>
 * <p>设计要点：</p>
 * <ul><li>不从空会话推断听过介绍。</li><li>旧时间事件不覆盖新数字人记录。</li></ul>
 */
@Repository
public class OpeningEncounterRepository implements IOpeningEncounterRepository {
    /** 主体隔离的首句DAO。 */ private final IOpeningFactsDao dao;
    /** 创建 {@code OpeningEncounterRepository}，初始化其依赖与状态。 */
    public OpeningEncounterRepository(IOpeningFactsDao dao) { this.dao=dao; }
    /** 读取主体最近实际展示的开场和形象记录。 */
    public OpeningEncounterVO read(String subject) {
        Map<String, Object> row=dao.encounter(subject);
        return row==null ? OpeningEncounterVO.unseen() : new OpeningEncounterVO(true,row.get("introducedAt")!=null,
                (String)row.get("avatarId"),(String)row.get("avatarName"));
    }
    /** 记录已实际展示的开场与形象信息，供下次开场决策使用。 */
    public void recordShown(String subject,String session,String avatar,String name,boolean intro,Instant at) {
        dao.shown(subject,session,avatar,name,intro,Date.from(at));
    }
}
