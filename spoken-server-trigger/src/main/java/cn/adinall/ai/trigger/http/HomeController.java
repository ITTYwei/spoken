package cn.adinall.ai.trigger.http;

import cn.adinall.ai.domain.account.service.IProfileService;
import cn.adinall.ai.domain.account.service.IHomeService;
import cn.adinall.ai.domain.account.model.valobj.VoiceVO;
import cn.adinall.ai.domain.account.model.valobj.AvatarVoiceSelectionVO;

import cn.adinall.ai.api.dto.AvatarResponseDTO;
import cn.adinall.ai.cases.catalog.ICatalogCase;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 首页聚合：卡片、音色和上次选择；开始聊天沿用统一sessions入口。 */
@RestController
@RequestMapping("/api/v1/home")
public class HomeController {
    private final ICatalogCase catalog;
    private final IHomeService home;
    private final IProfileService profiles;
    public HomeController(ICatalogCase catalog, IHomeService home, IProfileService profiles) {
        this.catalog = catalog; this.home = home; this.profiles = profiles;
    }
    public record Home(List<AvatarResponseDTO> avatars, List<VoiceVO> voices, AvatarVoiceSelectionVO selection) { }
    @GetMapping public Home home() {
        var profile = profiles.profile();
        return new Home(catalog.listAvatars(), home.voices(), new AvatarVoiceSelectionVO(profile.avatarId(), profile.voice()));
    }
    @PutMapping("/selection") public AvatarVoiceSelectionVO select(@RequestBody AvatarVoiceSelectionVO request) {
        return home.select(request.avatarId(), request.voice());
    }
    /** 独立试听返回WAV；不开始练习，也不抢占数字人的单GPU槽位。 */
    @PostMapping(value="/voice-preview", produces="audio/wav")
    public ResponseEntity<byte[]> preview(@RequestBody AvatarVoiceSelectionVO request) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(home.preview(request.voice()));
    }
}
