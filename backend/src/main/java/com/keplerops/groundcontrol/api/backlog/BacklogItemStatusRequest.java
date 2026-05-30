package com.keplerops.groundcontrol.api.backlog;

import com.keplerops.groundcontrol.domain.backlog.state.BacklogItemStatus;
import jakarta.validation.constraints.NotNull;

public record BacklogItemStatusRequest(@NotNull BacklogItemStatus status) {}
