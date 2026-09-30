package com.amhs.platform.api;
import com.amhs.platform.PrincipalResolver;
import com.amhs.platform.Role;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Who the caller is and what that lets them do.
 *
 * <p>A screen asks this so it can show what is available rather than offering everything and
 * letting the gate refuse. The gate still decides; this only describes the same rules so the two
 * cannot drift, and it never returns or echoes the token itself.
 */
@RestController
class SessionController {
    private final PrincipalResolver principals;
    SessionController(PrincipalResolver principals) { this.principals=principals; }

    public record Session(boolean authenticated, String code, Role role, List<String> allows) {}

    @GetMapping("/api/v1/session")
    Session current(@RequestHeader(value="Authorization",required=false) String authorization) {
        var principal=principals.resolve(authorization);
        var role=principal.map(p -> p.role()).orElse(Role.VIEWER);
        return new Session(principal.isPresent(), principal.map(p -> p.code()).orElse("anonymous"), role,
            AREAS.stream().filter(a -> role.allows(a.required())).map(Area::name).toList());
    }

    /** The areas a screen can offer, each named after what it changes. */
    private record Area(String name, Role required) {}
    private static final List<Area> AREAS=List.of(
        new Area("operation",Role.OPERATOR),   // assign, cancel, extend, report state, publish
        new Area("resources",Role.ENGINEER),   // vehicles, ports, connections, protocols
        new Area("equipment",Role.ENGINEER),   // equipment units, racks, slots
        new Area("topology",Role.ADMIN),       // nodes and segments
        new Area("system",Role.ADMIN),         // site-wide options
        new Area("accounts",Role.ADMIN));      // who may do any of this
}
