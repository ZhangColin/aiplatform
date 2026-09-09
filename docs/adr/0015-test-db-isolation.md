# 测试库隔离：测试连独立 aiplatform_test 库，与 dev 分库

project/order/identity/workspace/knowledge/metering 六域的 `@SpringBootTest` 直接继承 `application-local.yml` 连真 dev 库（`5432/aiplatform`），teardown 用全表 DELETE，`mvn test` 会清空 dev 的 `prj_projects` 等用户真数据（issue #132）。决定：测试改用同实例独立库 `aiplatform_test`，经 `application-test.yml`（URL 可覆盖）+ `@IntegrationTest` 组合注解切过去；全表 DELETE 在测试库上就地变正确、测试代码零改动。

## 定位

- 隔离落在「库」层：同一 homebrew PG 5432 实例、不同 database。不是 testcontainers、不是 H2、不是 schema 分租。
- 全表 DELETE teardown 是「清测试库」的正确写法，不是 bug——本决议让现有 teardown 模式就地正确，而不是去改 35+ 个测试的删除逻辑。
- URL 缝是唯一扩展点：未来 CI / 协作 / Testcontainers 都从这一道口子接入，不动测试代码。

## Considered Options

- **止血（delete-by-id）**：把全表 DELETE 改成只删本测试造的行。改 9 处 + 其它域，~20 分钟，但脚枪仍上膛——靠每个未来测试作者自觉。代码库已有 5 处 delete-by-id、9 处全表 DELETE 并存，正是「自觉」失败的证据。被否。
- **H2 内嵌库**：零置备、处处可跑，但 schema 深度 PG 专用（V1 第一步 `CREATE EXTENSION vector`、`embedding vector(512)`、HNSW 索引、`<=>` 算子、JSONB），H2 连迁移都执行不到底；换 SQL 方言即「假绿」。被否。
- **Testcontainers**：真 PG、每 run 全新库、零置备，但它是第二套机制（不接 URL 缝，靠 `@Testcontainers` 动态属性），与本机「独立库 + URL」机制并存；CI 用 PG service 已够。暂不选，留备案。
- **独立 PG 库 + 可覆盖 URL（本方案）**：与「止血」同样便宜（`createdb` + 一个 yml + 一个注解），却把整类 bug 归零、且不再复发。选之。

## 决议

- 测试库 `aiplatform_test`，与 dev 同 PG 实例（5432）；Flyway `baseline-on-migrate` 自动建全 schema（V1~V4），pgvector 扩展由 V1 `CREATE EXTENSION IF NOT EXISTS vector` 自动装（实例级已装）。
- `src/test/resources/application-test.yml`：`spring.datasource.url: ${TEST_DB_URL:jdbc:postgresql://localhost:5432/aiplatform_test}`，凭据同本机（postgres/truth）。
- `@IntegrationTest` 组合注解 = `@SpringBootTest` + `@ActiveProfiles({"local","test"})`——保留 local 的一切覆盖，test profile 只翻数据源 URL，与今日行为的最小 delta。
- 测试库手工 `createdb` 一次、写进 `docs/guide/测试环境部署.md`；缺库是响亮失败（`database does not exist`）、不静默。不自动建库：PG 无 `CREATE DATABASE IF NOT EXISTS`，为边际体验加机器不值。
- 全表 DELETE teardown 不动（隔离后就地正确）；已 delete-by-id 的 5 处保留（无害，不返工）。

## 未来演进（备案，带触发器）

- **CI：置备 PG service**。起 `pgvector/pgvector:pg16` 容器（GitHub Actions `services:`），设 `TEST_DB_URL` 指过去——同一道缝、同一套机制，`application-test.yml` 零改动。触发器：引入 CI 跑 `mvn test` 时，落地即此方案。
- **Testcontainers**。未来若要「零置备」（协作开发者免 `createdb`）或「每次 run 全新隔离」（并行 CI matrix）再切——同样接 `application-test.yml` 口子、不推翻。触发器：团队扩到需零置备、或需并行隔离时。

## Consequences

- `mvn test` 不再碰 dev 数据，用户真项目不会因跑测试蒸发。
- 全表 DELETE 就地正确，测试代码零改动（除挂 `@IntegrationTest` 注解）。
- 未来测试作者写全表 DELETE 也安全（作用在测试库上）。
- 一次性置备步骤 `createdb aiplatform_test`（已文档化）；缺库失败响亮、易诊断。
