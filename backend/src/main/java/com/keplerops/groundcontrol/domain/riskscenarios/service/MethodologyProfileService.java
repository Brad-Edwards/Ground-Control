package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.CrosswalkEntry;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.CrosswalkVocabularySurface;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyProfileStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NormalizedConcept;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MethodologyProfileService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // @formatter:off
    private static final String FAIR_INPUT_SCHEMA =
            """
            {
              "type": "object",
              "description": "FAIR v3.0 input factors with FAIR-CAM and FAIR-MAM extensions",
              "properties": {
                "threat_event_frequency": {
                  "type": "object",
                  "description": "Estimated annual frequency of the threat event occurring",
                  "properties": {
                    "low": {"type": "number", "minimum": 0},
                    "likely": {"type": "number", "minimum": 0},
                    "high": {"type": "number", "minimum": 0},
                    "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
                  },
                  "required": ["low", "likely", "high"]
                },
                "vulnerability": {
                  "type": "object",
                  "description": "Probability that a threat event becomes a loss event (0.0-1.0)",
                  "properties": {
                    "low": {"type": "number", "minimum": 0, "maximum": 1},
                    "likely": {"type": "number", "minimum": 0, "maximum": 1},
                    "high": {"type": "number", "minimum": 0, "maximum": 1},
                    "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
                  },
                  "required": ["low", "likely", "high"]
                },
                "loss_event_frequency": {
                  "type": "object",
                  "description": "Derived: TEF * Vulnerability. May be supplied directly if pre-calculated.",
                  "properties": {
                    "low": {"type": "number", "minimum": 0},
                    "likely": {"type": "number", "minimum": 0},
                    "high": {"type": "number", "minimum": 0},
                    "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
                  }
                },
                "primary_loss_magnitude": {
                  "type": "object",
                  "description": "Direct monetary loss from a single loss event",
                  "properties": {
                    "low": {"type": "number", "minimum": 0},
                    "likely": {"type": "number", "minimum": 0},
                    "high": {"type": "number", "minimum": 0},
                    "currency": {"type": "string", "default": "USD"},
                    "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
                  },
                  "required": ["low", "likely", "high"]
                },
                "secondary_loss_event_frequency": {
                  "type": "object",
                  "description": "Probability that a primary loss event triggers secondary losses (0.0-1.0)",
                  "properties": {
                    "low": {"type": "number", "minimum": 0, "maximum": 1},
                    "likely": {"type": "number", "minimum": 0, "maximum": 1},
                    "high": {"type": "number", "minimum": 0, "maximum": 1},
                    "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
                  }
                },
                "secondary_loss_magnitude": {
                  "type": "object",
                  "description": "Monetary loss from secondary effects (regulatory, reputational, etc.)",
                  "properties": {
                    "low": {"type": "number", "minimum": 0},
                    "likely": {"type": "number", "minimum": 0},
                    "high": {"type": "number", "minimum": 0},
                    "currency": {"type": "string", "default": "USD"},
                    "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
                  }
                },
                "fair_cam": {
                  "type": "object",
                  "description": "FAIR Control Analytics Model (FAIR-CAM) inputs for deriving Vulnerability",
                  "properties": {
                    "control_strength": {
                      "type": "number", "minimum": 0, "maximum": 100,
                      "description": "Aggregate control effectiveness percentage (0-100)"
                    },
                    "control_coverage": {
                      "type": "number", "minimum": 0, "maximum": 1,
                      "description": "Fraction of the attack surface covered by controls (0.0-1.0)"
                    }
                  }
                },
                "fair_mam": {
                  "type": "object",
                  "description": "FAIR Materiality Assessment Model (FAIR-MAM) loss magnitude breakdown",
                  "properties": {
                    "productivity_loss": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
                    "response_cost": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
                    "replacement_cost": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
                    "competitive_advantage_loss": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
                    "fines_and_judgments": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
                    "reputation_damage": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}}
                  }
                },
                "contact_frequency": {
                  "type": "object",
                  "description": "Component of Threat Event Frequency; TEF = Contact Frequency × Probability of Action",
                  "properties": {
                    "low": {"type": "number", "minimum": 0},
                    "likely": {"type": "number", "minimum": 0},
                    "high": {"type": "number", "minimum": 0},
                    "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
                  }
                },
                "probability_of_action": {
                  "type": "object",
                  "description": "Component of Threat Event Frequency; TEF = Contact Frequency × Probability of Action",
                  "properties": {
                    "low": {"type": "number", "minimum": 0},
                    "likely": {"type": "number", "minimum": 0},
                    "high": {"type": "number", "minimum": 0},
                    "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
                  }
                },
                "threat_capability": {
                  "type": "object",
                  "description": "Component of Vulnerability; Vuln = P(Threat Capability > Resistance Strength)",
                  "properties": {
                    "low": {"type": "number", "minimum": 0, "maximum": 1},
                    "likely": {"type": "number", "minimum": 0, "maximum": 1},
                    "high": {"type": "number", "minimum": 0, "maximum": 1},
                    "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
                  }
                },
                "resistance_strength": {
                  "type": "object",
                  "description": "Component of Vulnerability; Vuln = P(Threat Capability > Resistance Strength)",
                  "properties": {
                    "low": {"type": "number", "minimum": 0, "maximum": 1},
                    "likely": {"type": "number", "minimum": 0, "maximum": 1},
                    "high": {"type": "number", "minimum": 0, "maximum": 1},
                    "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
                  }
                }
              },
              "required": ["threat_event_frequency", "vulnerability", "primary_loss_magnitude"],
              "semantics": {
                "scale": "continuous",
                "units": "monetary",
                "currency": "configurable (default USD)",
                "estimation_method": "three-point (low/likely/high) with optional Monte Carlo simulation"
              }
            }""";

    private static final String FAIR_OUTPUT_SCHEMA =
            """
            {
              "type": "object",
              "description": "FAIR v3.0 computed risk outputs",
              "properties": {
                "annualized_loss_expectancy": {
                  "type": "object",
                  "description": "Expected annual monetary loss",
                  "properties": {
                    "low": {"type": "number"},
                    "likely": {"type": "number"},
                    "high": {"type": "number"},
                    "currency": {"type": "string"},
                    "percentiles": {
                      "type": "object",
                      "description": "Optional Monte Carlo simulation percentiles",
                      "properties": {
                        "p10": {"type": "number"},
                        "p50": {"type": "number"},
                        "p90": {"type": "number"},
                        "p95": {"type": "number"}
                      }
                    }
                  },
                  "required": ["low", "likely", "high"]
                },
                "loss_event_frequency": {
                  "type": "object",
                  "description": "Computed annual loss event frequency",
                  "properties": {
                    "low": {"type": "number"},
                    "likely": {"type": "number"},
                    "high": {"type": "number"}
                  },
                  "required": ["low", "likely", "high"]
                },
                "loss_magnitude": {
                  "type": "object",
                  "description": "Computed single-event loss magnitude",
                  "properties": {
                    "low": {"type": "number"},
                    "likely": {"type": "number"},
                    "high": {"type": "number"},
                    "currency": {"type": "string"}
                  },
                  "required": ["low", "likely", "high"]
                },
                "risk_level": {
                  "type": "string",
                  "description": "Derived qualitative risk level for communication",
                  "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH", "CRITICAL"]
                }
              },
              "required": ["annualized_loss_expectancy", "loss_event_frequency", "loss_magnitude"],
              "semantics": {
                "scale": "continuous",
                "units": "monetary",
                "derivation": "ALE = LEF * LM; LEF = TEF * Vuln; LM = PLM + (SLEF * SLM)"
              }
            }""";

    // NIST SP 800-30 Rev. 1 assessment input vocabulary. Encodes the full
    // Rev. 1 decomposition required by GC-T014: threat sources, threat events
    // (adversarial vs non-adversarial), vulnerabilities, predisposing
    // conditions, threat-source relevance, likelihood of initiation,
    // likelihood of adverse impact, overall likelihood (optionally derived
    // per Table G-5), impact level, and assessment timeframe.
    static final String NIST_INPUT_SCHEMA =
            """
            {
              "type": "object",
              "description": "NIST SP 800-30 Rev. 1 assessment inputs (full Rev. 1 decomposition per GC-T014)",
              "properties": {
                "threat_source": {
                  "type": "object",
                  "description": "Threat source per NIST SP 800-30 Rev. 1 Appendix D",
                  "properties": {
                    "id": {"type": "string"},
                    "name": {"type": "string"},
                    "kind": {
                      "type": "string",
                      "description": "Adversarial or non-adversarial (NIST Table D-2 / Table D-7)",
                      "enum": ["ADVERSARIAL", "NON_ADVERSARIAL"]
                    }
                  }
                },
                "threat_event": {
                  "type": "object",
                  "description": "Threat event per NIST SP 800-30 Rev. 1 Appendix E",
                  "properties": {
                    "id": {"type": "string"},
                    "description": {"type": "string"},
                    "kind": {
                      "type": "string",
                      "description": "Adversarial or non-adversarial threat event (NIST Table E-2 / Table E-3)",
                      "enum": ["ADVERSARIAL", "NON_ADVERSARIAL"]
                    }
                  }
                },
                "threat_event_kind": {
                  "type": "string",
                  "description": "Short discriminator mirrored on the event for non-recursive consumers",
                  "enum": ["ADVERSARIAL", "NON_ADVERSARIAL"]
                },
                "threat_source_characteristics": {
                  "type": "object",
                  "description": "Adversarial threat source attributes (NIST Table D-3..D-5); not applicable to non-adversarial events",
                  "properties": {
                    "capability": {"type": "string", "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]},
                    "intent": {"type": "string", "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]},
                    "targeting": {"type": "string", "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]}
                  }
                },
                "threat_source_relevance": {
                  "type": "string",
                  "description": "Threat source relevance per NIST Table D-2",
                  "enum": ["CONFIRMED", "EXPECTED", "ANTICIPATED", "PREDICTED", "POSSIBLE", "NOT_APPLICABLE"]
                },
                "vulnerabilities": {
                  "type": "array",
                  "description": "Vulnerabilities exposed to the threat event (NIST Appendix F)",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {"type": "string"},
                      "description": {"type": "string"},
                      "severity": {"type": "string", "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]},
                      "predisposing_condition_ids": {"type": "array", "items": {"type": "string"}}
                    }
                  }
                },
                "predisposing_conditions": {
                  "type": "array",
                  "description": "Predisposing conditions affecting exploitation (NIST Appendix F)",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {"type": "string"},
                      "description": {"type": "string"},
                      "pervasiveness": {
                        "type": "string",
                        "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]
                      }
                    }
                  }
                },
                "likelihood_initiation": {
                  "type": "string",
                  "description": "Likelihood that the threat source initiates the event / event occurs (NIST Table G-2)",
                  "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]
                },
                "likelihood_adverse_impact": {
                  "type": "string",
                  "description": "Likelihood that the threat event results in adverse impact (NIST Table G-3)",
                  "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]
                },
                "likelihood_overall": {
                  "type": "string",
                  "description": "Optional analyst-supplied overall likelihood; derived per Table G-5 when absent",
                  "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]
                },
                "impact_level": {
                  "type": "string",
                  "description": "Impact level per NIST Table H-3",
                  "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]
                },
                "assessment_timeframe": {
                  "type": "object",
                  "description": "Time window over which the assessment is judged",
                  "properties": {
                    "from": {"type": "string", "format": "date"},
                    "to": {"type": "string", "format": "date"}
                  }
                }
              },
              "required": ["impact_level"],
              "semantics": {
                "scale": "ordinal",
                "levels": 5,
                "level_labels": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"],
                "score_range": {"min": 1, "max": 5},
                "units": "qualitative ordinal levels",
                "adversarial_branch_fields": ["threat_source_characteristics"],
                "non_adversarial_branch_fields": []
              }
            }""";

    static final String NIST_OUTPUT_SCHEMA =
            """
            {
              "type": "object",
              "description": "NIST SP 800-30 Rev. 1 risk determination outputs (methodology-attributed per GC-L007 result contract)",
              "properties": {
                "overall_likelihood": {
                  "type": "string",
                  "description": "Overall likelihood band (analyst-supplied or derived per Table G-5)",
                  "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]
                },
                "impact_level": {
                  "type": "string",
                  "description": "Impact band echoed from inputs for analyst convenience",
                  "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]
                },
                "risk_level": {
                  "type": "string",
                  "description": "Overall risk level per NIST SP 800-30 Rev. 1 Table I-2",
                  "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]
                },
                "matrix_cell": {
                  "type": "string",
                  "description": "Matrix cell reference (e.g., L3-I4 for overall_likelihood=MODERATE, impact_level=HIGH)"
                },
                "derivation": {
                  "type": "string",
                  "description": "Provenance: analyst-supplied or derived per Table G-5"
                }
              },
              "required": ["risk_level"],
              "semantics": {
                "scale": "ordinal",
                "units": "qualitative ordinal levels",
                "derivation_method": "nist-sp800-30-rev1-5x5-matrix-v1",
                "matrix_conversion_rule": "overall_likelihood × impact_level → risk_level per NIST SP 800-30 Rev. 1 Table I-2",
                "no_numeric_score": "NIST ordinal bands must not be normalized into a cross-methodology numeric score without an explicit method label and conversion rule"
              }
            }""";

    private static final String ISO_INPUT_SCHEMA =
            """
            {
              "type": "object",
              "description": "ISO 27005:2022 risk assessment inputs (ISO 27001-compatible)",
              "properties": {
                "likelihood": {
                  "type": "object",
                  "description": "Likelihood of threat exploitation of vulnerability",
                  "properties": {
                    "level": {"type": "string", "enum": ["VERY_LOW", "LOW", "MEDIUM", "HIGH", "VERY_HIGH"]},
                    "score": {"type": "integer", "minimum": 1, "maximum": 5}
                  },
                  "required": ["level", "score"]
                },
                "consequence": {
                  "type": "object",
                  "description": "Business consequence/impact of the risk event (ISO 27005 terminology)",
                  "properties": {
                    "level": {"type": "string", "enum": ["NEGLIGIBLE", "MINOR", "MODERATE", "MAJOR", "SEVERE"]},
                    "score": {"type": "integer", "minimum": 1, "maximum": 5}
                  },
                  "required": ["level", "score"]
                },
                "asset_value": {
                  "type": "object",
                  "description": "Value classification of the information asset at risk",
                  "properties": {
                    "level": {"type": "string", "enum": ["VERY_LOW", "LOW", "MEDIUM", "HIGH", "VERY_HIGH"]},
                    "score": {"type": "integer", "minimum": 1, "maximum": 5}
                  }
                },
                "threat_level": {
                  "type": "object",
                  "description": "Assessed threat level for the applicable threat",
                  "properties": {
                    "level": {"type": "string", "enum": ["VERY_LOW", "LOW", "MEDIUM", "HIGH", "VERY_HIGH"]},
                    "score": {"type": "integer", "minimum": 1, "maximum": 5}
                  }
                },
                "vulnerability_level": {
                  "type": "object",
                  "description": "Assessed vulnerability severity level",
                  "properties": {
                    "level": {"type": "string", "enum": ["VERY_LOW", "LOW", "MEDIUM", "HIGH", "VERY_HIGH"]},
                    "score": {"type": "integer", "minimum": 1, "maximum": 5}
                  }
                },
                "existing_controls": {
                  "type": "string",
                  "description": "Description of existing ISO 27001 Annex A controls mitigating this risk"
                }
              },
              "required": ["likelihood", "consequence"],
              "semantics": {
                "scale": "ordinal or organization-defined",
                "levels": 5,
                "units": "organization-defined risk criteria",
                "iso_27001_alignment": "Supports ISO 27001:2022 clause 6.1.2 risk assessment process"
              }
            }""";

    private static final String ISO_OUTPUT_SCHEMA =
            """
            {
              "type": "object",
              "description": "ISO 27005:2022 risk evaluation outputs",
              "properties": {
                "risk_value": {
                  "type": "integer",
                  "description": "Numeric risk value (likelihood.score * consequence.score)",
                  "minimum": 1,
                  "maximum": 25
                },
                "risk_level": {
                  "type": "string",
                  "description": "Qualitative risk level derived from organization-defined thresholds",
                  "enum": ["LOW", "MEDIUM", "HIGH", "VERY_HIGH", "CRITICAL"]
                },
                "risk_acceptance_criteria": {
                  "type": "object",
                  "description": "Organization-defined risk acceptance thresholds",
                  "properties": {
                    "acceptable_threshold": {"type": "integer", "description": "Maximum risk_value for automatic acceptance"},
                    "tolerable_threshold": {"type": "integer", "description": "Maximum risk_value before mandatory treatment"},
                    "outcome": {"type": "string", "enum": ["ACCEPTABLE", "TOLERABLE", "UNACCEPTABLE"]}
                  }
                },
                "risk_matrix_cell": {
                  "type": "string",
                  "description": "Matrix cell reference (e.g., L3-C4 for likelihood=3, consequence=4)"
                }
              },
              "required": ["risk_value", "risk_level"],
              "semantics": {
                "scale": "ordinal with organization-defined thresholds",
                "derivation": "risk_value = likelihood.score * consequence.score",
                "level_mapping": {
                  "1-4": "LOW",
                  "5-9": "MEDIUM",
                  "10-14": "HIGH",
                  "15-19": "VERY_HIGH",
                  "20-25": "CRITICAL"
                },
                "iso_27001_alignment": "Maps to ISO 27001:2022 clause 6.1.2 and 8.2 risk treatment decisions"
              }
            }""";

    private static final String LEGACY_INPUT_SCHEMA =
            """
            {
              "type": "object",
              "description": "Free-form input factors for legacy qualitative assessments",
              "additionalProperties": true,
              "semantics": {
                "scale": "unstructured",
                "units": "free-form",
                "note": "This profile accepts any input structure for backwards compatibility"
              }
            }""";

    private static final String LEGACY_OUTPUT_SCHEMA =
            """
            {
              "type": "object",
              "description": "Free-form computed outputs for legacy qualitative assessments",
              "additionalProperties": true,
              "semantics": {
                "scale": "unstructured",
                "units": "free-form",
                "note": "This profile accepts any output structure for backwards compatibility"
              }
            }""";
    // @formatter:on

    private final MethodologyProfileRepository repository;
    private final ProjectService projectService;

    public MethodologyProfileService(MethodologyProfileRepository repository, ProjectService projectService) {
        this.repository = repository;
        this.projectService = projectService;
    }

    public MethodologyProfile create(CreateMethodologyProfileCommand command) {
        var project = projectService.getById(command.projectId());
        if (repository.existsByProjectIdAndProfileKeyAndVersion(
                project.getId(), command.profileKey(), command.version())) {
            throw new ConflictException(
                    "Methodology profile " + command.profileKey() + "@" + command.version() + " already exists");
        }
        var profile = new MethodologyProfile(
                project, command.profileKey(), command.name(), command.version(), command.family());
        applyUpdates(
                profile,
                command.description(),
                command.inputSchema(),
                command.outputSchema(),
                command.status(),
                command.treatmentStrategyVocabulary(),
                command.crosswalkEntries());
        return repository.save(profile);
    }

    @Transactional(readOnly = true)
    public List<MethodologyProfile> listByProject(UUID projectId) {
        ensureSeeded(projectId);
        return repository.findByProjectIdOrderByNameAscVersionDesc(projectId);
    }

    @Transactional(readOnly = true)
    public MethodologyProfile getById(UUID projectId, UUID id) {
        return repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Methodology profile not found: " + id));
    }

    public MethodologyProfile update(UUID projectId, UUID id, UpdateMethodologyProfileCommand command) {
        var profile = getById(projectId, id);
        if (command.name() != null) {
            profile.setName(command.name());
        }
        if (command.version() != null) {
            profile.setVersion(command.version());
        }
        if (command.family() != null) {
            profile.setFamily(command.family());
        }
        applyUpdates(
                profile,
                command.description(),
                command.inputSchema(),
                command.outputSchema(),
                command.status(),
                command.treatmentStrategyVocabulary(),
                command.crosswalkEntries());
        return repository.save(profile);
    }

    public void delete(UUID projectId, UUID id) {
        repository.delete(getById(projectId, id));
    }

    // GC-T012 crosswalk semantic constants (shared scale/units literals across seeds)
    private static final String SCALE_CONTINUOUS = "continuous";
    private static final String SCALE_QUALITATIVE_ORDINAL = "qualitative ordinal";
    private static final String SCALE_ORG_DEFINED_ORDINAL = "organization-defined ordinal";
    private static final String UNITS_FIVE_LEVEL_ORDINAL = "5-level ordinal (VERY_LOW–VERY_HIGH)";
    private static final String UNITS_ORG_DEFINED_ORDINAL_LEVELS = "organization-defined ordinal levels";

    // Crosswalk validation error-detail keys + JSON-schema walker keys
    private static final String DETAIL_KEY_VOCABULARY_SURFACE = "vocabularySurface";
    private static final String DETAIL_KEY_SOURCE_FIELD_PATH = "sourceFieldPath";
    private static final String SCHEMA_KEY_PROPERTIES = "properties";
    private static final String SCHEMA_KEY_ITEMS = "items";
    private static final String SCHEMA_KEY_ADDITIONAL_PROPERTIES = "additionalProperties";
    private static final String TREATMENT_SURFACE_NAME = "TREATMENT_STRATEGY_VOCABULARY";

    // @formatter:off
    // GC-T012 seed crosswalk entries for FAIR_V3_0
    private static final List<CrosswalkEntry> FAIR_CROSSWALK_ENTRIES = List.of(
            new CrosswalkEntry(
                    NormalizedConcept.THREAT_EVENT,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "threat_event_frequency",
                    "Threat Event Frequency",
                    "Estimated annual frequency of the threat event occurring",
                    SCALE_CONTINUOUS,
                    "annual events",
                    null,
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.VULNERABILITY_OR_EXPOSURE,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "vulnerability",
                    "Vulnerability",
                    "Probability that a threat event becomes a loss event (0.0-1.0)",
                    SCALE_CONTINUOUS,
                    "probability (0.0–1.0)",
                    null,
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "loss_event_frequency",
                    "Loss Event Frequency",
                    "Derived: TEF × Vulnerability. May be supplied directly if pre-calculated.",
                    SCALE_CONTINUOUS,
                    "annual events",
                    "LEF = TEF × Vulnerability",
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.IMPACT_OR_LOSS_MAGNITUDE,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "primary_loss_magnitude",
                    "Primary Loss Magnitude",
                    "Direct monetary loss from a single loss event",
                    SCALE_CONTINUOUS,
                    "monetary",
                    null,
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.IMPACT_OR_LOSS_MAGNITUDE,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "secondary_loss_magnitude",
                    "Secondary Loss Magnitude",
                    "Monetary loss from secondary effects (regulatory, reputational, etc.)",
                    SCALE_CONTINUOUS,
                    "monetary",
                    null,
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.CONTROL,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "fair_cam.control_strength",
                    "FAIR-CAM Control Strength",
                    "Aggregate control effectiveness percentage (0-100)",
                    SCALE_CONTINUOUS,
                    "percentage (0–100)",
                    null,
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.CONTROL,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "fair_cam.control_coverage",
                    "FAIR-CAM Control Coverage",
                    "Fraction of the attack surface covered by controls (0.0-1.0)",
                    SCALE_CONTINUOUS,
                    "coverage fraction (0.0–1.0)",
                    null,
                    null));

    // GC-T012 seed crosswalk entries for NIST_SP800_30_R1
    private static final List<CrosswalkEntry> NIST_CROSSWALK_ENTRIES = List.of(
            new CrosswalkEntry(
                    NormalizedConcept.THREAT_SOURCE,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "threat_source",
                    "Threat Source",
                    "Threat source per NIST SP 800-30 Rev. 1 Appendix D",
                    SCALE_QUALITATIVE_ORDINAL,
                    UNITS_FIVE_LEVEL_ORDINAL,
                    null,
                    "5-level ordinal, no continuous frequency"),
            new CrosswalkEntry(
                    NormalizedConcept.THREAT_EVENT,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "threat_event",
                    "Threat Event",
                    "Threat event per NIST SP 800-30 Rev. 1 Appendix E",
                    SCALE_QUALITATIVE_ORDINAL,
                    UNITS_FIVE_LEVEL_ORDINAL,
                    null,
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.VULNERABILITY_OR_EXPOSURE,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "vulnerabilities",
                    "Vulnerabilities",
                    "Vulnerabilities exposed to the threat event (NIST Appendix F)",
                    SCALE_QUALITATIVE_ORDINAL,
                    UNITS_FIVE_LEVEL_ORDINAL,
                    null,
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "likelihood_initiation",
                    "Likelihood of Initiation",
                    "Likelihood that the threat source initiates the event (NIST Table G-2)",
                    SCALE_QUALITATIVE_ORDINAL,
                    UNITS_FIVE_LEVEL_ORDINAL,
                    null,
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "likelihood_adverse_impact",
                    "Likelihood of Adverse Impact",
                    "Likelihood that the threat event results in adverse impact (NIST Table G-3)",
                    SCALE_QUALITATIVE_ORDINAL,
                    UNITS_FIVE_LEVEL_ORDINAL,
                    null,
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "likelihood_overall",
                    "Overall Likelihood",
                    "Overall likelihood; derived per Table G-5 when absent",
                    SCALE_QUALITATIVE_ORDINAL,
                    UNITS_FIVE_LEVEL_ORDINAL,
                    "Derived per NIST SP 800-30 Rev. 1 Table G-5",
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.IMPACT_OR_LOSS_MAGNITUDE,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "impact_level",
                    "Impact Level",
                    "Impact level per NIST Table H-3",
                    SCALE_QUALITATIVE_ORDINAL,
                    UNITS_FIVE_LEVEL_ORDINAL,
                    null,
                    null));

    // GC-T012 seed crosswalk entries for ISO_27005_V2022
    private static final List<CrosswalkEntry> ISO_CROSSWALK_ENTRIES = List.of(
            new CrosswalkEntry(
                    NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "likelihood",
                    "Likelihood",
                    "Likelihood of threat exploitation of vulnerability",
                    SCALE_ORG_DEFINED_ORDINAL,
                    UNITS_ORG_DEFINED_ORDINAL_LEVELS,
                    null,
                    "Scale levels are organization-defined per ISO 27005:2022"),
            new CrosswalkEntry(
                    NormalizedConcept.CONSEQUENCE_OR_EFFECT,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "consequence",
                    "Consequence",
                    "Business consequence/impact of the risk event (ISO 27005 terminology)",
                    SCALE_ORG_DEFINED_ORDINAL,
                    UNITS_ORG_DEFINED_ORDINAL_LEVELS,
                    null,
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.ASSET,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "asset_value",
                    "Asset Value",
                    "Value classification of the information asset at risk",
                    SCALE_ORG_DEFINED_ORDINAL,
                    UNITS_ORG_DEFINED_ORDINAL_LEVELS,
                    null,
                    null),
            new CrosswalkEntry(
                    NormalizedConcept.CONTROL,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "existing_controls",
                    "Existing Controls",
                    "Description of existing ISO 27001 Annex A controls mitigating this risk",
                    "qualitative",
                    "free text",
                    null,
                    null));
    // @formatter:on

    public void ensureSeeded(UUID projectId) {
        var project = projectService.getById(projectId);
        seedIfMissing(
                project,
                "LEGACY_QUALITATIVE_V1",
                "Legacy Qualitative",
                "1",
                MethodologyFamily.CUSTOM,
                "Compatibility profile for migrated pre-methodology qualitative assessments.",
                parseSchema(LEGACY_INPUT_SCHEMA),
                parseSchema(LEGACY_OUTPUT_SCHEMA),
                null);
        seedIfMissing(
                project,
                "FAIR_V3_0",
                "FAIR",
                "3.0",
                MethodologyFamily.FAIR,
                "Factor Analysis of Information Risk (FAIR) v3.0 quantitative model with "
                        + "FAIR-CAM control analytics and FAIR-MAM loss magnitude extensions.",
                parseSchema(FAIR_INPUT_SCHEMA),
                parseSchema(FAIR_OUTPUT_SCHEMA),
                FAIR_CROSSWALK_ENTRIES);
        seedIfMissing(
                project,
                "NIST_SP800_30_R1",
                "NIST SP 800-30 Rev. 1",
                "1",
                MethodologyFamily.NIST_SP800_30_R1,
                "NIST SP 800-30 Rev. 1 qualitative risk assessment using five-level "
                        + "likelihood and impact scales with a 5x5 risk matrix.",
                parseSchema(NIST_INPUT_SCHEMA),
                parseSchema(NIST_OUTPUT_SCHEMA),
                NIST_CROSSWALK_ENTRIES);
        seedIfMissing(
                project,
                "ISO_27005_V2022",
                "ISO 27005",
                "2022",
                MethodologyFamily.ISO_27005,
                "ISO/IEC 27005:2022-aligned risk assessment supporting ISO 27001 "
                        + "information security management system risk criteria.",
                parseSchema(ISO_INPUT_SCHEMA),
                parseSchema(ISO_OUTPUT_SCHEMA),
                ISO_CROSSWALK_ENTRIES);
    }

    private void seedIfMissing(
            Project project,
            String key,
            String name,
            String version,
            MethodologyFamily family,
            String description,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            List<CrosswalkEntry> crosswalkEntries) {
        if (repository.existsByProjectIdAndProfileKeyAndVersion(project.getId(), key, version)) {
            return;
        }
        var profile = new MethodologyProfile(project, key, name, version, family);
        profile.setDescription(description);
        profile.setInputSchema(inputSchema);
        profile.setOutputSchema(outputSchema);
        profile.setStatus(MethodologyProfileStatus.ACTIVE);
        if (crosswalkEntries != null && !crosswalkEntries.isEmpty()) {
            profile.setCrosswalkEntries(crosswalkEntries);
        }
        repository.save(profile);
    }

    private static Map<String, Object> parseSchema(String json) {
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse methodology schema", e);
        }
    }

    private void applyUpdates(
            MethodologyProfile profile,
            String description,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            MethodologyProfileStatus status,
            Map<String, Object> treatmentStrategyVocabulary,
            List<CrosswalkEntry> crosswalkEntries) {
        if (description != null) {
            profile.setDescription(description);
        }
        if (inputSchema != null) {
            profile.setInputSchema(inputSchema);
        }
        if (outputSchema != null) {
            profile.setOutputSchema(outputSchema);
        }
        if (status != null) {
            profile.setStatus(status);
        }
        if (treatmentStrategyVocabulary != null) {
            profile.setTreatmentStrategyVocabulary(treatmentStrategyVocabulary);
        }
        if (crosswalkEntries != null) {
            validateCrosswalkEntries(profile, crosswalkEntries);
            profile.setCrosswalkEntries(
                    crosswalkEntries.isEmpty() ? new ArrayList<>() : new ArrayList<>(crosswalkEntries));
        }
    }

    /**
     * Validates crosswalk entries against the profile's current state.
     *
     * <p>Throws {@link DomainValidationException} on:
     * <ul>
     *   <li>duplicate {@code (normalizedConcept, vocabularySurface, sourceFieldPath)} tuples,
     *   <li>{@code INPUT_SCHEMA} surface when {@code inputSchema} is null,
     *   <li>{@code OUTPUT_SCHEMA} surface when {@code outputSchema} is null,
     *   <li>{@code TREATMENT_STRATEGY_VOCABULARY} surface when {@code treatmentStrategyVocabulary} is null,
     *   <li>{@code sourceFieldPath} not resolvable under the named surface's schema,
     *   <li>{@code conversionRule} non-null with both {@code scale} and {@code units} null.
     * </ul>
     */
    private static void validateCrosswalkEntries(MethodologyProfile profile, List<CrosswalkEntry> entries) {
        Set<String> seen = new HashSet<>();
        for (CrosswalkEntry entry : entries) {
            rejectDuplicateTuple(entry, seen);
            validateEntrySurface(profile, entry);
            rejectConversionRuleWithoutScaleOrUnits(entry);
        }
    }

    private static void rejectDuplicateTuple(CrosswalkEntry entry, Set<String> seen) {
        String key = entry.normalizedConcept() + "|" + entry.vocabularySurface() + "|" + entry.sourceFieldPath();
        if (seen.add(key)) {
            return;
        }
        throw new DomainValidationException(
                "Crosswalk contains duplicate entry: "
                        + entry.normalizedConcept() + " / "
                        + entry.vocabularySurface() + " / "
                        + entry.sourceFieldPath(),
                "duplicate_crosswalk_entry",
                Map.of(
                        "normalizedConcept",
                        entry.normalizedConcept().name(),
                        DETAIL_KEY_VOCABULARY_SURFACE,
                        entry.vocabularySurface().name(),
                        DETAIL_KEY_SOURCE_FIELD_PATH,
                        entry.sourceFieldPath()));
    }

    private static void validateEntrySurface(MethodologyProfile profile, CrosswalkEntry entry) {
        switch (entry.vocabularySurface()) {
            case INPUT_SCHEMA -> validateSchemaSurface(profile.getInputSchema(), entry, "INPUT_SCHEMA");
            case OUTPUT_SCHEMA -> validateSchemaSurface(profile.getOutputSchema(), entry, "OUTPUT_SCHEMA");
            case TREATMENT_STRATEGY_VOCABULARY -> validateTreatmentSurface(
                    profile.getTreatmentStrategyVocabulary(), entry);
            default -> throw new IllegalStateException(
                    "Unhandled CrosswalkVocabularySurface: " + entry.vocabularySurface());
        }
    }

    private static void validateSchemaSurface(Map<String, Object> schema, CrosswalkEntry entry, String surfaceName) {
        if (schema == null) {
            throw new DomainValidationException(
                    "Cannot add " + surfaceName + " crosswalk entry — profile has no "
                            + (surfaceName.equals("INPUT_SCHEMA") ? "inputSchema" : "outputSchema"),
                    "crosswalk_surface_not_present",
                    Map.of(DETAIL_KEY_VOCABULARY_SURFACE, surfaceName));
        }
        validateFieldPath(entry.sourceFieldPath(), schema, surfaceName);
    }

    private static void validateTreatmentSurface(Map<String, Object> vocabulary, CrosswalkEntry entry) {
        if (vocabulary == null) {
            throw new DomainValidationException(
                    "Cannot add TREATMENT_STRATEGY_VOCABULARY crosswalk entry — "
                            + "profile has no treatmentStrategyVocabulary",
                    "crosswalk_surface_not_present",
                    Map.of(DETAIL_KEY_VOCABULARY_SURFACE, TREATMENT_SURFACE_NAME));
        }
        validateTreatmentPath(entry.sourceFieldPath(), vocabulary);
    }

    private static void rejectConversionRuleWithoutScaleOrUnits(CrosswalkEntry entry) {
        if (entry.conversionRule() == null) {
            return;
        }
        if (entry.scale() != null || entry.units() != null) {
            return;
        }
        throw new DomainValidationException(
                "crosswalk entry for " + entry.sourceFieldPath()
                        + " has conversionRule but neither scale nor units is set — "
                        + "a conversion rule must name the scale or units it converts",
                "crosswalk_conversion_rule_missing_scale_or_units",
                Map.of(DETAIL_KEY_SOURCE_FIELD_PATH, entry.sourceFieldPath()));
    }

    /**
     * Best-effort dotted-path presence check against a JSON Schema map.
     *
     * <p>Walks {@code properties} / {@code items.properties} segments. Returns
     * without error for schemas that have {@code additionalProperties: true} at
     * any level (the schema is open-ended and any path is valid). Throws
     * {@link DomainValidationException} only when a {@code properties} map is
     * present and the path segment is not found in it.
     *
     * <p>Handles FAIR-CAM dotted paths like {@code fair_cam.control_strength} by
     * descending into nested object properties.
     */
    @SuppressWarnings("unchecked")
    private static void validateFieldPath(String path, Map<String, Object> schema, String surfaceName) {
        if (shouldSkipPropertyCheck(schema)) {
            return;
        }
        Map<String, Object> properties = (Map<String, Object>) schema.get(SCHEMA_KEY_PROPERTIES);
        String[] segments = path.split("\\.", 2);
        String head = segments[0];
        if (!properties.containsKey(head)) {
            throw new DomainValidationException(
                    "sourceFieldPath '" + path + "' does not resolve under " + surfaceName,
                    "crosswalk_unknown_field_path",
                    Map.of(DETAIL_KEY_SOURCE_FIELD_PATH, path, "surface", surfaceName));
        }
        if (segments.length > 1) {
            descendIntoNested(segments[1], properties.get(head), surfaceName);
        }
    }

    /**
     * True when the schema's contents cannot constrain a path (null schema, open
     * {@code additionalProperties}, or no {@code properties} node): the walker
     * treats any path as valid in that case.
     */
    private static boolean shouldSkipPropertyCheck(Map<String, Object> schema) {
        if (schema == null) {
            return true;
        }
        if (Boolean.TRUE.equals(schema.get(SCHEMA_KEY_ADDITIONAL_PROPERTIES))) {
            return true;
        }
        return !(schema.get(SCHEMA_KEY_PROPERTIES) instanceof Map);
    }

    @SuppressWarnings("unchecked")
    private static void descendIntoNested(String remaining, Object nested, String surfaceName) {
        if (!(nested instanceof Map)) {
            return;
        }
        Map<String, Object> nestedMap = (Map<String, Object>) nested;
        if (Boolean.TRUE.equals(nestedMap.get(SCHEMA_KEY_ADDITIONAL_PROPERTIES))) {
            return;
        }
        recurseIntoChildSchema(remaining, nestedMap, surfaceName);
        Object items = nestedMap.get(SCHEMA_KEY_ITEMS);
        if (items instanceof Map) {
            recurseIntoChildSchema(remaining, (Map<String, Object>) items, surfaceName);
        }
    }

    /**
     * If {@code parent[properties]} is itself a JSON-schema-style map, wrap it
     * as the {@code properties} of a synthetic schema and recurse the walker
     * into it. Otherwise no-op.
     */
    @SuppressWarnings("unchecked")
    private static void recurseIntoChildSchema(String remaining, Map<String, Object> parent, String surfaceName) {
        Object child = parent.get(SCHEMA_KEY_PROPERTIES);
        if (!(child instanceof Map)) {
            return;
        }
        Map<String, Object> synthetic = new LinkedHashMap<>();
        synthetic.put(SCHEMA_KEY_PROPERTIES, child);
        validateFieldPath(remaining, synthetic, surfaceName);
    }

    /**
     * Validates a treatment crosswalk {@code sourceFieldPath} against a profile's
     * {@code treatmentStrategyVocabulary}.
     *
     * <p>The vocabulary is a flat map keyed by stable strategy key (per V125). The
     * top-level segment of the dotted path must be a key in the map; if the value
     * is itself a {@code Map} and the path has remaining segments, the walk
     * descends into it. Unknown keys throw {@link DomainValidationException} with
     * the same {@code crosswalk_unknown_field_path} code as the schema-backed
     * surfaces, matching the contract documented in {@code docs/API.md}.
     */
    @SuppressWarnings("unchecked")
    private static void validateTreatmentPath(String path, Map<String, Object> vocabulary) {
        String[] segments = path.split("\\.", 2);
        String head = segments[0];
        if (!vocabulary.containsKey(head)) {
            throw new DomainValidationException(
                    "sourceFieldPath '" + path + "' does not resolve under TREATMENT_STRATEGY_VOCABULARY",
                    "crosswalk_unknown_field_path",
                    Map.of(DETAIL_KEY_SOURCE_FIELD_PATH, path, "surface", TREATMENT_SURFACE_NAME));
        }
        if (segments.length > 1) {
            Object nested = vocabulary.get(head);
            if (nested instanceof Map) {
                validateTreatmentPath(segments[1], (Map<String, Object>) nested);
            }
        }
    }
}
