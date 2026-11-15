-- lytgos 持久层建表脚本。
--
-- 由 Spring 的 DataSourceInitializer 在容器启动时幂等执行，不交给 ORM 自动建表。
-- 全部 IF NOT EXISTS：重复启动不破坏已有审计记录（这是 05 号闸门审计不变量 I1/I2/I3 的前提）。
--
-- trace_id 上的索引刻意不在这里建：本脚本先于 SqliteSchemaMigrator 执行，而老库的表还没有
-- trace_id 列，在此执行 CREATE INDEX 会让老库启动直接失败（no such column: trace_id）。
-- 索引由迁移器在补列之后统一用 CREATE INDEX IF NOT EXISTS 建立。

-- WAL 模式持久化在库文件里，启动时执行一次即可；每次连接的 busy_timeout 由连接池配置。
PRAGMA journal_mode = WAL;

CREATE TABLE IF NOT EXISTS sessions (
    key        TEXT PRIMARY KEY,
    messages   TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS llm_calls (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id        TEXT NOT NULL,
    turn_id           TEXT NOT NULL,
    trace_id          TEXT,
    provider          TEXT NOT NULL,
    model             TEXT NOT NULL,
    prompt_tokens     INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    total_tokens      INTEGER NOT NULL,
    duration_ms       INTEGER NOT NULL,
    success           INTEGER NOT NULL,
    error_message     TEXT,
    created_at        TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_llm_calls_session ON llm_calls (session_id);
CREATE INDEX IF NOT EXISTS idx_llm_calls_turn ON llm_calls (turn_id);
CREATE INDEX IF NOT EXISTS idx_llm_calls_created ON llm_calls (created_at);

CREATE TABLE IF NOT EXISTS tool_invocations (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    TEXT NOT NULL,
    turn_id       TEXT NOT NULL,
    trace_id      TEXT,
    tool_name     TEXT NOT NULL,
    arguments     TEXT,
    duration_ms   INTEGER NOT NULL,
    success       INTEGER NOT NULL,
    error_message TEXT,
    created_at    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tool_invocations_session ON tool_invocations (session_id);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_turn ON tool_invocations (turn_id);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_created ON tool_invocations (created_at);
