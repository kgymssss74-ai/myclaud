PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- ============================================================
-- 1. members
-- ============================================================
CREATE TABLE IF NOT EXISTS members (
    member_id     TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    display_name  TEXT,
    tg            TEXT NOT NULL,
    tg_order      INTEGER NOT NULL DEFAULT 1,
    cl_level      TEXT,
    mentor        TEXT,
    is_part_leader INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (mentor) REFERENCES members(member_id) DEFERRABLE INITIALLY DEFERRED
);

-- ============================================================
-- 2. projects
-- ============================================================
CREATE TABLE IF NOT EXISTS projects (
    project_id       TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    track            TEXT NOT NULL CHECK(track IN ('device', 'advance', 'review')),
    platform         TEXT,
    background       TEXT,
    owner_member_id  TEXT REFERENCES members(member_id),
    created_week     TEXT,
    tg               TEXT
);

-- ============================================================
-- 3. milestones
-- ============================================================
CREATE TABLE IF NOT EXISTS milestones (
    ms_id        TEXT PRIMARY KEY,
    project_id   TEXT NOT NULL REFERENCES projects(project_id),
    type         TEXT NOT NULL,
    planned_date TEXT,
    actual_date  TEXT,
    status       TEXT NOT NULL DEFAULT 'pending'
                 CHECK(status IN ('done', 'pending', 'risk'))
);

CREATE INDEX IF NOT EXISTS idx_milestones_project ON milestones(project_id);

-- ============================================================
-- 4. milestone_history
-- ============================================================
CREATE TABLE IF NOT EXISTS milestone_history (
    hist_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    ms_id        TEXT NOT NULL REFERENCES milestones(ms_id),
    old_date     TEXT,
    new_date     TEXT,
    reason       TEXT,
    changed_week TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ms_history_ms ON milestone_history(ms_id);

-- ============================================================
-- 5. mbo_objectives  (before report_items — report_items has FK here)
-- ============================================================
CREATE TABLE IF NOT EXISTS mbo_objectives (
    mbo_id            TEXT PRIMARY KEY,
    member_id         TEXT NOT NULL REFERENCES members(member_id),
    year              INTEGER NOT NULL,
    title             TEXT NOT NULL,
    weight            INTEGER,
    target_metric     TEXT,
    target_value      TEXT,
    linked_project_id TEXT REFERENCES projects(project_id)
);

CREATE INDEX IF NOT EXISTS idx_mbo_member ON mbo_objectives(member_id, year);

-- ============================================================
-- 6. weekly_reports
-- ============================================================
CREATE TABLE IF NOT EXISTS weekly_reports (
    report_id  INTEGER PRIMARY KEY AUTOINCREMENT,
    week       TEXT NOT NULL,
    member_id  TEXT NOT NULL REFERENCES members(member_id),
    project_id TEXT NOT NULL REFERENCES projects(project_id),
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE(week, member_id, project_id)
);

CREATE INDEX IF NOT EXISTS idx_reports_week    ON weekly_reports(week);
CREATE INDEX IF NOT EXISTS idx_reports_member  ON weekly_reports(member_id);
CREATE INDEX IF NOT EXISTS idx_reports_project ON weekly_reports(project_id);

-- ============================================================
-- 7. report_items
-- ============================================================
CREATE TABLE IF NOT EXISTS report_items (
    item_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    report_id     INTEGER NOT NULL REFERENCES weekly_reports(report_id) ON DELETE CASCADE,
    item_key      TEXT NOT NULL,
    progress_text TEXT,
    schedule_text TEXT,
    risk_text     TEXT,
    delay_reason  TEXT,
    ms_id         TEXT REFERENCES milestones(ms_id),
    mbo_id        TEXT REFERENCES mbo_objectives(mbo_id),
    status        TEXT CHECK(status IN ('完', '進', '이슈')),
    prev_item_id  INTEGER REFERENCES report_items(item_id)
);

CREATE INDEX IF NOT EXISTS idx_items_report ON report_items(report_id);
CREATE INDEX IF NOT EXISTS idx_items_ms     ON report_items(ms_id);
CREATE INDEX IF NOT EXISTS idx_items_prev   ON report_items(prev_item_id);

-- ============================================================
-- 8. item_attachments
-- ============================================================
CREATE TABLE IF NOT EXISTS item_attachments (
    att_id  INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id INTEGER NOT NULL REFERENCES report_items(item_id) ON DELETE CASCADE,
    kind    TEXT NOT NULL DEFAULT 'image',
    path    TEXT NOT NULL,
    caption TEXT
);

-- ============================================================
-- 9. tags
-- ============================================================
CREATE TABLE IF NOT EXISTS tags (
    tag_id INTEGER PRIMARY KEY AUTOINCREMENT,
    name   TEXT NOT NULL UNIQUE
);

-- ============================================================
-- 10. item_tags
-- ============================================================
CREATE TABLE IF NOT EXISTS item_tags (
    item_id INTEGER NOT NULL REFERENCES report_items(item_id) ON DELETE CASCADE,
    tag_id  INTEGER NOT NULL REFERENCES tags(tag_id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, tag_id)
);

-- ============================================================
-- 11. delta_spans
-- ============================================================
CREATE TABLE IF NOT EXISTS delta_spans (
    span_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id   INTEGER NOT NULL REFERENCES report_items(item_id) ON DELETE CASCADE,
    field     TEXT NOT NULL CHECK(field IN ('progress_text', 'schedule_text', 'risk_text')),
    start_pos INTEGER NOT NULL,
    end_pos   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_delta_spans_item ON delta_spans(item_id);
