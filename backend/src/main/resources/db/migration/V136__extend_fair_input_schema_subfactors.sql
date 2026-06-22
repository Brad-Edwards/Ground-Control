-- GC-T011 / #723: extend the Open FAIR methodology profile input schema with four
-- optional sub-factor properties (contact_frequency, probability_of_action,
-- threat_capability, resistance_strength) that underpin TEF and Vulnerability
-- lineage in FairQuantitativeAnalysisService. Existing rows created before this
-- migration validate fine against the extended schema because the new properties
-- are NOT added to the required array.

UPDATE methodology_profile
SET input_schema = '{
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
        "low": {"type": "number", "minimum": 0},
        "likely": {"type": "number", "minimum": 0},
        "high": {"type": "number", "minimum": 0},
        "confidence": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]}
      },
      "required": ["low", "likely", "high"]
    },
    "loss_event_frequency": {
      "type": "object",
      "description": "Loss Event Frequency; may be supplied directly or derived from TEF × Vulnerability.",
      "properties": {
        "low": {"type": "number", "minimum": 0, "maximum": 1},
        "likely": {"type": "number", "minimum": 0, "maximum": 1},
        "high": {"type": "number", "minimum": 0, "maximum": 1},
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
        "low": {"type": "number", "minimum": 0, "maximum": 1},
        "likely": {"type": "number", "minimum": 0, "maximum": 1},
        "high": {"type": "number", "minimum": 0, "maximum": 1},
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
}'
WHERE profile_key = 'FAIR_V3_0';
