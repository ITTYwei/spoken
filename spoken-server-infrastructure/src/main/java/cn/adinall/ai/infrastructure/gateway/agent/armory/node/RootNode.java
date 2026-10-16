package cn.adinall.ai.infrastructure.gateway.agent.armory.node;

import cn.adinall.ai.infrastructure.gateway.agent.model.ArmoryCommandEntity;
import cn.adinall.ai.infrastructure.gateway.agent.model.AiAgentRegisterVO;
import cn.adinall.ai.infrastructure.gateway.agent.armory.AbstractArmorySupport;
import cn.adinall.ai.infrastructure.gateway.agent.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 根节点（装配责任链的起点）
 * <p>
 * 功能：只做一件事 —— 把装配请求交给下一个节点，本身不产出任何装配结果。
 * <pre>
 *   doApply()  路由到下一个节点（不写 DynamicContext）
 *   get()      返回下一个节点 AiApiNode
 * </pre>
 * 运行过程：
 * <pre>
 *   ArmoryService.acceptArmoryAgents()
 *        |
 *        v
 *   RootNode.doApply(requestParameter, dynamicContext)
 *        |
 *        v
 *   router(requestParameter, dynamicContext)
 *        |
 *        v
 *   get(...) --> aiApiNode
 *        |
 *        v
 *   AiApiNode.doApply(...)   （真正的装配从这里开始）
 * </pre>
 * 案例：
 * <pre>
 *   输入: ArmoryCommandEntity{aiAgentConfigTableVO=table(appName="demo-app")}, 空 DynamicContext
 *   处理: doApply --> router --> get 返回 aiApiNode
 *   输出: 无（返回值来自链尾 RunnerNode），上下文此时仍为空
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>起点节点保持纯粹：调用方只依赖「配置表 + 一个空上下文」这个最小入参，
 *       起始条件不必随装配步骤增减而调整。</li>
 *   <li>doApply 不写上下文是刻意的 —— 上下文里所有键都由具体节点按需写入，
 *       避免出现一个「谁都能改」的公共写入口。</li>
 *   <li>新增装配步骤时，只需把 RootNode 的 get() 指到新节点，或在链尾追加，
 *       不用改 ArmoryService。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2025/12/17 08:16
 */
@Slf4j
@Service
public class RootNode extends AbstractArmorySupport {

    @Resource
    private AiApiNode aiApiNode;

    /** 执行当前节点的智能体配置装配。 */
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        // 路由到下一个节点
        return router(requestParameter, dynamicContext);
    }

    /** 返回当前装配节点完成后应执行的后续策略。 */
    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 配置了下一个节点
        return aiApiNode;
    }

}
