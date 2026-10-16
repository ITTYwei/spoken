package cn.adinall.ai.domain.companion.model.valobj;

import lombok.Value;

/**
 * <b>功能</b>：描述对外可用的伙伴身份。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>framework config -> execution adapter -> public agent identity -> catalog</pre>
 * <p><b>案例</b>：100003、口语伙伴、日常练习 -> 返回身份，不包含模型地址或密钥。</p>
 * <b>设计要点</b>
 * <ul><li>不携带装配模块、SDK 类型或供应商凭据。</li><li>展示身份和技术配置分别建模。</li></ul>
 */
@Value
public class AgentProfileVO {
    String agentId;
    String agentName;
    String agentDesc;
}
