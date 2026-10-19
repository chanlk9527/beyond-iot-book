package com.nebula.iot.identity.interfaces;

import com.nebula.iot.identity.api.*;
import com.nebula.iot.identity.api.AccessViews.*;
import com.nebula.iot.identity.application.RbacService;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("${nebula.runtime.management:true}")
@RestController
@RequestMapping("/api/management/iam")
@ConditionalOnProperty(name="nebula.management.enabled",havingValue="true")
public class RbacController {
    private final RbacService service;
    public RbacController(RbacService service){this.service=service;}
    public record UpdateUser(Boolean enabled,Long revision){}
    public record UserRoles(List<String> roleIds,Long revision){}
    public record CreateRole(String code,String name,List<String> permissions,List<String> menus){}
    public record UpdateRole(String name,Boolean enabled,Long revision,List<String> permissions,List<String> menus){}
    @GetMapping("/menus") public List<Menus.Definition> menus(Principal actor){return service.menus(actor.getName());}
    @GetMapping("/menu-pages") public List<Menus.Definition> pages(Principal actor){return service.registeredPages(actor.getName());}
    @PostMapping("/menus") @ResponseStatus(HttpStatus.CREATED) public Menus.Definition createMenu(Principal actor,@RequestBody Menus.Definition input){return service.saveMenu(actor.getName(),input,true);}
    @PutMapping("/menus/{code}") public Menus.Definition updateMenu(Principal actor,@PathVariable String code,@RequestBody Menus.Definition input){
        if(!code.equals(input.code()))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Menu code cannot change");
        return service.saveMenu(actor.getName(),input,false);
    }
    public record DeleteMenu(long revision){}
    @DeleteMapping("/menus/{code}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteMenu(Principal actor,@PathVariable String code,@RequestBody DeleteMenu input){service.deleteMenu(actor.getName(),code,input.revision());}
    @GetMapping("/permissions") public List<Permissions.Definition> catalog(Principal actor){return service.catalog(actor.getName());}
    @GetMapping("/users") public Page<User> users(Principal actor,@RequestParam(defaultValue="")String query,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return service.users(actor.getName(),query,page,size);}
    @PutMapping("/users/{id}") public User updateUser(Principal actor,@PathVariable String id,@RequestBody UpdateUser input){return service.updateUser(actor.getName(),id,input.enabled(),input.revision());}
    @PutMapping("/users/{id}/roles") public User assign(Principal actor,@PathVariable String id,@RequestBody UserRoles input){return service.assignRoles(actor.getName(),id,input.roleIds(),input.revision());}
    @GetMapping("/roles") public List<Role> roles(Principal actor){return service.roles(actor.getName());}
    @PostMapping("/roles") @ResponseStatus(HttpStatus.CREATED) public Role createRole(Principal actor,@RequestBody CreateRole input){
        if(input.menus()!=null){
            if(input.permissions()!=null)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Specify menus or permissions, not both");
            return service.createRoleMenus(actor.getName(),input.code(),input.name(),input.menus());
        }
        return service.createRole(actor.getName(),input.code(),input.name(),input.permissions());
    }
    @PutMapping("/roles/{id}") public Role updateRole(Principal actor,@PathVariable String id,@RequestBody UpdateRole input){
        if (input.menus()!=null) {
            if (input.permissions()!=null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Specify menus or permissions, not both");
            return service.updateRoleMenus(actor.getName(),id,input.name(),input.enabled(),input.revision(),input.menus());
        }
        return service.updateRole(actor.getName(),id,input.name(),input.enabled(),input.revision(),input.permissions());
    }
}
