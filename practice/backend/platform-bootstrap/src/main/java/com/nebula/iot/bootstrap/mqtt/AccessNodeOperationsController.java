package com.nebula.iot.bootstrap.mqtt;

import com.nebula.iot.cluster.JdbcNodeDirectory;
import com.nebula.iot.identity.api.AccountDirectory;
import java.security.Principal;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@RestController
@RequestMapping("/api/management/access/nodes")
@ConditionalOnProperty(name="nebula.cluster.enabled",havingValue="true")
public class AccessNodeOperationsController {
    public record DrainRequest(String operationId,long revision,int batchSize,int timeoutSeconds) {}
    public record ResumeRequest(String operationId,long revision) {}
    private final JdbcNodeDirectory directory;
    private final AccountDirectory accounts;
    public AccessNodeOperationsController(JdbcNodeDirectory directory,AccountDirectory accounts){this.directory=directory;this.accounts=accounts;}
    @PostMapping("/{nodeId}/drain") @ResponseStatus(HttpStatus.ACCEPTED)
    public JdbcNodeDirectory.Drain drain(Principal principal,@PathVariable String nodeId,@RequestBody DrainRequest request){
        accounts.requirePlatformAdmin(principal.getName());
        if(request.operationId()==null)throw new IllegalArgumentException("Operation ID required");
        return directory.requestDrain(request.operationId(),nodeId,request.revision(),principal.getName(),request.batchSize(),Duration.ofSeconds(request.timeoutSeconds()));
    }
    @PostMapping("/{nodeId}/resume")
    public JdbcNodeDirectory.Drain resume(Principal principal,@PathVariable String nodeId,@RequestBody ResumeRequest request){
        accounts.requirePlatformAdmin(principal.getName());return directory.resume(nodeId,request.revision(),request.operationId(),principal.getName());
    }
    @GetMapping("/operations/{operationId}")
    public JdbcNodeDirectory.Drain operation(Principal principal,@PathVariable String operationId){
        accounts.requirePlatformAdmin(principal.getName());directory.expireOperations();return directory.operation(operationId);
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public java.util.Map<String,String> invalid(){return java.util.Map.of("error","invalid_operation");}
    @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public java.util.Map<String,String> conflict(){return java.util.Map.of("error","node_changed_or_insufficient_capacity");}
}
