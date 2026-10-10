package cn.adinall.ai.domain.conversation.model.valobj;

/**
 * 数字人形象值对象（App 形象选择界面的一个条目）
 * <p>
 * 功能：描述一个可选形象的展示信息与在线状态；由 IAvatarDriverPort.listAvatars() 产出。
 * <pre>
 *   id               形象 id，建会话时作为 CreateSessionCommandEntity.avatarId 回传
 *   name             展示名；上游没给名字时适配器用 id 兜底
 *   coverUrl         封面图地址，可为 null
 *   previewVideoUrl  预览视频地址，可为 null
 *   online           是否在线；上游拉取失败时的兜底列表里一律 true
 * </pre>
 * 数据流：
 * <pre>
 *   OpenTalking GET /avatars（字段名可能是 id / avatar_id / avatarId ...）
 *        |
 *        | OpenTalkingAvatarDriver.listAvatars：多候选字段名宽松解析
 *        v
 *   List&lt;AvatarProfileVO&gt;
 *        |
 *        | ConversationCaseImpl 不加工，接口层转成响应 DTO
 *        v
 *   App 形象选择界面
 * </pre>
 * 案例：
 * <pre>
 *   输入: 上游返回 {"avatars":[{"avatar_id":"anchor","display_name":"默认主播",
 *             "cover_url":"https://cdn/cover.png","preview_video_url":"https://cdn/preview.mp4"}]}
 *   处理: 适配器按多候选字段名逐个取值、逐个兜底
 *   输出: AvatarProfileVO{id="anchor", name="默认主播", coverUrl="https://cdn/cover.png",
 *         previewVideoUrl="https://cdn/preview.mp4", online=true}
 *   降级: 上游报错或列表为空 --> AvatarProfileVO{id="anchor", name="anchor",
 *         coverUrl=null, previewVideoUrl=null, online=true}（配置里的 fallbackAvatarIds）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>name 用 id 兜底而不是留空：App 列表里出现一行没有文字的条目，
 *       比出现 "anchor" 更让人无从选择。</li>
 *   <li>coverUrl / previewVideoUrl 允许为 null：形象列表是展示型数据，
 *       缺一张封面不该让整个列表拉不出来。</li>
 *   <li>兜底条目的 online 固定 true：拉不到上游时已经降级过一次，
 *       再显示「离线」会让用户连试都不试。</li>
 * </ul>
 */
public record AvatarProfileVO(
        String id,
        String name,
        String coverUrl,
        String previewVideoUrl,
        boolean online
) {
}