package cn.adinall.ai.domain.conversation.adapter.repository;

import cn.adinall.ai.domain.conversation.model.valobj.OpeningEncounterVO;
import java.time.Instant;

/**
 * 功能：按可信账号或设备主体持久保存已经完成的开场介绍。
 * <p>体系架构 / 运行过程：</p>
 * <pre>主体查询 -> 开场决策；媒体完成 -> recordShown -> 下场会话</pre>
 * <p>案例：device:d1 完成 FIRST -> d1 新建会话使用简短招呼。</p>
 * <p>设计要点：</p>
 * <ul><li>不把创建或下发成功当作听完。</li><li>账号与匿名设备使用不同键。</li></ul>
 */
public interface IOpeningEncounterRepository {
    OpeningEncounterVO read(String subject);
    void recordShown(String subject,String session,String avatarId,String avatarName,boolean introduction,Instant at);
}
