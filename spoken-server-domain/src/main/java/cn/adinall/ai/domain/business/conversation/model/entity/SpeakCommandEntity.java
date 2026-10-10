package cn.adinall.ai.domain.business.conversation.model.entity;

/**
 * 用户发言命令：text 是**用户说的话**，上游会用 LLM 生成回复再让数字人念出来。
 *
 * ⚠️ 不是「让数字人念这段文字」—— 那是 {@link SayCommandEntity}。
 * 这条区分不是咬文嚼字：两者的延迟差一个数量级（秒级 vs 亚秒级），
 * 而且 say 不依赖 LLM 可用性与措辞随机性，开场白/题目/反馈必须用 say。
 *
 * voice 为空表示用上游默认音色。
 */
public record SpeakCommandEntity(String text, String voice) {
}