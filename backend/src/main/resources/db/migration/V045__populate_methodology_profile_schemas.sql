-- Populate methodology profile schemas with proper semantics, factors, scales,
-- units, and output rules for each supported risk assessment methodology.

-- ---------------------------------------------------------------------------
-- FAIR Model v3.0 (includes FAIR-CAM and FAIR-MAM extensions)
-- ---------------------------------------------------------------------------
UPDATE methodology_profile
SET
    description = 'Factor Analysis of Information Risk (FAIR) v3.0 quantitative model. '
               || 'Decomposes risk into Loss Event Frequency (LEF) and Loss Magnitude (LM) '
               || 'using probabilistic three-point estimates. Includes FAIR-CAM control '
               || 'analytics inputs and FAIR-MAM loss magnitude extensions when configured.',
    input_schema = '{
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
    }
  },
  "required": ["threat_event_frequency", "vulnerability", "primary_loss_magnitude"],
  "semantics": {
    "scale": "continuous",
    "units": "monetary",
    "currency": "configurable (default USD)",
    "estimation_method": "three-point (low/likely/high) with optional Monte Carlo simulation"
  }
}',
    output_schema = '{
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
}'
WHERE profile_key = 'FAIR_V3_0';

-- ---------------------------------------------------------------------------
-- NIST SP 800-30 Rev. 1 Likelihood/Impact Assessment
-- ---------------------------------------------------------------------------
UPDATE methodology_profile
SET
    description = 'NIST Special Publication 800-30 Rev. 1 qualitative risk assessment using '
               || 'five-level likelihood and impact scales with a 5x5 risk matrix. Supports '
               || 'threat source characterization (capability, intent, targeting) and '
               || 'predisposing conditions as supplementary inputs.',
    input_schema = '{
  "type": "object",
  "description": "NIST SP 800-30 Rev. 1 assessment inputs",
  "properties": {
    "likelihood": {
      "type": "object",
      "description": "Overall likelihood of threat event occurrence and adverse impact",
      "properties": {
        "level": {"type": "string", "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]},
        "score": {"type": "integer", "minimum": 1, "maximum": 5}
      },
      "required": ["level", "score"]
    },
    "impact": {
      "type": "object",
      "description": "Level of adverse impact if the threat event occurs",
      "properties": {
        "level": {"type": "string", "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]},
        "score": {"type": "integer", "minimum": 1, "maximum": 5}
      },
      "required": ["level", "score"]
    },
    "predisposing_conditions": {
      "type": "string",
      "description": "Conditions that increase or decrease likelihood (e.g., unpatched systems, network exposure)"
    },
    "threat_source_characteristics": {
      "type": "object",
      "description": "NIST threat source characterization",
      "properties": {
        "capability": {"type": "string", "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]},
        "intent": {"type": "string", "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]},
        "targeting": {"type": "string", "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]}
      }
    }
  },
  "required": ["likelihood", "impact"],
  "semantics": {
    "scale": "ordinal",
    "levels": 5,
    "level_labels": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"],
    "score_range": {"min": 1, "max": 5},
    "units": "qualitative ordinal levels"
  }
}',
    output_schema = '{
  "type": "object",
  "description": "NIST SP 800-30 Rev. 1 risk determination outputs",
  "properties": {
    "risk_level": {
      "type": "string",
      "description": "Overall risk level derived from the 5x5 matrix",
      "enum": ["VERY_LOW", "LOW", "MODERATE", "HIGH", "VERY_HIGH"]
    },
    "risk_score": {
      "type": "integer",
      "description": "Numeric risk score (likelihood_score * impact_score)",
      "minimum": 1,
      "maximum": 25
    },
    "risk_matrix_cell": {
      "type": "string",
      "description": "Matrix cell reference (e.g., L3-I4 for likelihood=3, impact=4)"
    }
  },
  "required": ["risk_level", "risk_score"],
  "semantics": {
    "scale": "ordinal",
    "derivation": "risk_score = likelihood.score * impact.score",
    "matrix_mapping": {
      "1-4": "VERY_LOW",
      "5-8": "LOW",
      "9-12": "MODERATE",
      "13-19": "HIGH",
      "20-25": "VERY_HIGH"
    }
  }
}'
WHERE profile_key = 'NIST_SP800_30_R1';

-- ---------------------------------------------------------------------------
-- ISO 27005 (ISO 27001-compatible risk criteria)
-- ---------------------------------------------------------------------------
UPDATE methodology_profile
SET
    description = 'ISO/IEC 27005:2022-aligned risk assessment supporting ISO 27001 '
               || 'information security management system risk criteria. Uses qualitative '
               || 'likelihood and consequence scales with organization-defined risk '
               || 'acceptance thresholds. Compatible with ISO 27001:2022 Annex A controls.',
    input_schema = '{
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
}',
    output_schema = '{
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
}'
WHERE profile_key = 'ISO_27005_V2022';

-- ---------------------------------------------------------------------------
-- Legacy Qualitative (backwards-compatibility profile)
-- ---------------------------------------------------------------------------
UPDATE methodology_profile
SET
    description = 'Compatibility profile for migrated pre-methodology qualitative assessments. '
               || 'Accepts free-form input factors and computed outputs without enforcing '
               || 'a specific schema.',
    input_schema = '{
  "type": "object",
  "description": "Free-form input factors for legacy qualitative assessments",
  "additionalProperties": true,
  "semantics": {
    "scale": "unstructured",
    "units": "free-form",
    "note": "This profile accepts any input structure for backwards compatibility"
  }
}',
    output_schema = '{
  "type": "object",
  "description": "Free-form computed outputs for legacy qualitative assessments",
  "additionalProperties": true,
  "semantics": {
    "scale": "unstructured",
    "units": "free-form",
    "note": "This profile accepts any output structure for backwards compatibility"
  }
}'
WHERE profile_key = 'LEGACY_QUALITATIVE_V1';
