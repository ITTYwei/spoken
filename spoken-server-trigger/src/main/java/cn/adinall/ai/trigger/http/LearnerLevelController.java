package cn.adinall.ai.trigger.http;

import cn.adinall.ai.api.dto.EnglishLevelRequestDTO;
import cn.adinall.ai.cases.learner.LearnerLevelCase;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelSettingVO;
import org.springframework.web.bind.annotation.*;

/** “我的”陪练档位入口，只转换请求和调用用例；生效时间为下一次新对话。 */
@RestController
@RequestMapping("/api/v1/me/english-level")
public class LearnerLevelController {
    /** 处理可信身份、事务及领域调用的用例。 */
    private final LearnerLevelCase levels;
    public LearnerLevelController(LearnerLevelCase levels) { this.levels = levels; }
    /** 查询自动、手动及下次有效档位，返回真实持久化状态。 */
    @GetMapping public EnglishLevelSettingVO get() { return levels.get(); }
    /** 保存成功后返回真实快照；非法档位拒绝，不改当前媒体或已冻结对话。 */
    @PutMapping public EnglishLevelSettingVO put(@RequestBody EnglishLevelRequestDTO request) { return levels.save(request.level()); }
}
