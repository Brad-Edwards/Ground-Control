-- GC-T016 / #745: extend the FAIR v3.0 methodology profile so the materiality /
-- loss-taxonomy vocabulary is self-describing.
--   * input_schema: redefine `fair_mam` as the FAIR Materiality Assessment Model's
--     ten primary cost modules (FAIR Institute), and add the optional
--     `secondary_loss_by_stakeholder` array whose `loss_form` uses the six FAIR
--     forms of loss from The Open Group Risk Taxonomy (O-RT).
--   * output_schema: add the `materiality` object the read-only analysis envelope
--     now emits (FAIR-MAM module breakdown, total, stakeholder secondary effects).
-- The new input properties are NOT added to `required`, so rows created before this
-- migration validate fine. Materiality is descriptive only; the canonical ALE
-- derivation (ALE = LEF * LM; LM = PLM + SLEF*SLM) is unchanged.

UPDATE methodology_profile
SET input_schema = '{
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
    "secondary_loss_by_stakeholder": {
      "type": "array",
      "description": "Optional GC-T016 stakeholder-specific secondary loss effects. Each entry names the affected stakeholder, an optional O-RT FAIR form of loss, and a three-point monetary magnitude. Descriptive only — surfaced in the materiality view, not summed into the canonical ALE.",
      "items": {
        "type": "object",
        "properties": {
          "stakeholder": {"type": "string"},
          "loss_form": {"type": "string", "enum": ["productivity", "response", "replacement", "fines_and_judgments", "competitive_advantage", "reputation"]},
          "low": {"type": "number", "minimum": 0},
          "likely": {"type": "number", "minimum": 0},
          "high": {"type": "number", "minimum": 0},
          "currency": {"type": "string", "default": "USD"}
        },
        "required": ["stakeholder", "low", "likely", "high"]
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
      "description": "FAIR Materiality Assessment Model (FAIR-MAM, FAIR Institute) loss-magnitude breakdown by its ten primary cost modules. Each module is an optional three-point monetary estimate. Deeper FAIR-MAM subcategory layers are not modelled here.",
      "properties": {
        "information_privacy": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "proprietary_data_loss": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "business_interruption": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "cyber_extortion": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "network_security": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "financial_fraud": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "media_content": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "hardware_bricking": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "post_breach_security_improvements": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "reputational_damage": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}}
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
    "materiality": {
      "type": "object",
      "description": "GC-T016 FAIR materiality view: a descriptive decomposition of fair_mam into FAIR-MAM cost modules plus stakeholder-specific secondary effects. Does not alter the ALE derivation.",
      "properties": {
        "fairMamModules": {
          "type": "array",
          "description": "Per FAIR-MAM cost module with its three-point monetary magnitude",
          "items": {
            "type": "object",
            "properties": {
              "module": {"type": "string", "enum": ["INFORMATION_PRIVACY", "PROPRIETARY_DATA_LOSS", "BUSINESS_INTERRUPTION", "CYBER_EXTORTION", "NETWORK_SECURITY", "FINANCIAL_FRAUD", "MEDIA_CONTENT", "HARDWARE_BRICKING", "POST_BREACH_SECURITY_IMPROVEMENTS", "REPUTATIONAL_DAMAGE"]},
              "magnitude": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}}}
            }
          }
        },
        "fairMamTotal": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}}},
        "currency": {"type": "string"},
        "secondaryLossByStakeholder": {
          "type": "array",
          "description": "Stakeholder-specific secondary loss effects classified by O-RT FAIR form of loss",
          "items": {
            "type": "object",
            "properties": {
              "stakeholder": {"type": "string"},
              "lossForm": {"type": "string", "enum": ["PRODUCTIVITY", "RESPONSE", "REPLACEMENT", "FINES_AND_JUDGMENTS", "COMPETITIVE_ADVANTAGE", "REPUTATION"]},
              "magnitude": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}}}
            }
          }
        }
      }
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
