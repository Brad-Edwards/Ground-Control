-- GC-T016 / #745: add the forms-of-loss materiality vocabulary to the Open FAIR
-- (FAIR_V3_0) methodology profile, on top of the O-RT 3.0.1 / O-RA 2.0.1 alignment
-- in V138.
--
-- Surgical jsonb_set additions (not a full schema replacement) so the V138
-- source alignment is preserved verbatim:
--   * input_schema.properties.forms_of_loss        — the six O-RT forms of loss
--     (Risk Taxonomy, O-RT v3.0.1), each an optional three-point monetary estimate.
--   * input_schema.properties.secondary_loss_by_stakeholder — optional per-stakeholder
--     secondary effects whose loss_form is one of the six O-RT forms.
--   * output_schema.properties.materiality         — the forms-of-loss breakdown,
--     total, and stakeholder secondary effects the read-only analysis envelope emits.
--
-- New input properties are NOT added to `required`, so rows created before this
-- migration validate fine. Materiality is descriptive only; the canonical ALE
-- derivation (ALE = LEF * LM; LM = PLM + SLEF*SLM) is unchanged. FAIR-CAM and
-- FAIR-MAM (FAIR Institute models, not part of the Open Group FAIR standard) were
-- removed in V138 and are intentionally not reintroduced.

UPDATE methodology_profile
SET input_schema = jsonb_set(
        jsonb_set(
            input_schema::jsonb,
            '{properties,forms_of_loss}',
            '{
              "type": "object",
              "description": "GC-T016 materiality input: the six forms of loss per The Open Group Risk Taxonomy (O-RT v3.0.1). Each form is an optional three-point monetary estimate. Descriptive only; surfaced in the materiality view, not summed into the canonical ALE.",
              "properties": {
                "productivity": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
                "response": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
                "replacement": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
                "fines_and_judgments": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
                "competitive_advantage": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}},
                "reputation": {"type": "object", "properties": {"low": {"type": "number"}, "likely": {"type": "number"}, "high": {"type": "number"}, "currency": {"type": "string", "default": "USD"}}}
              }
            }'::jsonb,
            true),
        '{properties,secondary_loss_by_stakeholder}',
        '{
          "type": "array",
          "description": "Optional GC-T016 stakeholder-specific secondary loss effects. Each entry names the affected stakeholder, an optional O-RT FAIR form of loss, and a three-point monetary magnitude. Descriptive only; not summed into the canonical ALE.",
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
        }'::jsonb,
        true)::text,
    output_schema = jsonb_set(
        output_schema::jsonb,
        '{properties,materiality}',
        '{
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
        }'::jsonb,
        true)::text,
    updated_at = CURRENT_TIMESTAMP
WHERE profile_key = 'FAIR_V3_0'
  AND input_schema IS NOT NULL
  AND output_schema IS NOT NULL;
