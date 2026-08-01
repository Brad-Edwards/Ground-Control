package com.keplerops.groundcontrol.domain.identity.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserKind;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserState;
import com.keplerops.groundcontrol.shared.security.UserCredentialPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.envers.Audited;

@Entity
@Audited
@Table(name = "identity_user")
public class IdentityUser extends BaseEntity {

    private static final String FIELD = "field";

    @Column(name = "login_name", nullable = false, length = 64, updatable = false, unique = true)
    private String loginName;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdentityUserKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdentityUserState state = IdentityUserState.ACTIVE;

    protected IdentityUser() {}

    public IdentityUser(String loginName, String displayName, IdentityUserKind kind) {
        if (loginName == null
                || !UserCredentialPolicy.USERNAME_PATTERN.matcher(loginName).matches()) {
            throw new DomainValidationException(
                    "Invalid identity login name", "invalid_identity_login", Map.of(FIELD, "loginName"));
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
            throw new DomainValidationException(
                    "Display name must contain 1 to 200 characters",
                    "invalid_identity_display_name",
                    Map.of(FIELD, "displayName"));
        }
        if (kind == null) {
            throw new DomainValidationException(
                    "Identity user kind is required", "invalid_identity_kind", Map.of(FIELD, "kind"));
        }
        this.loginName = loginName;
        this.displayName = displayName.trim();
        this.kind = kind;
    }

    public void setDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
            throw new DomainValidationException(
                    "Display name must contain 1 to 200 characters",
                    "invalid_identity_display_name",
                    Map.of(FIELD, "displayName"));
        }
        this.displayName = displayName.trim();
    }

    public void transitionTo(IdentityUserState target) {
        if (target == null) {
            throw new DomainValidationException("Identity user state is required");
        }
        if (state == IdentityUserState.DISABLED && target != IdentityUserState.DISABLED) {
            throw new DomainValidationException("Cannot transition an identity user from DISABLED to " + target);
        }
        this.state = target;
    }

    public String getLoginName() {
        return loginName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public IdentityUserKind getKind() {
        return kind;
    }

    public IdentityUserState getState() {
        return state;
    }
}
