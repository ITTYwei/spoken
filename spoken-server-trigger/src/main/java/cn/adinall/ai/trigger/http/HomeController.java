package cn.adinall.ai.trigger.http;

import cn.adinall.ai.cases.overview.HomeQueryCase.Overview;

import cn.adinall.ai.cases.overview.HomeQueryCase;
import cn.adinall.ai.domain.identity.service.IHomeService;
import cn.adinall.ai.domain.identity.model.valobj.VoiceVO;
import cn.adinall.ai.domain.identity.model.valobj.AvatarVoiceSelectionVO;

import cn.adinall.ai.api.dto.AvatarResponseDTO;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * <b>功能</b>：提供首页快照、伙伴选择和固定短文案音色试听。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * GET home -> HomeQueryCase -> account selection + avatars + voices -> HTTP mapping
 * PUT selection / POST preview -> identity home service
 * </pre>
 * <p><b>案例</b>：GET /api/v1/home -> avatars、voices、selection；试听返回 audio/wav。</p>
 * <b>设计要点</b>
 * <ul><li>首页跨领域组合经过 Case，单领域保存和试听直接调用 Domain。</li>
 * <li>接口路径和 JSON 字段保持 Android 当前契约。</li></ul>
 */
@RestController
@RequestMapping("/api/v1/home")
public class HomeController {
    private final HomeQueryCase overview;
    private final IHomeService home;
    /** 创建 {@code HomeController}，初始化其依赖与状态。 */
    public HomeController(HomeQueryCase overview, IHomeService home) {
        this.overview = overview; this.home = home;
    }
    public record Home(List<AvatarResponseDTO> avatars, List<VoiceVO> voices, AvatarVoiceSelectionVO selection) { }
    /** 读取当前账号首页的伙伴选择、学习概况和可用音色。 */
    @GetMapping public Home home() {
        Overview result = overview.query();
        return new Home(result.avatars().stream().map(CatalogResponseAssembler::avatar).toList(), result.voices(), result.selection());
    }
    /** 校验并保存当前账号选择的形象与音色。 */
    @PutMapping("/selection") public AvatarVoiceSelectionVO select(@RequestBody AvatarVoiceSelectionVO request) {
        return home.select(request.avatarId(), request.voice());
    }
    /** 独立试听返回WAV；不开始练习，也不抢占数字人的单GPU槽位。 */
    @PostMapping(value="/voice-preview", produces="audio/wav")
    public ResponseEntity<byte[]> preview(@RequestBody AvatarVoiceSelectionVO request) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(home.preview(request.voice()));
    }
}
