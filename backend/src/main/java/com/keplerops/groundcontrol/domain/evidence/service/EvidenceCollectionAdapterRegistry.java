package com.keplerops.groundcontrol.domain.evidence.service;

import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionAdapter;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.plugins.service.PluginDescriptor;
import com.keplerops.groundcontrol.domain.plugins.service.PluginInfo;
import com.keplerops.groundcontrol.domain.plugins.service.PluginRegistry;
import com.keplerops.groundcontrol.domain.plugins.state.PluginLifecycleState;
import com.keplerops.groundcontrol.domain.plugins.state.PluginType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EvidenceCollectionAdapterRegistry {

    private final List<EvidenceCollectionAdapter> classpathAdapters;
    private final PluginRegistry pluginRegistry;

    public EvidenceCollectionAdapterRegistry(
            List<EvidenceCollectionAdapter> classpathAdapters, PluginRegistry pluginRegistry) {
        this.classpathAdapters = classpathAdapters;
        this.pluginRegistry = pluginRegistry;
    }

    public List<PluginInfo> listAdapters() {
        Map<String, PluginInfo> adaptersByName = classpathAdapterInfos();
        for (PluginInfo info : pluginRegistry.listByType(PluginType.EVIDENCE_COLLECTOR)) {
            adaptersByName.putIfAbsent(info.name(), info);
        }
        return List.copyOf(adaptersByName.values());
    }

    public List<PluginInfo> listAdapters(UUID projectId) {
        Map<String, PluginInfo> adaptersByName = classpathAdapterInfos();
        pluginRegistry.listPlugins(projectId).stream()
                .filter(info -> info.type() == PluginType.EVIDENCE_COLLECTOR)
                .forEach(info -> adaptersByName.putIfAbsent(info.name(), info));
        return List.copyOf(adaptersByName.values());
    }

    public EvidenceCollectionAdapter getAdapter(String name) {
        return classpathAdapters.stream()
                .filter(adapter -> adapter.descriptor().type() == PluginType.EVIDENCE_COLLECTOR)
                .filter(adapter -> adapter.descriptor().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Evidence collection adapter not found: " + name));
    }

    private Map<String, PluginInfo> classpathAdapterInfos() {
        Map<String, PluginInfo> adaptersByName = new LinkedHashMap<>();
        for (EvidenceCollectionAdapter adapter : classpathAdapters) {
            PluginDescriptor descriptor = adapter.descriptor();
            if (descriptor.type() != PluginType.EVIDENCE_COLLECTOR) {
                continue;
            }
            adaptersByName.putIfAbsent(
                    descriptor.name(),
                    new PluginInfo(
                            descriptor.name(),
                            descriptor.version(),
                            descriptor.description(),
                            descriptor.type(),
                            descriptor.capabilities() != null ? descriptor.capabilities() : Set.of(),
                            descriptor.metadata() != null ? descriptor.metadata() : Map.of(),
                            adapter.isAvailable() ? PluginLifecycleState.STARTED : PluginLifecycleState.FAILED,
                            adapter.isAvailable(),
                            true));
        }
        return adaptersByName;
    }
}
