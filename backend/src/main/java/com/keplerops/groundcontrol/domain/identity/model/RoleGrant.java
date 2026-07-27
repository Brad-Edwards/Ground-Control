package com.keplerops.groundcontrol.domain.identity.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.identity.state.RoleGrantState;
import com.keplerops.groundcontrol.domain.projects.model.Project;
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
import java.util.UUID;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

@Entity
@Audited
@Table(name = "role_grant")
public class RoleGrant extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false, updatable = false)
    private IdentityRole role;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", updatable = false)
    private IdentityUser user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_id", updatable = false)
    private IdentityGroup group;

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "project_id", insertable = false, updatable = false)
    private Project project;

    @Column(name = "project_id", updatable = false)
    private UUID auditedProjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoleGrantState state = RoleGrantState.ACTIVE;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_until")
    private Instant effectiveUntil;

    protected RoleGrant() {}

    private RoleGrant(
            IdentityRole role,
            IdentityUser user,
            IdentityGroup group,
            Project project,
            Instant effectiveFrom,
            Instant effectiveUntil) {
        if (role == null || (user == null) == (group == null)) {
            throw new DomainValidationException(
                    "Role grant requires a role and exactly one user or group", "invalid_role_grant_subject", Map.of());
        }
        EffectiveWindow.validate(effectiveFrom, effectiveUntil);
        this.role = role;
        this.user = user;
        this.group = group;
        this.project = project;
        this.auditedProjectId = project == null ? null : project.getId();
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
    }

    public static RoleGrant forUser(
            IdentityRole role, IdentityUser user, Project project, Instant effectiveFrom, Instant effectiveUntil) {
        return new RoleGrant(role, user, null, project, effectiveFrom, effectiveUntil);
    }

    public static RoleGrant forGroup(
            IdentityRole role, IdentityGroup group, Project project, Instant effectiveFrom, Instant effectiveUntil) {
        return new RoleGrant(role, null, group, project, effectiveFrom, effectiveUntil);
    }

    public void revoke() {
        state = RoleGrantState.REVOKED;
    }

    public IdentityRole getRole() {
        return role;
    }

    public IdentityUser getUser() {
        return user;
    }

    public IdentityGroup getGroup() {
        return group;
    }

    public Project getProject() {
        return project;
    }

    public UUID getProjectId() {
        return project == null ? auditedProjectId : project.getId();
    }

    public RoleGrantState getState() {
        return state;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveUntil() {
        return effectiveUntil;
    }
}
