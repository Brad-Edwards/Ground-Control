-- GC-T016 / #745: extend the FAIR v3.0 methodology profile so the materiality /
-- loss-taxonomy vocabulary is self-describing, grounded in The Open Group FAIR
-- standard (Risk Taxonomy, O-RT v3.0.1).
--   * input_schema: add the optional `forms_of_loss` object keyed by the six O-RT
--     forms of loss, and the optional `secondary_loss_by_stakeholder` array whose
--     `loss_form` uses the same six forms. `fair_mam` is retained as a legacy
--     opaque passthrough for backward compatibility (GC-T011).
--   * output_schema: add the `materiality` object the read-only analysis envelope
--     now emits (forms-of-loss breakdown, total, stakeholder secondary effects).
-- The new input properties are NOT added to `required`, so rows created before this
-- migration validate fine. Materiality is descriptive only; the canonical ALE
-- derivation (ALE = LEF * LM; LM = PLM + SLEF*SLM) is unchanged.
-- FAIR-MAM (FAIR Institute) is a non-standard, separately-published extension and
-- is intentionally out of scope here.

UPDATE methodology_profile
SET input_schema = '{
  "type": "object",
  "description": "FAIR v3.0 input factors with FAIR-CAM and O-RT forms-of-loss materiality extensions",
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
      "description": "Legacy opaque loss-magnitude passthrough retained for backward compatibility (GC-T011). New materiality input is `forms_of_loss`."
    },
    "forms_of_loss": {
      "type": "object",
      "description": "GC-T016 materiality input: the six forms of loss per The Open Group Risk Taxonomy (O-RT v3.0.1). Each form is an optional three-point monetary estimate. Descriptive only — surfaced in the materiality view, not summed into the canonical ALE.",
      "properties": {
        "productivity": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "response": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "replacement": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "fines_and_judgments": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "competitive_advantage": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
        "reputation": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}}
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
      "description": "GC-T016 FAIR materiality view: a descriptive decomposition of forms_of_loss into the six O-RT forms of loss plus stakeholder-specific secondary effects. Does not alter the ALE derivation.",
      "properties": {
        "formsOfLoss": {
          "type": "array",
          "description": "Per O-RT form of loss with its three-point monetary magnitude",
          "items": {
            "type": "object",
            "properties": {
              "form": {"type": "string", "enum": ["PRODUCTIVITY", "RESPONSE", "REPLACEMENT", "FINES_AND_JUDGMENTS", "COMPETITIVE_ADVANTAGE", "REPUTATION"]},
              "magnitude": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}}}
            }
          }
        },
        "formsOfLossTotal": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}}},
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
