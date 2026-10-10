package cn.adinall.ai.domain.business.conversation.adapter.repository;

import cn.adinall.ai.domain.business.conversation.model.valobj.ScenarioVO;

import java.util.List;

/** 口语训练场景（剧本）目录。现在是进程内的写死列表，以后换成读配置或场景服务都行。 */
public interface IScenarioRepository {

    List<ScenarioVO> listAll();

    ScenarioVO findById(String scenarioId);
}