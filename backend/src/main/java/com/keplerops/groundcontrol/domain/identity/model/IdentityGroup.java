package com.keplerops.groundcontrol.domain.identity.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.identity.state.IdentityGroupState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.envers.Audited;

@Entity
@Audited
@Table(name = "identity_group")
public class IdentityGroup extends BaseEntity {

    @Column(nullable = false, length = 100, updatable = false, unique = true)
    private String name;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdentityGroupState state = IdentityGroupState.ACTIVE;

    protected IdentityGroup() {}

    public IdentityGroup(String name, String displayName) {
        if (name == null || !name.matches("^[a-z][a-z0-9._-]{1,99}$")) {
            throw new DomainValidationException(
                    "Invalid identity group name", "invalid_identity_group_name", Map.of("field", "name"));
        }
        this.name = name;
        setDisplayName(displayName);
    }

    public void setDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
            throw new DomainValidationException(
                    "Group display name must contain 1 to 200 characters",
                    "invalid_identity_group_display_name",
                    Map.of("field", "displayName"));
        }
        this.displayName = displayName.trim();
    }

    public void transitionTo(IdentityGroupState target) {
        if (target == null) {
            throw new DomainValidationException("Identity group state is required");
        }
        this.state = target;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public IdentityGroupState getState() {
        return state;
    }
}
