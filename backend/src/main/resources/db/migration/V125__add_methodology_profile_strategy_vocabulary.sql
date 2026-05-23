-- GC-T004 / C5 (#861): profile-owned treatment strategy vocabulary, keyed by stable
-- strategy key. JSON via JacksonTextCollectionConverters.StringObjectMapConverter,
-- same column pattern as input_schema / output_schema.
ALTER TABLE methodology_profile
    ADD COLUMN treatment_strategy_vocabulary TEXT;

ALTER TABLE methodology_profile_audit
    ADD COLUMN treatment_strategy_vocabulary TEXT;
