package cn.adinall.ai.domain.identity.adapter.port;

import cn.adinall.ai.domain.identity.model.valobj.VoiceVO;

import java.util.List;

/** 首页音色目录及独立试听端口，不创建数字人会话、不占GPU槽位。 */
public interface IHomeMediaPort {
    /** 查询当前提供方可用的音色。 */
    List<VoiceVO> voices();
    /** 获取指定音色的试听音频。 */
    byte[] preview(String voice);
}
