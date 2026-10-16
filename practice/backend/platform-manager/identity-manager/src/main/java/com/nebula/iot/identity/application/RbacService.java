package com.nebula.iot.identity.application;

import com.nebula.iot.identity.api.*;
import com.nebula.iot.identity.api.AccessViews.*;
import com.nebula.iot.identity.domain.RbacRepository;
import com.nebula.iot.identity.domain.RbacRows.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Transactional(readOnly=true)
public class RbacService implements AccountProvisioning {
    private final AccountDirectory accounts;
    private final RbacRepository repository;
    private final PasswordEncoder encoder;
    public RbacService(AccountDirectory accounts,RbacRepository repository,PasswordEncoder encoder) {
        this.accounts=accounts;this.repository=repository;this.encoder=encoder;
    }
    public List<Permissions.Definition> catalog(String actor) {
        accounts.requirePermission(actor,"iam:role:read");return Permissions.CATALOG;
    }
    public List<Menus.Definition> menus(String actor) {
        accounts.requirePermission(actor,"iam:role:read");return configuredMenus();
    }
    public List<Menus.Definition> registeredPages(String actor) {
        accounts.requirePlatformAdmin(actor);return Menus.CATALOG;
    }
    private List<Menus.Definition> configuredMenus() {
        return repository.menus().stream().map(this::menuView).toList();
    }
    private Menus.Definition menuView(com.nebula.iot.identity.domain.MenuRow row) {
        return new Menus.Definition(row.code(),row.name(),row.groupName(),row.path(),repository.menuPermissions(row.code()),row.description(),row.sortOrder(),row.revision());
    }
    @Transactional public Menus.Definition saveMenu(String actor,Menus.Definition input,boolean creating) {
        repository.lock();accounts.requirePlatformAdmin(actor);validateMenu(input);
        var existing=repository.menu(input.code());
        if(creating && existing!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Menu already exists");
        if(!creating && existing==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Menu unavailable");
        var before=configuredMenus();
        var row=new com.nebula.iot.identity.domain.MenuRow(input.code(),input.name().strip(),input.group().strip(),input.path(),input.description().strip(),input.sortOrder(),input.revision());
        if(creating)repository.createMenu(row);
        else if(repository.updateMenu(row)!=1)conflict();
        repository.clearMenuPermissions(input.code());
        input.permissions().forEach(permission -> repository.addMenuPermission(input.code(),permission));
        if(!creating) {
            var old=before.stream().filter(menu -> menu.code().equals(input.code())).findFirst().orElseThrow();
            var added=new HashSet<>(input.permissions());added.removeAll(old.permissions());
            var removed=new HashSet<>(old.permissions());removed.removeAll(input.permissions());
            if(!added.isEmpty() || !removed.isEmpty()) for(var role:repository.roles()) {
                var previous=repository.rolePermissions(role.id());
                if(!previous.contains(input.code()))continue;
                var shared=before.stream().filter(menu -> !menu.code().equals(input.code()) && previous.contains(menu.code()))
                    .flatMap(menu -> menu.permissions().stream()).collect(java.util.stream.Collectors.toSet());
                var next=new LinkedHashSet<>(previous);
                next.removeIf(permission -> removed.contains(permission) && !shared.contains(permission));
                next.addAll(added);
                if(!next.equals(new HashSet<>(previous))) {
                    if(repository.updateRole(role.id(),role.name(),role.enabled(),role.revision())!=1)conflict();
                    repository.clearPermissions(role.id());next.forEach(permission -> repository.addPermission(role.id(),permission));
                    audit(actor,"ROLE_MENU_SYNCED",role.id());
                }
            }
        }
        audit(actor,creating?"MENU_CREATED":"MENU_UPDATED",input.code());
        return menuView(repository.menu(input.code()));
    }
    @Transactional public void deleteMenu(String actor,String code,long revision) {
        repository.lock();accounts.requirePlatformAdmin(actor);
        if("page:menus".equals(code))throw new ResponseStatusException(HttpStatus.CONFLICT,"Menu maintenance entry is protected");
        var existing=repository.menu(code);
        if(existing==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Menu unavailable");
        if(existing.revision()!=revision)conflict();
        if(repository.roles().stream().anyMatch(role -> repository.rolePermissions(role.id()).contains(code)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Revoke role assignments before deleting this menu");
        repository.clearMenuPermissions(code);
        if(repository.deleteMenu(code,revision)!=1)conflict();
        audit(actor,"MENU_DELETED",code);
    }
    private static void validateMenu(Menus.Definition menu) {
        if(menu==null)bad("Menu required");
        var page=Menus.CATALOG.stream().filter(item -> item.code().equals(menu.code())).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a registered page"));
        if(!page.path().equals(menu.path()))bad("Route must match the registered page");
        validName(menu.name());
        if(menu.group()==null || menu.group().isBlank() || menu.group().length()>100 || menu.description()==null || menu.description().length()>500 || menu.sortOrder()<0 || menu.sortOrder()>10000 || menu.revision()<0)bad("Invalid menu fields");
        var capabilities=Permissions.CATALOG.stream().map(Permissions.Definition::code).collect(java.util.stream.Collectors.toSet());
        if(menu.permissions()==null || menu.permissions().isEmpty() || menu.permissions().size()>capabilities.size()
            || menu.permissions().stream().anyMatch(permission -> permission==null || !capabilities.contains(permission))
            || new HashSet<>(menu.permissions()).size()!=menu.permissions().size())bad("Choose registered API permissions");
    }
    @Transactional public Role createRoleMenus(String actor,String code,String name,List<String> menus) {
        repository.lock();accounts.requirePermission(actor,"iam:role:write");
        return createRole(actor,code,name,menuPermissions(menus));
    }
    public List<String> menuPermissions(List<String> menus) {
        if (menus == null || menus.size() > configuredMenus().size()
                || menus.stream().anyMatch(code -> code == null || configuredMenus().stream().noneMatch(menu -> menu.code().equals(code)))
                || new HashSet<>(menus).size() != menus.size()) bad("Unknown or duplicate menu");
        return configuredMenus().stream().filter(menu -> menus.contains(menu.code())).flatMap(menu -> java.util.stream.Stream.concat(java.util.stream.Stream.of(menu.code()),menu.permissions().stream())).distinct().toList();
    }
    @Transactional public Role updateRoleMenus(String actor,String id,String name,Boolean enabled,Long revision,List<String> menus) {
        repository.lock();accounts.requirePermission(actor,"iam:role:write");existingRole(id);
        menuPermissions(menus);
        var previous=repository.rolePermissions(id);
        var previousMenus=configuredMenus().stream().filter(menu -> previous.contains(menu.code())).toList();
        var removedCapabilities=previousMenus.stream().filter(menu -> !menus.contains(menu.code()))
            .flatMap(menu -> menu.permissions().stream()).collect(java.util.stream.Collectors.toSet());
        var retainedCapabilities=previousMenus.stream().filter(menu -> menus.contains(menu.code()))
            .flatMap(menu -> menu.permissions().stream()).collect(java.util.stream.Collectors.toSet());
        var result=new LinkedHashSet<String>();
        // Retaining a migrated read-only page must not silently grant its write capabilities.
        previous.stream().filter(permission -> !permission.startsWith("page:"))
            .filter(permission -> !removedCapabilities.contains(permission) || retainedCapabilities.contains(permission)).forEach(result::add);
        result.addAll(menus);
        configuredMenus().stream().filter(menu -> menus.contains(menu.code()) && !previous.contains(menu.code()))
            .flatMap(menu -> menu.permissions().stream()).forEach(result::add);
        return updateRole(actor,id,name,enabled,revision,List.copyOf(result));
    }
    public Page<User> users(String actor,String query,int page,int size) {
        accounts.requirePermission(actor,"iam:user:read");
        if(query==null||query.length()>100||page<1||size<1||size>100) bad("Invalid search or pagination");
        return new Page<>(repository.users(query,size,((long)page-1)*size,tenantScope(actor)).stream().map(this::userView).toList(),repository.userCount(query,tenantScope(actor)),page,size);
    }
    public List<Role> roles(String actor) {
        accounts.requirePermission(actor,"iam:role:read");
        return repository.roles().stream().map(this::roleView).toList();
    }
    @Transactional public User createUser(String actor,String username,String password,String tenantId) {
        repository.lock();accounts.requirePermission(actor,"iam:user:write");
        if(username==null||username.isBlank()||username.length()>100||!username.equals(username.strip()))bad("Invalid username");
        if(password==null||password.isBlank()||password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)bad("Password required, maximum 72 UTF-8 bytes");
        if(tenantId==null||tenantId.isBlank())bad("A tenant is required for every ordinary account");
        var creator=accounts.requireEnabled(actor);
        if(!creator.platformAdmin()&&!tenantId.equals(creator.tenantId()))throw new AccessDeniedException("Cannot create users for another tenant");
        String id=UUID.randomUUID().toString();
        try{repository.createUser(id,username,encoder.encode(password),tenantId);}
        catch(org.springframework.dao.DuplicateKeyException duplicate){throw new ResponseStatusException(HttpStatus.CONFLICT,"Username already exists");}
        audit(actor,"USER_CREATED",id);return userView(existingUser(id));
    }
    @Transactional public User updateUser(String actor,String id,Boolean enabled,Long revision) {
        repository.lock();accounts.requirePermission(actor,"iam:user:write");
        if(enabled==null||revision==null||revision<0)bad("Status and revision required");
        var target=existingUser(id); protectAccount(actor,target);
        ceiling(actor,allUserPermissions(id));
        if(repository.updateUser(id,enabled,revision)!=1)conflict();
        audit(actor,"USER_UPDATED",id);return userView(existingUser(id));
    }
    @Transactional public User assignRoles(String actor,String id,List<String> roleIds,Long revision) {
        repository.lock();accounts.requirePermission(actor,"iam:grant");
        if(roleIds==null||roleIds.size()>100||roleIds.stream().anyMatch(Objects::isNull)||revision==null||revision<0)bad("Roles and revision required");
        var target=existingUser(id);protectAccount(actor,target);ceiling(actor,allUserPermissions(id));
        Set<String> unique=new HashSet<>(roleIds);
        if(unique.size()!=roleIds.size())bad("Duplicate roles");
        for(String roleId:unique){var role=existingRole(roleId);if(!role.enabled())bad("Disabled role cannot be assigned");ceiling(actor,repository.rolePermissions(roleId));}
        if(repository.touchUser(id,revision)!=1)conflict();
        repository.clearUserRoles(id);unique.forEach(roleId->repository.addUserRole(id,roleId));
        audit(actor,"USER_ROLES_CHANGED",id);return userView(existingUser(id));
    }
    @Transactional public Role createRole(String actor,String code,String name,List<String> permissions) {
        repository.lock();accounts.requirePermission(actor,"iam:role:write");
        validName(name);validPermissions(permissions);ceiling(actor,permissions);
        if(code==null||!code.matches("[a-z][a-z0-9:_-]{1,63}"))bad("Role code must use 2 to 64 lowercase letters, digits, colon, underscore or hyphen");
        String id=UUID.randomUUID().toString();
        try{repository.createRole(id,code,name.strip());}
        catch(org.springframework.dao.DuplicateKeyException duplicate){throw new ResponseStatusException(HttpStatus.CONFLICT,"Role code already exists");}
        permissions.forEach(p->repository.addPermission(id,p));audit(actor,"ROLE_CREATED",id);
        return roleView(existingRole(id));
    }
    @Transactional public Role updateRole(String actor,String id,String name,Boolean enabled,Long revision,List<String> permissions) {
        repository.lock();accounts.requirePermission(actor,"iam:role:write");
        validName(name);validPermissions(permissions);
        if(enabled==null||revision==null||revision<0)bad("Status and revision required");
        existingRole(id);ceiling(actor,repository.rolePermissions(id));ceiling(actor,permissions);
        if(repository.updateRole(id,name.strip(),enabled,revision)!=1)conflict();
        repository.clearPermissions(id);permissions.forEach(p->repository.addPermission(id,p));
        audit(actor,"ROLE_UPDATED",id);return roleView(existingRole(id));
    }
    private String tenantScope(String actor){var account=accounts.requireEnabled(actor);return account.platformAdmin()?null:account.tenantId();}
    private void protectAccount(String actor,UserRow target){
        var owner=accounts.requireEnabled(actor);
        if(!owner.platformAdmin()&&!Objects.equals(owner.tenantId(),target.tenantId()))throw new AccessDeniedException("Account belongs to another tenant");
        // Super-admin grants and recovery are a separate explicit administrative channel.
        if(target.platformAdmin()||target.id().equals(accounts.requireEnabled(actor).id()))throw new AccessDeniedException("Protected account");
    }
    private List<String> allUserPermissions(String id){return repository.userRoles(id).stream().flatMap(r->repository.rolePermissions(r).stream()).distinct().toList();}
    private void ceiling(String actor,Collection<String> requested){if(!accounts.requireEnabled(actor).permissions().containsAll(requested))throw new AccessDeniedException("Cannot delegate permissions you do not have");}
    private UserRow existingUser(String id){var user=repository.user(id);if(user==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User unavailable");return user;}
    private RoleRow existingRole(String id){var role=repository.role(id);if(role==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Role unavailable");return role;}
    private User userView(UserRow u){return new User(u.id(),u.username(),u.enabled(),u.platformAdmin(),u.revision(),repository.userRoles(u.id()),u.tenantId());}
    private Role roleView(RoleRow r){return new Role(r.id(),r.code(),r.name(),r.enabled(),r.revision(),repository.rolePermissions(r.id()));}
    private void audit(String actor,String action,String subject){repository.audit(UUID.randomUUID().toString(),accounts.requireEnabled(actor).id(),action,subject);}
    private static void validName(String name){if(name==null||name.isBlank()||name.length()>100)bad("Role name required, maximum 100 characters");}
    private static void validPermissions(List<String> permissions){if(permissions==null||permissions.size()>Permissions.ALL.size()||permissions.stream().anyMatch(p->p==null||!Permissions.ALL.contains(p))||new HashSet<>(permissions).size()!=permissions.size())bad("Unknown or duplicate permission");}
    private static void bad(String m){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
    private static void conflict(){throw new ResponseStatusException(HttpStatus.CONFLICT,"Revision changed; refresh and retry");}
}
