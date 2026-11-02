-- À appliquer avant le déploiement de la version introduisant @Version.
-- MySQL 8.0.29+ accepte IF NOT EXISTS sur ADD COLUMN.
ALTER TABLE evaluation ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE campagne ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE fiche_objectifs ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
