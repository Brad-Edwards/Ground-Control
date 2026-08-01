package com.keplerops.groundcontrol.domain.identity.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.identity.state.ProjectAccessGrantState;
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
@Table(name = "project_access_grant")
public class ProjectAccessGrant extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", updatable = false)
    private IdentityUser user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_id", updatable = false)
    private IdentityGroup group;

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id", nullable = false, insertable = false, updatable = false)
    private Project project;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID auditedProjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectAccessGrantState state = ProjectAccessGrantState.ACTIVE;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_until")
    private Instant effectiveUntil;

    protected ProjectAccessGrant() {}

    private ProjectAccessGrant(
            IdentityUser user, IdentityGroup group, Project project, Instant effectiveFrom, Instant effectiveUntil) {
        if ((user == null) == (group == null) || project == null) {
            throw new DomainValidationException(
                    "Project access grant requires a project and exactly one user or group",
                    "invalid_project_access_grant_subject",
                    Map.of());
        }
        EffectiveWindow.validate(effectiveFrom, effectiveUntil);
        this.user = user;
        this.group = group;
        this.project = project;
        this.auditedProjectId = project.getId();
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
    }

    public static ProjectAccessGrant forUser(
            IdentityUser user, Project project, Instant effectiveFrom, Instant effectiveUntil) {
        return new ProjectAccessGrant(user, null, project, effectiveFrom, effectiveUntil);
    }

    public static ProjectAccessGrant forGroup(
            IdentityGroup group, Project project, Instant effectiveFrom, Instant effectiveUntil) {
        return new ProjectAccessGrant(null, group, project, effectiveFrom, effectiveUntil);
    }

    public void revoke() {
        state = ProjectAccessGrantState.REVOKED;
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

    public ProjectAccessGrantState getState() {
        return state;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveUntil() {
        return effectiveUntil;
    }
}
