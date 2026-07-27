package com.keplerops.groundcontrol.domain.identity.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.identity.state.GroupMembershipState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.envers.Audited;

@Entity
@Audited
@Table(name = "group_membership")
public class GroupMembership extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private IdentityUser user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "group_id", nullable = false, updatable = false)
    private IdentityGroup group;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupMembershipState state = GroupMembershipState.ACTIVE;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_until")
    private Instant effectiveUntil;

    protected GroupMembership() {}

    public GroupMembership(IdentityUser user, IdentityGroup group, Instant effectiveFrom, Instant effectiveUntil) {
        if (user == null || group == null) {
            throw new DomainValidationException(
                    "Membership requires a user and group", "invalid_group_membership", Map.of());
        }
        EffectiveWindow.validate(effectiveFrom, effectiveUntil);
        this.user = user;
        this.group = group;
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
    }

    public void revoke() {
        this.state = GroupMembershipState.REVOKED;
    }

    public IdentityUser getUser() {
        return user;
    }

    public IdentityGroup getGroup() {
        return group;
    }

    public GroupMembershipState getState() {
        return state;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveUntil() {
        return effectiveUntil;
    }
}
