package cn.adinall.ai.domain.business.conversation.model.entity;

/**
 * 脚本轨命令：让数字人**原样念出**这段文字，不经过 LLM。
 *
 * 与 {@link SpeakCommandEntity} 的分工是这块最容易搞混的一点：
 * <ul>
 *   <li>{@code SpeakCommandEntity} 的 text 是「用户说的话」，上游会用 LLM 生成回复再念；</li>
 *   <li>{@code SayCommandEntity} 的 text 就是「数字人要说的那句话」。</li>
 * </ul>
 *
 * 所以开场白、题目、讲解、判分反馈这类**内容已定**的话必须走这里：
 * 不依赖 LLM 可用性、措辞完全可控、延迟从秒级降到亚秒级（实测首块音频 ~1.0s，走 LLM 约 6.9s）。
 *
 * voice 为空表示用会话默认音色。
 */
public record SayCommandEntity(String text, String voice, String commandId) {

    /** 只关心文本时用这个：commandId 由适配器/上游自行生成。 */
    public SayCommandEntity(String text, String voice) {
        this(text, voice, null);
    }
}
