package cn.adinall.ai.trigger.http;

import cn.adinall.ai.cases.practice.PracticeCase;
import cn.adinall.ai.domain.scenario.model.valobj.*;
import cn.adinall.ai.domain.practice.model.valobj.PracticeStateVO;
import cn.adinall.ai.api.dto.PracticeSwitchRequestDTO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 教材与练习 HTTP 薄入口；只转换请求和返回值，不创建模型或操作媒体。
 * 账号来自现有认证链路，模式事务由 Case 编排，编号与状态规则由所属领域处理。
 */
@RestController
@RequestMapping("/api/v1")
public class PracticeController {
    /** 模式及会话归属的业务编排入口。 */
    private final PracticeCase practice;
    public PracticeController(PracticeCase practice) { this.practice=practice; }
    /** 返回本地固定教材目录，运行时不依赖 GitHub。 */
    @GetMapping("/textbooks") public List<TextbookVO> list() { return practice.textbooks(); }
    /** 读取完整课简介、目标与原题；无效编号返回不存在。 */
    @GetMapping("/textbooks/{textbookId}/lessons/{lessonId}")
    public TextLessonVO lesson(@PathVariable String textbookId,@PathVariable String lessonId) { return practice.lesson(textbookId,lessonId); }
    /** 获取当前模式、版本及已播出的继续选择窗口。 */
    @GetMapping("/sessions/{sessionId}/practice") public PracticeStateVO current(@PathVariable String sessionId) { return practice.current(sessionId); }
    /** 幂等模式切换，业务失败时返回实际错误，不宣布成功。 */
    @PostMapping("/sessions/{sessionId}/practice") public PracticeStateVO change(@PathVariable String sessionId,@Valid @RequestBody PracticeSwitchRequestDTO request) {
        return practice.switchFromButton(sessionId,request);
    }
}
