-- Null catalog/version is legacy. Existing characters and active jobs retain their original assets/pipeline.
ALTER TABLE character_creation_jobs ADD COLUMN expression_pipeline_version INTEGER;
ALTER TABLE character_creation_jobs ADD COLUMN expression_catalog_json TEXT;
ALTER TABLE characters ADD COLUMN expression_catalog_json TEXT;
