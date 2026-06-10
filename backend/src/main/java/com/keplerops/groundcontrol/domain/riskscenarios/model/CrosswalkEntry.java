package com.keplerops.groundcontrol.domain.riskscenarios.model;

import com.keplerops.groundcontrol.domain.riskscenarios.state.CrosswalkVocabularySurface;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NormalizedConcept;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A single crosswalk mapping between a normalized risk concept and one
 * concrete field in a {@link MethodologyProfile}'s vocabulary surface (GC-T012 C2).
 * Instances are stored as a JSON list on the profile via
 * {@link com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.CrosswalkEntryListConverter}.
 */
public record CrosswalkEntry(
        @NotNull NormalizedConcept normalizedConcept,
        @NotNull CrosswalkVocabularySurface vocabularySurface,
        @NotBlank @Size(max = 400) String sourceFieldPath,
        @Size(max = 200) String sourceTermLabel,
        @Size(max = 2000) String sourceTermDefinition,
        @Size(max = 100) String scale,
        @Size(max = 100) String units,
        @Size(max = 400) String conversionRule,
        @Size(max = 400) String limitations) {}
