package com.keplerops.groundcontrol.domain.identity.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.identity.state.IdentityRoleState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.envers.Audited;

@Entity
@Audited
@Table(name = "identity_role")
public class IdentityRole extends BaseEntity {

    @Column(name = "role_key", nullable = false, length = 64, updatable = false, unique = true)
    private String key;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(length = 1000)
    private String description;

    @Column(name = "built_in", nullable = false, updatable = false)
    private boolean builtIn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdentityRoleState state = IdentityRoleState.ACTIVE;

    protected IdentityRole() {}

    public IdentityRole(String key, String displayName) {
        if (key == null || !key.matches("^[A-Z][A-Z0-9_]{1,63}$")) {
            throw new DomainValidationException(
                    "Invalid identity role key", "invalid_identity_role_key", Map.of("field", "key"));
        }
        this.key = key;
        setDisplayName(displayName);
    }

    public void setDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
            throw new DomainValidationException(
                    "Role display name must contain 1 to 200 characters",
                    "invalid_identity_role_display_name",
                    Map.of("field", "displayName"));
        }
        this.displayName = displayName.trim();
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void transitionTo(IdentityRoleState target) {
        if (target == null) {
            throw new DomainValidationException("Identity role state is required");
        }
        this.state = target;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public IdentityRoleState getState() {
        return state;
    }
}
