package cn.adinall.ai.infrastructure.adapter.port;

import com.fasterxml.jackson.databind.JsonNode;

import cn.adinall.ai.domain.identity.model.valobj.VoiceVO;

import cn.adinall.ai.domain.identity.adapter.port.IHomeMediaPort;
import cn.adinall.ai.infrastructure.config.OpenTalkingProperties;
import cn.adinall.ai.infrastructure.gateway.OpenTalkingGateway;
import org.springframework.stereotype.Component;
import java.util.*;

/** 首页仅提供当前TTS引擎的系统音色，试听与聊天使用相同provider。 */
@Component
public class OpenTalkingHomeMediaAdapter implements IHomeMediaPort {
    private final OpenTalkingGateway gateway;
    private final OpenTalkingProperties properties;
    /** 创建 {@code OpenTalkingHomeMediaAdapter}，初始化其依赖与状态。 */
    public OpenTalkingHomeMediaAdapter(OpenTalkingGateway gateway, OpenTalkingProperties properties) {
        this.gateway = gateway; this.properties = properties;
    }
    /** 读取当前媒体提供方的配置。 */
    private String provider() {
        String value = properties.getDefaultTtsProvider();
        return value == null || value.isBlank() ? "edge" : value;
    }
    /** 查询当前提供方可用的音色。 */
    public List<VoiceVO> voices() {
        List<VoiceVO> result = new ArrayList<>();
        JsonNode rows = gateway.voices(provider()).path("items");
        for (JsonNode row : rows) {
            // 上游服务自身有账号数据；仅公开系统音色，忽略其user_id和自定义资产。
            if (!"system".equals(row.path("source").asText()) || !provider().equals(row.path("provider").asText())) continue;
            String id = row.path("voice_id").asText();
            if (!id.isBlank()) result.add(new VoiceVO(id, row.path("display_label").asText(id), provider()));
        }
        return result;
    }
    /** 获取指定音色的试听音频。 */
    public byte[] preview(String voice) {
        // 固定简短试听文案，客户端不能把试听接口当作任意长文本TTS工具。
        return gateway.previewVoice(provider(), voice, "Hello! Let's practise English together. 你好，我们一起练习英语吧。");
    }
}
