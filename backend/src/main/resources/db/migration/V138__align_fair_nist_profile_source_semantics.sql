-- Primary-source alignment for existing seeded methodology profiles.
--
-- FAIR / O-RT / O-RA: align the seeded compatibility profile with the primary
-- Open FAIR standards. The compatibility key remains FAIR_V3_0, but the source
-- profile is O-RT 3.0.1 plus O-RA 2.0.1. Probability of Action is a probability
-- bounded to [0,1]; Threat Capability and Resistance Strength are percentile
-- estimates bounded to [0,100], not probabilities. Remove unsupported FAIR-CAM
-- and FAIR-MAM extension fields from the core Open FAIR profile.
-- NIST SP 800-30 Rev. 1: Confirmed/Expected/Anticipated/Predicted/Possible/N/A
-- are Table E-4 threat-event relevance values, not Table D-2 threat-source
-- taxonomy values. Add the correctly named property and mark the historical
-- threat_source_relevance property as a compatibility alias.

UPDATE methodology_profile
SET name = 'Open FAIR',
    version = 'O-RT 3.0.1 / O-RA 2.0.1',
    description = 'Open FAIR quantitative profile aligned to O-RT 3.0.1 and O-RA 2.0.1. Profile key FAIR_V3_0 is retained for compatibility.',
    input_schema = '{
  "type": "object",
  "description": "Open FAIR input factors aligned to O-RT 3.0.1 and O-RA 2.0.1",
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
      "description": "Loss Event Frequency; may be supplied directly or derived from TEF × Vulnerability.",
      "properties": {
        "low": {"type": "number", "minimum": 0},
        "likely": {"type": "number", "minimum": 0},
        "high": {"type": "number", "minimum": 0},
        "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
      }
    },
    "primary_loss_magnitude": {
      "type": "object",
      "description": "Direct economic loss from a single loss event",
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
      "description": "Additional economic loss from Secondary Stakeholder reaction",
      "properties": {
        "low": {"type": "number", "minimum": 0},
        "likely": {"type": "number", "minimum": 0},
        "high": {"type": "number", "minimum": 0},
        "currency": {"type": "string", "default": "USD"},
        "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
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
        "low": {"type": "number", "minimum": 0, "maximum": 1},
        "likely": {"type": "number", "minimum": 0, "maximum": 1},
        "high": {"type": "number", "minimum": 0, "maximum": 1},
        "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
      }
    },
    "threat_capability": {
      "type": "object",
      "description": "Threat Capability percentile estimate used with Resistance Strength to evaluate Vulnerability",
      "properties": {
        "low": {"type": "number", "minimum": 0, "maximum": 100},
        "likely": {"type": "number", "minimum": 0, "maximum": 100},
        "high": {"type": "number", "minimum": 0, "maximum": 100},
        "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
      }
    },
    "resistance_strength": {
      "type": "object",
      "description": "Resistance Strength percentile estimate used with Threat Capability to evaluate Vulnerability",
      "properties": {
        "low": {"type": "number", "minimum": 0, "maximum": 100},
        "likely": {"type": "number", "minimum": 0, "maximum": 100},
        "high": {"type": "number", "minimum": 0, "maximum": 100},
        "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
      }
    }
  },
  "required": ["threat_event_frequency", "vulnerability", "primary_loss_magnitude"],
  "semantics": {
    "scale": "continuous",
    "units": "monetary",
    "currency": "configurable (default USD)",
    "estimation_method": "three-point (low/likely/high) estimates with optional persisted Monte Carlo outputs"
  }
}',
    output_schema = '{
  "type": "object",
  "description": "Open FAIR computed risk outputs",
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
    "derivation": "ALE = LEF * LM; LEF = TEF * Vuln; TEF = CF * PoA when derived; expected LM = PLM + (SLEF * SLM)"
  }
}',
    crosswalk_entries = CASE
        WHEN crosswalk_entries IS NULL THEN NULL
        ELSE (
            SELECT COALESCE(jsonb_agg(entry), '[]'::jsonb)::text
            FROM jsonb_array_elements(crosswalk_entries::jsonb) AS entry
            WHERE entry ->> 'sourceFieldPath' NOT IN ('fair_cam.control_strength', 'fair_cam.control_coverage')
        )
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE profile_key = 'FAIR_V3_0';

UPDATE methodology_profile
SET input_schema = jsonb_set(
        jsonb_set(
            jsonb_set(
                input_schema::jsonb,
                '{properties,threat_event_relevance}',
                jsonb_build_object(
                    'type', 'string',
                    'description', 'Threat event relevance per NIST SP 800-30 Rev. 1 Table E-4',
                    'enum', jsonb_build_array(
                        'CONFIRMED',
                        'EXPECTED',
                        'ANTICIPATED',
                        'PREDICTED',
                        'POSSIBLE',
                        'NOT_APPLICABLE')),
                true),
            '{properties,threat_source_relevance,description}',
            to_jsonb('Legacy field name retained for compatibility; use threat_event_relevance for NIST SP 800-30 Rev. 1 Table E-4 threat-event relevance'::text),
            false),
        '{properties,threat_source_relevance,deprecated}',
        'true'::jsonb,
        true)::text,
    updated_at = CURRENT_TIMESTAMP
WHERE profile_key = 'NIST_SP800_30_R1'
  AND input_schema IS NOT NULL
  AND input_schema::jsonb #> '{properties,threat_source_relevance}' IS NOT NULL;
