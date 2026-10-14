package cn.adinall.ai.infrastructure.adapter.port;

import cn.adinall.ai.domain.account.model.valobj.VoiceVO;

import cn.adinall.ai.domain.account.adapter.port.IHomeMediaPort;
import cn.adinall.ai.infrastructure.config.OpenTalkingProperties;
import cn.adinall.ai.infrastructure.gateway.OpenTalkingGateway;
import org.springframework.stereotype.Component;
import java.util.*;

/** 首页仅提供当前TTS引擎的系统音色，试听与聊天使用相同provider。 */
@Component
public class OpenTalkingHomeMediaAdapter implements IHomeMediaPort {
    private final OpenTalkingGateway gateway;
    private final OpenTalkingProperties properties;
    public OpenTalkingHomeMediaAdapter(OpenTalkingGateway gateway, OpenTalkingProperties properties) {
        this.gateway = gateway; this.properties = properties;
    }
    private String provider() {
        String value = properties.getDefaultTtsProvider();
        return value == null || value.isBlank() ? "edge" : value;
    }
    public List<VoiceVO> voices() {
        List<VoiceVO> result = new ArrayList<>();
        var rows = gateway.voices(provider()).path("items");
        for (var row : rows) {
            // 上游服务自身有账号数据；仅公开系统音色，忽略其user_id和自定义资产。
            if (!"system".equals(row.path("source").asText()) || !provider().equals(row.path("provider").asText())) continue;
            String id = row.path("voice_id").asText();
            if (!id.isBlank()) result.add(new VoiceVO(id, row.path("display_label").asText(id), provider()));
        }
        return result;
    }
    public byte[] preview(String voice) {
        // 固定简短试听文案，客户端不能把试听接口当作任意长文本TTS工具。
        return gateway.previewVoice(provider(), voice, "Hello! Let's practise English together. 你好，我们一起练习英语吧。");
    }
}
