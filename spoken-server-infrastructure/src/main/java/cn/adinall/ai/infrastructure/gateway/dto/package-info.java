/**
 * OpenTalking 上游接口专用 DTO 的预留包（当前为空包）
 * <p>
 * 包职责：收拢「OpenTalking 线格式」的请求体 / 响应体 / SSE 事件体的 DTO 定义，
 * 让上游字段名只出现在基础设施层内部，不泄漏到 domain 与 api。
 * <pre>
 *   上游线格式（字段名跟对端一致）  ->  本包 DTO  ->  gateway / adapter 内部使用
 *   domain 的 VO / 聚合             ->  不引用本包任何类型
 * </pre>
 * 包内类清单：
 * <pre>
 *   （当前为空）
 *   本包目前没有任何 Java 类；现有实现刻意不走强类型 DTO，
 *   全部用 JsonNode + OpenTalkingJson 做宽松取值（原因见下面的设计要点）。
 * </pre>
 * 典型调用链（现状是 JsonNode 直通，虚线是将来引入 DTO 后的接法）：
 * <pre>
 *   OpenTalkingAvatarDriver（适配器）
 *        |
 *        v
 *   OpenTalkingGateway（唯一直接讲 OpenTalking 方言的类）
 *        |
 *        | 现状：请求体拼成 Map / Map.of -> writeJson；响应体直接读 JsonNode
 *        | 将来：请求体用本包 XxxRequest -> writeJson
 *        |       响应体用 MAPPER.readValue(body, XxxResponse.class)
 *        v
 *   cn.adinall.ai.infrastructure.gateway.dto（本包）
 *        |
 *        v
 *   OpenTalkingEventTranslator（SSE 事件 -> 领域事件）
 *        现状：OpenTalkingJson.text(payload, "text", "content", ...) 多候选名取
 *        将来：payload 绑定成事件 DTO 后按字段读
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>与 domain 的 valobj 严格分开：本包只描述上游的线格式（字段名跟对端走），
 *       一旦被领域层引用，上游字段名就变成了跨层契约，防腐层形同虚设。</li>
 *   <li>现状刻意不用强类型 DTO 而用 JsonNode 宽松解析：上游同一含义的字段在不同版本里叫法不同
 *       （session_id / sessionId、sdp / answer / answer_sdp），强类型绑定的表现是直接反序列化失败，
 *       而宽松取值只是取到 null 后走兜底分支。</li>
 *   <li>什么情况下才值得往本包放类：字段多且结构已经稳定（例如以后 /models 的 statuses
 *       稳定下来），此时用 DTO 替换手写的候选名取值链能提升可读性；字段三五行的接口不值得。</li>
 *   <li>访问范围约定：本包的类只在 gateway 包与 adapter 包内部使用，
 *       不要在 Controller 或用例层出现，否则又会出现两套上游字段名的翻译。</li>
 * </ul>
 */
package cn.adinall.ai.infrastructure.gateway.dto;
