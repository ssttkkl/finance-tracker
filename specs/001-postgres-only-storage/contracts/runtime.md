# Runtime Contract

## Configuration

Required:

- `FT_DATABASE_URL`
- `FT_WORKSPACE_ID`

`FT_DATABASE_URL` must use a PostgreSQL SQLAlchemy dialect. SQLite is allowed only for isolated repository unit tests,
not as runtime configuration.

Runtime storage configuration is environment-only in this feature; no local storage YAML is read.

Rejected legacy inputs:

- `FT_STORAGE_BACKEND`
- `FT_DIR`
- any runtime storage YAML containing `storage.backend` or `storage.ledger_root`

Missing required values or any rejected key raises `StorageConfigurationError` before a command executes.

## Startup validation

`build_services(settings)` must:

1. connect to PostgreSQL;
2. verify required baseline/schema version;
3. verify the configured workspace exists;
4. return a workspace-bound service bundle.

It must not call `create_all`, `ensure_workspace`, inspect `HOME`, create `~/.ft`, or fallback to files.

## Service bundle

The bundle exposes only PostgreSQL-backed supported use cases. It has no backend selector, local builder,
migration target, Git change set or file snapshot repair service.

## Atomicity

Every write use case owns exactly one UoW transaction. A failed write leaves account facts, source records,
revisions and projections unchanged.
