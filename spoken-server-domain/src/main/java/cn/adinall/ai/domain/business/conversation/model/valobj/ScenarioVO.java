package cn.adinall.ai.domain.business.conversation.model.valobj;

/**
 * 一个口语训练场景（剧本）：练什么、目标是什么、什么难度、开场说什么、用什么嗓子说。
 *
 * <b>systemPromptHint 就是发给 LLM 的系统提示词本体</b>（不是"提示的提示"）。
 * 它<b>只作用于生成轨</b>（LLM 生成的那些话）。脚本轨（{@code openingLine} 以及以后
 * 写死的题目、判分反馈）绕过 LLM，提示词管不到它 —— 那边要说英文就得自己写成英文。
 *
 * <b>voice 是这个场景的默认音色</b>（Edge TTS 的 voice 名）。它作用在 TTS 阶段，
 * 而 TTS 是两条轨共用的，所以它是唯一能同时管住脚本轨和生成轨的旋钮：
 * 英文提示词 + 中文音色 = 英文文本用中文嗓子念，很难听。为空表示用后端默认音色。
 *
 * {@code openingLine} 是数字人主动说的第一句话（走脚本轨 say，不经 LLM）。
 * 为空表示这个场景不自动开场，直接等用户先开口。
 */
public record ScenarioVO(
        String id,
        String title,
        String goal,
        String systemPromptHint,
        String level,
        String openingLine,
        String voice
) {
}