package cn.adinall.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 脚本轨：让数字人**原样念出**这段文字，不经过 LLM。
 *
 * 与 {@link SpeakRequestDTO} 的分工：
 * <ul>
 *   <li>{@code SpeakRequestDTO.text} 是「用户说的话」，会先让 LLM 生成回复；</li>
 *   <li>{@code SayRequestDTO.text} 就是数字人要说的话，直通 TTS。</li>
 * </ul>
 *
 * 开场白、题目、讲解、判分反馈这类内容已定的话走这里：可预写、可测试、延迟低。
 */
public record SayRequestDTO(@NotBlank String text, String voice, String commandId) {
}
