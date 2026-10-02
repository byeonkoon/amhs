package com.amhs.platform.api;
import com.amhs.platform.Logins;
import com.amhs.platform.PrincipalResolver;
import com.amhs.platform.Role;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Logging in and out, and what the current login may do.
 *
 * <p>The areas are listed from the same levels the gate enforces, so a screen can offer what is
 * available instead of offering everything and letting the gate refuse. The gate still decides;
 * this only describes the same rules, and never returns a password.
 */
@RestController
class SessionController {
    private final PrincipalResolver principals; private final Logins logins;
    SessionController(PrincipalResolver principals,Logins logins) { this.principals=principals; this.logins=logins; }

    public record Session(boolean authenticated, String code, Role role, int level, List<String> allows) {}
    public record Credentials(String code, String password) {}
    /** The session value is in this reply and nowhere else; the browser holds it for its lifetime. */
    public record LoggedIn(String token, OffsetDateTime expiresAt, Session session) {}

    @GetMapping("/api/v1/session")
    Session current(@RequestHeader(value="Authorization",required=false) String authorization) {
        var principal=principals.resolve(authorization);
        var role=principal.map(p -> p.role()).orElse(Role.VIEWER);
        return describe(principal.isPresent(),principal.map(p -> p.code()).orElse("anonymous"),role);
    }

    @PostMapping("/api/v1/sites/{siteId}/session")
    LoggedIn logIn(@PathVariable long siteId,@RequestBody Credentials credentials) {
        var session=logins.logIn(siteId,credentials.code(),credentials.password());
        var p=session.principal();
        return new LoggedIn(session.token(),session.expiresAt(),describe(true,p.code(),p.role()));
    }

    @DeleteMapping("/api/v1/session")
    Map<String,Boolean> logOut(@RequestHeader(value="Authorization",required=false) String authorization) {
        if(authorization!=null&&authorization.startsWith("Bearer "))
            logins.logOut(PrincipalResolver.sha256Hex(authorization.substring(7)));
        return Map.of("endedSession",true);
    }

    private static Session describe(boolean authenticated,String code,Role role) {
        return new Session(authenticated,code,role,role.level(),
            AREAS.stream().filter(a -> role.allows(a.required())).map(Area::name).toList());
    }

    /** Each area named for what it governs, with the level it takes. */
    private record Area(String name, Role required) {}
    private static final List<Area> AREAS=List.of(
        new Area("monitor",Role.VIEWER),      // fleet, alarms, history, diagnostics
        new Area("operation",Role.OPERATOR),  // move, dispatch, cancel, extend, report, transport, traffic
        new Area("inventory",Role.MANAGER),   // what is being carried and stored
        new Area("resources",Role.ADMIN),     // vehicles, ports, connections, protocols
        new Area("equipment",Role.ADMIN),     // equipment units, racks, slots
        new Area("topology",Role.ADMIN),      // nodes and segments
        new Area("system",Role.ADMIN),        // site-wide options
        new Area("accounts",Role.ADMIN));     // who may log in
}
