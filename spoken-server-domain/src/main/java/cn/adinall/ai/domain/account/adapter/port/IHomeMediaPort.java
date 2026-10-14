package cn.adinall.ai.domain.account.adapter.port;

import cn.adinall.ai.domain.account.model.valobj.VoiceVO;

import java.util.List;

/** 首页音色目录及独立试听端口，不创建数字人会话、不占GPU槽位。 */
public interface IHomeMediaPort {
    List<VoiceVO> voices();
    byte[] preview(String voice);
}
