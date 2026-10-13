package cn.adinall.ai.domain.conversation.adapter.port;

/**
 * 功能：解析当前选定数字人的明确名称，不使用场景角色名。
 * <p>体系架构 / 运行过程：</p>
 * <pre>requested avatar -> effective ID -> configured display name / null</pre>
 * <p>案例：未配置名称的 anchor -> null，开场省略名字。</p>
 * <p>设计要点：</p>
 * <ul><li>名称与形象ID绑定。</li><li>不能把ID读作名字。</li></ul>
 */
public interface IOpeningAvatarPort {
    String effectiveId(String requestedId);
    String name(String avatarId);
}
