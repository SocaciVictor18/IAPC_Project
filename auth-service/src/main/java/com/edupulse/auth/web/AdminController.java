package com.edupulse.auth.web;

import com.edupulse.auth.domain.UserAccount;
import com.edupulse.auth.repository.UserRepository;
import com.edupulse.common.security.Roles;
import com.edupulse.common.security.TenantContext;
import com.edupulse.common.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** User management, restricted to ROLE_ADMIN and to the admin's own organization. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public record UserView(UUID id, String email, String fullName, UUID orgUnitId, String orgUnitCode,
                           int clearanceLevel, boolean enabled, Set<String> roles) {
        static UserView of(UserAccount u) {
            return new UserView(u.id(), u.email(), u.fullName(), u.orgUnitId(), u.orgUnitCode(),
                    u.clearanceLevel(), u.enabled(), u.roles());
        }
    }

    public record CreateUser(@NotBlank @Email String email,
                             @NotBlank @Size(min = 10, message = "must have at least 10 characters") String password,
                             String fullName,
                             @NotNull UUID orgUnitId,
                             @Min(1) @Max(5) int clearanceLevel,
                             @NotEmpty Set<String> roles) {
    }

    public record UpdateUser(@Min(1) @Max(5) Integer clearanceLevel, Boolean enabled, Set<String> roles, UUID orgUnitId) {
    }

    @GetMapping("/users")
    public List<UserView> list() {
        return users.findByOrganization(TenantContext.current().organizationId()).stream().map(UserView::of).toList();
    }

    @GetMapping("/org-units")
    public List<Map<String, Object>> orgUnits() {
        return users.orgUnits(TenantContext.current().organizationId());
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUser req) {
        TenantContext admin = TenantContext.current();
        validateRoles(req.roles());
        requireOwnOrgUnit(req.orgUnitId(), admin);
        if (users.findByEmail(req.email()).isPresent()) {
            throw ApiException.conflict("E-mail already registered");
        }
        UUID id = users.create(req.email(), passwordEncoder.encode(req.password()), req.fullName(),
                req.orgUnitId(), req.clearanceLevel(), req.roles());
        return UserView.of(users.findById(id).orElseThrow());
    }

    @PatchMapping("/users/{id}")
    public UserView update(@PathVariable UUID id, @Valid @RequestBody UpdateUser req) {
        TenantContext admin = TenantContext.current();
        UserAccount target = users.findById(id).filter(u -> u.organizationId().equals(admin.organizationId()))
                .orElseThrow(() -> ApiException.notFound("User"));
        if (req.roles() != null) {
            validateRoles(req.roles());
        }
        if (req.orgUnitId() != null) {
            requireOwnOrgUnit(req.orgUnitId(), admin);
        }
        if (target.id().equals(admin.userId()) && Boolean.FALSE.equals(req.enabled())) {
            throw ApiException.badRequest("Admins cannot disable themselves");
        }
        users.update(id, req.clearanceLevel(), req.enabled(), req.roles(), req.orgUnitId());
        return UserView.of(users.findById(id).orElseThrow());
    }

    private void requireOwnOrgUnit(UUID orgUnitId, TenantContext admin) {
        if (!users.orgUnitBelongsTo(orgUnitId, admin.organizationId())) {
            throw ApiException.badRequest("Org unit does not belong to your organization");
        }
    }

    private static void validateRoles(Set<String> roles) {
        if (!Roles.USER_ROLES.containsAll(roles)) {
            throw ApiException.badRequest("Allowed roles: " + Roles.USER_ROLES);
        }
    }
}
