package com.nebula.iot.video.interfaces;
import com.nebula.iot.media.api.MediaProvider.Signal;
import com.nebula.iot.video.api.LiveAccessPolicy.*;
import com.nebula.iot.video.application.LiveSessionService;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/management/video")
@ConditionalOnProperty(name = "nebula.management.enabled", havingValue = "true")
public class LiveController {
    public record Create(String tenantId, String deviceId, String channelId) {}
    private final LiveSessionService service;
    public LiveController(LiveSessionService service) { this.service = service; }
    @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED)
    public LiveSessionService.View create(Principal principal, @RequestBody Create input, HttpServletResponse response) {
        noStore(response);
        if (input.deviceId() == null || input.deviceId().isBlank() || input.deviceId().length() > 64
                || input.channelId() == null || !input.channelId().matches("[A-Za-z0-9_-]{1,64}")
                || (input.tenantId() != null && (input.tenantId().isBlank() || input.tenantId().length() > 64)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device and channel required");
        return service.create(identity(principal), input.tenantId(), input.deviceId(), input.channelId());
    }
    @PostMapping("/sessions/{id}/signals") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void send(Principal principal, @PathVariable String id, @RequestBody Signal signal, HttpServletResponse response) {
        noStore(response); service.send(identity(principal), id, signal);
    }
    @GetMapping("/sessions/{id}/signals")
    public List<Signal> receive(Principal principal, @PathVariable String id, HttpServletResponse response) {
        noStore(response); return service.receive(identity(principal), id);
    }
    @DeleteMapping("/sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Principal principal, @PathVariable String id, HttpServletResponse response) {
        noStore(response); service.delete(identity(principal), id);
    }
    private static Identity identity(Principal principal) { return new Identity(Audience.MANAGEMENT, principal.getName()); }
    private static void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
}
