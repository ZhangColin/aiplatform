-- 集成测试库（@IntegrationTest 隔离到 aiplatform_test，见 docs/adr/0015-test-db-isolation.md）。
-- 主库 aiplatform 由 POSTGRES_DB 建；vector extension 不在此建——
-- Flyway V1__baseline.sql 的 CREATE EXTENSION IF NOT EXISTS vector 会在各自库应用时自建。
CREATE DATABASE aiplatform_test;
