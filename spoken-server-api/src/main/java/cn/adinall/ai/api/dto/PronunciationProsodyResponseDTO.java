package cn.adinall.ai.api.dto;

import java.util.List;

/**
 * 韵律曲线（评测响应里的 prosody）
 * <p>
 * 功能：两条逐帧等长的曲线，供 App 画「用户读得平不平 / 重音在哪」的对比图。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   f0      基频曲线（Hz），逐帧一个点
 *   energy  能量（响度）曲线，逐帧一个点
 * </pre>
 * 案例：
 * <pre>
 *   输出: {"f0":[200.0,193.19,...,97.15],"energy":[9.27,81.68,...,213.6]}   // 各 38 个点
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>原样透传不做下采样：画图取点属于展示决策，后端一采样，App 想放大看细节就没了原始数据。</li>
 *   <li>两条曲线都可能为 null：上游可以用 --no-prosody 跳过这段计算，
 *       此时键会因为 @JsonInclude(NON_NULL) 而不出现在响应里。</li>
 * </ul>
 */
public record PronunciationProsodyResponseDTO(List<Double> f0, List<Double> energy) {
}
