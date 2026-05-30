package com.keplerops.groundcontrol.domain.backlog.service;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.backlog.model.BacklogItem;
import com.keplerops.groundcontrol.domain.backlog.repository.BacklogItemRepository;
import com.keplerops.groundcontrol.domain.backlog.state.BacklogItemStatus;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BacklogItemService {

    private static final Logger log = LoggerFactory.getLogger(BacklogItemService.class);

    private final BacklogItemRepository repository;
    private final ProjectService projectService;

    public BacklogItemService(BacklogItemRepository repository, ProjectService projectService) {
        this.repository = repository;
        this.projectService = projectService;
    }

    public BacklogItem create(CreateBacklogItemCommand command) {
        var project = projectService.getById(command.projectId());
        if (repository.existsByProjectIdAndUid(project.getId(), command.uid())) {
            throw new ConflictException("BacklogItem with UID '" + command.uid() + "' already exists in project "
                    + project.getIdentifier());
        }
        var item = new BacklogItem(project, command.uid(), command.title());
        item.setDescription(command.description());
        item.setUserBusinessValue(command.userBusinessValue());
        item.setTimeCriticality(command.timeCriticality());
        item.setRiskReductionOpportunityEnablement(command.riskReductionOpportunityEnablement());
        item.setJobDuration(command.jobDuration());
        item.setCreatedBy(ActorHolder.get());
        var saved = repository.save(item);
        log.info(
                "backlog_item_created: project={} uid={} id={}",
                project.getIdentifier(),
                saved.getUid(),
                saved.getId());
        return saved;
    }

    public BacklogItem update(UUID projectId, UUID id, UpdateBacklogItemCommand command) {
        var item = findOrThrow(projectId, id);
        if (command.title() != null) {
            item.setTitle(command.title());
        }
        if (command.description() != null) {
            item.setDescription(command.description());
        }
        if (command.userBusinessValue() != null) {
            item.setUserBusinessValue(command.userBusinessValue());
        }
        if (command.timeCriticality() != null) {
            item.setTimeCriticality(command.timeCriticality());
        }
        if (command.riskReductionOpportunityEnablement() != null) {
            item.setRiskReductionOpportunityEnablement(command.riskReductionOpportunityEnablement());
        }
        if (command.jobDuration() != null) {
            item.setJobDuration(command.jobDuration());
        }
        var saved = repository.save(item);
        log.info("backlog_item_updated: id={} uid={}", saved.getId(), saved.getUid());
        return saved;
    }

    public BacklogItem transitionStatus(UUID projectId, UUID id, BacklogItemStatus target) {
        var item = findOrThrow(projectId, id);
        item.transitionStatus(target);
        var saved = repository.save(item);
        log.info("backlog_item_status_changed: id={} status={}", saved.getId(), saved.getStatus());
        return saved;
    }

    public void delete(UUID projectId, UUID id) {
        var item = findOrThrow(projectId, id);
        repository.delete(item);
        log.info("backlog_item_deleted: id={} uid={}", item.getId(), item.getUid());
    }

    @Transactional(readOnly = true)
    public BacklogItem getById(UUID projectId, UUID id) {
        return findOrThrow(projectId, id);
    }

    @Transactional(readOnly = true)
    public BacklogItem getByUid(UUID projectId, String uid) {
        return repository
                .findByProjectIdAndUid(projectId, uid)
                .orElseThrow(() -> new NotFoundException("BacklogItem not found: " + uid));
    }

    @Transactional(readOnly = true)
    public List<BacklogItem> listByProject(UUID projectId) {
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    private BacklogItem findOrThrow(UUID projectId, UUID id) {
        return repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("BacklogItem not found: " + id));
    }
}
