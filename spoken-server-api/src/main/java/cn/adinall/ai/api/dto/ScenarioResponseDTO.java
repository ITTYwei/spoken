package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 一个口语训练场景（剧本）：练什么、目标是什么、什么难度、开场说什么。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenarioResponseDTO(
        String id,
        String title,
        String goal,
        String systemPromptHint,
        String level,
        /** 数字人主动说的第一句话；为空表示该场景不自动开场。 */
        String openingLine,
        /** 该场景的默认 TTS 音色（Edge TTS voice 名）；为空表示用后端默认音色。 */
        String voice
) {
}