package com.nebula.iot.device.interfaces;

import com.nebula.iot.device.api.FirmwareViews.*;
import com.nebula.iot.device.application.FirmwareFileService;
import com.nebula.iot.file.*;
import java.io.IOException;
import java.security.Principal;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@ConditionalOnExpression("${nebula.runtime.management:true}")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
@RequestMapping({"/api/management/catalog/platform/products/{productId}/firmware", "/api/management/catalog/tenants/{tenantId}/products/{productId}/firmware"})
public class FirmwareFileController {
    private final FirmwareFileService service;
    public FirmwareFileController(FirmwareFileService service) { this.service=service; }
    @GetMapping("/files/capabilities") public FileCapabilities capabilities(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String productId) {
        return service.capabilities(actor.getName(),tenantId,productId);
    }
    @PostMapping(value="/channels/{channel}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public UploadedFile upload(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String productId,@PathVariable int channel,
                               @RequestPart("file") MultipartFile file,@RequestParam String keyId,@RequestParam String signature) throws IOException {
        try (var in=file.getInputStream()) { return service.upload(actor.getName(),tenantId,productId,channel,keyId,signature,in); }
    }
    @GetMapping("/channels/{channel}/versions/{id}/download") public ObjectStorage.Download download(Principal actor,@PathVariable(required=false) String tenantId,@PathVariable String productId,@PathVariable int channel,@PathVariable String id,jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return service.download(actor.getName(),tenantId,productId,channel,id);
    }
    @ExceptionHandler(FileStorageException.class) @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ProblemDetail storageFailure() { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,"文件存储暂时不可用，请稍后重试"); }
    @ExceptionHandler(IOException.class) @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ProblemDetail uploadFailure() { return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,"文件接收失败，请重试"); }
}
