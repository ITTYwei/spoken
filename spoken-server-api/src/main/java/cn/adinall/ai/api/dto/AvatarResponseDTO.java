package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 数字人形象（GET /api/v1/catalog/avatars 的列表元素）
 * <p>
 * 功能：告诉 App「这个后端现在能演哪些形象」，以及每个形象的封面和预览视频地址。
 * record 的组件名就是 JSON 字段名，App 侧直接按这些键取值。
 * <pre>
 *   id               形象 id，建会话时填进 CreateConversationSessionRequestDTO.avatarId
 *   name             展示名；上游没给名字时退化成 id 本身
 *   coverUrl         列表封面图地址，可为 null（兜底形象没有封面）
 *   previewVideoUrl  预览视频地址，可为 null
 *   online           当前是否可用；上游拉取失败时兜底形象一律给 true
 * </pre>
 * 运行过程：
 * <pre>
 *   GET /api/v1/catalog/avatars
 *        |
 *        v
 *   CatalogController.avatars()
 *        |
 *        v
 *   CatalogCaseImpl.listAvatars()
 *        |
 *        | (1) avatarDriver.listAvatars() --> List&lt;AvatarProfileVO&gt;
 *        |        （OpenTalking 实现：拉上游 /avatars，失败或为空则退到 fallbackAvatarIds）
 *        | (2) 逐个 new AvatarResponseDTO(id, name, coverUrl, previewVideoUrl, online)
 *        v
 *   [ {"id":"anchor","name":"anchor","coverUrl":null,"previewVideoUrl":null,"online":true} ]
 * </pre>
 * 案例：
 * <pre>
 *   输入: 无（GET，不带参数）
 *   处理: 上游正常时映射上游字段；上游连不上时回落到 spoken-ai.provider.opentalking.fallback-avatar-ids
 *   输出: [ {"id":"anchor","name":"anchor","coverUrl":null,"previewVideoUrl":null,"online":true} ]
 *         _NON_NULL 生效：null 字段（如 coverUrl）不会出现在 JSON 里
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>@JsonInclude(NON_NULL) 是刻意的：封面/预览可为空，把 null 键省掉能让 App 少写一层判空，
 *       但也意味着前端要按「键缺失」处理，不能假定键一定在。</li>
 *   <li>列表设计成「永不空手而归」：上游异常时用配置里的兜底形象顶上，
 *       否则 App 启动就会看到一个空白的形象选择页。</li>
 *   <li>用 record 而不是 @Data 类：这是纯只读出参，没有 setter 需求，
 *       组件顺序即序列化顺序，改顺序属于对外契约变更。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AvatarResponseDTO(
        String id,
        String name,
        String coverUrl,
        String previewVideoUrl,
        boolean online
) {
}