package cn.adinall.ai.trigger.http;

import cn.adinall.ai.api.dto.EnglishLevelRequestDTO;
import cn.adinall.ai.domain.identity.support.AccountContext;
import cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum;
import cn.adinall.ai.domain.learning.model.valobj.EnglishLevelSettingVO;
import cn.adinall.ai.domain.learning.service.level.LearnerProgressService;
import org.springframework.web.bind.annotation.*;

/**
 * <b>功能</b>：查询和保存当前登录账号的陪练档位。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>Bearer identity -> request enum conversion -> learning service -> transactional selection</pre>
 * <p><b>案例</b>：PUT {level:L3} -> manualLevel=L3；PUT {level:null} -> 恢复自动档位。</p>
 * <b>设计要点</b>
 * <ul><li>单领域设置直接进入 Domain，保存事务由领域服务持有。</li>
 * <li>主体取自可信身份，非法字符串在 HTTP 类型转换处拒绝。</li></ul>
 */
@RestController
@RequestMapping("/api/v1/me/english-level")
public class LearnerLevelController {
    private final LearnerProgressService progress;
    /** 创建 {@code LearnerLevelController}，初始化其依赖与状态。 */
    public LearnerLevelController(LearnerProgressService progress) { this.progress = progress; }

    /** 读取当前账号的陪练档位及其设置来源。 */
    @GetMapping public EnglishLevelSettingVO get() {
        return progress.settings(subject());
    }

    /** 保存当前账号主动选择的陪练档位。 */
    @PutMapping public EnglishLevelSettingVO put(@RequestBody EnglishLevelRequestDTO request) {
        String subject = subject();
        EnglishLevelEnum level;
        try { level = request.level() == null ? null : EnglishLevelEnum.valueOf(request.level()); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("请选择 L1 至 L6 的练习档位，或恢复自动适配");
        }
        return progress.select(subject, level);
    }

    /** 返回当前登录账号的学习主体键。 */
    private String subject() { return "user:" + AccountContext.require().id(); }
}
