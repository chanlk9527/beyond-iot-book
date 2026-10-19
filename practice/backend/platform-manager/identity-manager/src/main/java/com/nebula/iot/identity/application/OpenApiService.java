package com.nebula.iot.identity.application;

import com.nebula.iot.identity.api.*;
import com.nebula.iot.identity.domain.OpenApiRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Transactional
public class OpenApiService {
    private static final Set<String> ALLOWED = Set.of("device:read", "device:control", "video:watch", "video:recording", "event:read");
    private final OpenApiRepository repository; private final PasswordEncoder passwords; private final Clock clock;
    public OpenApiService(OpenApiRepository repository, PasswordEncoder passwords) { this(repository, passwords, Clock.systemUTC()); }
    OpenApiService(OpenApiRepository repository, PasswordEncoder passwords, Clock clock) { this.repository=repository; this.passwords=passwords; this.clock=clock; }
    public record Registration(OpenApiApplication application, String clientSecret) {
        @Override public String toString() { return "Registration["+application.appId()+", secret=REDACTED]"; }
    }
    public record Token(String accessToken, String tokenType, long expiresIn, Set<String> scopes) {
        @Override public String toString() { return "Token[REDACTED]"; }
    }
    public Registration register(String appId, String name, Collection<String> scopes) {
        validText(appId,64); validText(name,100); Set<String> requested=validateScopes(scopes);
        if(!appId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid application ID");
        String secret = random(32); repository.insertApplication(UUID.randomUUID().toString(),appId,name,passwords.encode(secret),join(requested));
        return new Registration(new OpenApiApplication(appId,name,requested,true,0),secret);
    }
    public Token issue(String appId, String clientSecret, String subject, String tenantId, Collection<String> scopes) {
        if (appId==null || clientSecret==null || clientSecret.isBlank() || clientSecret.length()>256) throw new BadCredentialsException("Application authentication failed");
        var app=repository.application(appId).filter(a->a.enabled()).orElseThrow(()->new BadCredentialsException("Application authentication failed"));
        if (!passwords.matches(clientSecret,app.secretHash())) throw new BadCredentialsException("Application authentication failed");
        // Client credentials represent the application; user delegation requires a separate verified mapping.
        if (subject!=null || tenantId!=null) throw new IllegalArgumentException("User and tenant delegation is not supported by client credentials");
        Set<String> granted=validateScopes(scopes); Set<String> allowed=split(app.scopes());
        if (!allowed.containsAll(granted)) throw new AccessDeniedException("Requested scope is not granted to application");
        Instant expires=clock.instant().plus(Duration.ofMinutes(30)); String token=random(48);
        repository.insertToken(hash(token),appId,appId,repository.tenantId(appId),join(granted),expires,app.revision());
        return new Token(token,"Bearer",1800,granted);
    }
    @Transactional(readOnly=true) public OpenApiPrincipal authenticate(String rawToken, String requiredScope) {
        if (rawToken==null||!rawToken.matches("[A-Za-z0-9_-]{64}")) throw new BadCredentialsException("Token unavailable");
        var row=repository.token(hash(rawToken)).filter(t->!t.revoked() && t.expiresAt().isAfter(clock.instant())).orElseThrow(()->new BadCredentialsException("Token unavailable"));
        var principal=new OpenApiPrincipal(row.appId(),row.subject(),row.tenantId(),split(row.scopes()),row.expiresAt());
        if (requiredScope!=null&&!principal.allows(requiredScope)) throw new AccessDeniedException("Scope required"); return principal;
    }
    public void revoke(String rawToken) { authenticate(rawToken,null); repository.revokeToken(hash(rawToken)); }
    public void setEnabled(String appId, boolean enabled, long revision) {
        if(repository.setEnabled(appId,enabled,revision)!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Application revision changed");
    }
    public String rotateSecret(String appId, long revision) {
        String secret=random(32);
        if(repository.rotateSecret(appId,passwords.encode(secret),revision)!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Application revision changed");
        return secret;
    }
    private static Set<String> validateScopes(Collection<String> scopes) { if(scopes==null||scopes.isEmpty()||scopes.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Scope required"); Set<String>s=Set.copyOf(scopes); if(!ALLOWED.containsAll(s)) throw new IllegalArgumentException("Unknown scope"); return s; }
    private static Set<String> split(String value){return Arrays.stream(value.split(" ")).filter(x->!x.isBlank()).collect(Collectors.toUnmodifiableSet());}
    private static String join(Collection<String> s){return String.join(" ",new TreeSet<>(s));}
    private static String random(int bytes){byte[] b=new byte[bytes]; new java.security.SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private static String hash(String value){try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void validText(String v,int max){if(v==null||v.isBlank()||v.length()>max||v.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid application field");}
}
