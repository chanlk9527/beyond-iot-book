package com.nebula.iot.video.interfaces;

import com.nebula.iot.media.api.MediaProvider.Signal;
import com.nebula.iot.video.api.AppLiveRequestVerifier;
import com.nebula.iot.video.api.LiveAccessPolicy;
import com.nebula.iot.video.application.LiveSessionService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** C-end live API. The App Server supplies a signed, binding-scoped claim. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@RestController
@RequestMapping("/api/app/video")
@ConditionalOnProperty(name = "nebula.app.video.enabled", havingValue = "true")
public class AppLiveController {
    private final LiveSessionService service;
    private final AppLiveRequestVerifier verifier;
    public AppLiveController(LiveSessionService service, AppLiveRequestVerifier verifier) { this.service = service; this.verifier = verifier; }

    @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED)
    public LiveSessionService.View create(@RequestHeader("X-Nebula-App-Subject") String subject,
                                          @RequestHeader("X-Nebula-App-Id") String appId,
                                          @RequestHeader("X-Nebula-App-Tenant") String tenant,
                                          @RequestHeader("X-Nebula-App-Device") String device,
                                          @RequestHeader("X-Nebula-App-Channel") String channel,
                                          @RequestHeader("X-Nebula-App-Timestamp") String timestamp,
                                          @RequestHeader("X-Nebula-App-Request-Id") String requestId,
                                          @RequestHeader("X-Nebula-App-Signature") String signature,
                                          HttpServletResponse response) {
        noStore(response);
        var claim = verify(appId, subject, tenant, device, channel, timestamp, requestId, signature);
        return service.create(new LiveAccessPolicy.Identity(LiveAccessPolicy.Audience.APP, claim.subject()),
                claim.tenantId(), claim.deviceId(), claim.channelId());
    }
    @PostMapping("/sessions/{id}/signals") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void send(@PathVariable String id, @RequestBody Signal signal,
                     @RequestHeader("X-Nebula-App-Subject") String subject,
                     @RequestHeader("X-Nebula-App-Id") String appId,
                     @RequestHeader("X-Nebula-App-Tenant") String tenant,
                     @RequestHeader("X-Nebula-App-Device") String device,
                     @RequestHeader("X-Nebula-App-Channel") String channel,
                     @RequestHeader("X-Nebula-App-Timestamp") String timestamp,
                     @RequestHeader("X-Nebula-App-Request-Id") String requestId,
                     @RequestHeader("X-Nebula-App-Signature") String signature,
                     HttpServletResponse response) {
        noStore(response);
        // Session ownership is checked by the service. For signaling, the signature binds the caller to the session subject.
        var claim = verify(appId, subject, tenant, device, channel, timestamp, requestId, signature);
        service.send(new LiveAccessPolicy.Identity(LiveAccessPolicy.Audience.APP, claim.subject()), id,
                claim.tenantId(), claim.deviceId(), claim.channelId(), signal);
    }
    @GetMapping("/sessions/{id}/signals")
    public List<Signal> receive(@PathVariable String id,
                                @RequestHeader("X-Nebula-App-Subject") String subject,
                                @RequestHeader("X-Nebula-App-Id") String appId,
                                @RequestHeader("X-Nebula-App-Tenant") String tenant,
                                @RequestHeader("X-Nebula-App-Device") String device,
                                @RequestHeader("X-Nebula-App-Channel") String channel,
                                @RequestHeader("X-Nebula-App-Timestamp") String timestamp,
                                @RequestHeader("X-Nebula-App-Request-Id") String requestId,
                                @RequestHeader("X-Nebula-App-Signature") String signature,
                                HttpServletResponse response) {
        noStore(response);
        var claim = verify(appId, subject, tenant, device, channel, timestamp, requestId, signature);
        return service.receive(new LiveAccessPolicy.Identity(LiveAccessPolicy.Audience.APP, claim.subject()), id,
                claim.tenantId(), claim.deviceId(), claim.channelId());
    }
    @DeleteMapping("/sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id,
                       @RequestHeader("X-Nebula-App-Subject") String subject,
                       @RequestHeader("X-Nebula-App-Id") String appId,
                       @RequestHeader("X-Nebula-App-Tenant") String tenant,
                       @RequestHeader("X-Nebula-App-Device") String device,
                       @RequestHeader("X-Nebula-App-Channel") String channel,
                       @RequestHeader("X-Nebula-App-Timestamp") String timestamp,
                       @RequestHeader("X-Nebula-App-Request-Id") String requestId,
                       @RequestHeader("X-Nebula-App-Signature") String signature,
                       HttpServletResponse response) {
        noStore(response);
        var claim = verify(appId, subject, tenant, device, channel, timestamp, requestId, signature);
        service.delete(new LiveAccessPolicy.Identity(LiveAccessPolicy.Audience.APP, claim.subject()), id,
                claim.tenantId(), claim.deviceId(), claim.channelId());
    }
    private AppLiveRequestVerifier.Claim verify(String app,String s,String t,String d,String c,String ts,String requestId,String sig){
        try { return verifier.verify(app,s,t,d,c,ts,requestId,sig); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid App live identity"); }
    }
    private static void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
}
