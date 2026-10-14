package cn.adinall.ai.trigger.http;

import cn.adinall.ai.domain.account.model.valobj.AccountOverviewVO;

import cn.adinall.ai.domain.account.service.IProfileService;
import org.springframework.web.bind.annotation.*;

/** “我的”页面接口，不接受userId参数；账号归属来自认证上下文。 */
@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {
    private final IProfileService profiles;
    public ProfileController(IProfileService profiles) { this.profiles = profiles; }
    public record Nickname(String nickname) { }
    public record Display(boolean subtitles, String translation) { }
    public record Fact(String key, String type, String content) { }
    @GetMapping public AccountOverviewVO me() { return profiles.me(); }
    /** 保存后返回完整资料，客户端不需要猜测服务器最终状态。 */
    @PatchMapping("/nickname") public AccountOverviewVO nickname(@RequestBody Nickname request) {
        profiles.nickname(request.nickname()); return profiles.me();
    }
    @PutMapping("/display") public AccountOverviewVO display(@RequestBody Display request) {
        profiles.display(request.subtitles(), request.translation()); return profiles.me();
    }
    @PutMapping("/preferences") public AccountOverviewVO preference(@RequestBody Fact request) {
        profiles.fact(request.key(), request.type(), request.content()); return profiles.me();
    }
    /** 删除采用独立HTTP动作，避免客户端用空字符串误清除事实。 */
    @DeleteMapping("/preferences") public AccountOverviewVO delete(@RequestParam String key, @RequestParam String type) {
        profiles.fact(key, type, null); return profiles.me();
    }
}
