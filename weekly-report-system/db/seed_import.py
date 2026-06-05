"""
seed_import.py — Import CSV seed data into weekly.db.

Usage (from weekly-report-system/ directory):
    python db/seed_import.py [--db PATH] [--csv-dir PATH]

Defaults:
    --db      weekly.db
    --csv-dir ../uploads/<latest uuid dir>  (auto-detected, or pass explicitly)

CSV files expected (romanized filenames, BOM-safe UTF-8):
    members.csv, member_display_names.csv, projects.csv,
    milestones.csv, mbo_template.csv, tags.csv
"""

import argparse
import csv
import io
import sqlite3
import sys
from pathlib import Path


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def nullable(val: str) -> str | None:
    v = val.strip() if val else ''
    return v if v else None


def read_csv(path: Path) -> list[dict]:
    """Read CSV, skipping blank lines and lines starting with '#'."""
    lines: list[str] = []
    with open(path, encoding='utf-8-sig') as f:
        for line in f:
            stripped = line.strip()
            if not stripped or stripped.startswith('#'):
                continue
            lines.append(line)
    reader = csv.DictReader(io.StringIO(''.join(lines)))
    return list(reader)


def connect(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path)
    conn.execute('PRAGMA foreign_keys = ON')
    conn.execute('PRAGMA journal_mode = WAL')
    schema = (Path(__file__).parent / 'schema.sql').read_text(encoding='utf-8')
    conn.executescript(schema)
    return conn


# ---------------------------------------------------------------------------
# Import functions (FK-safe order)
# ---------------------------------------------------------------------------

def import_members(conn: sqlite3.Connection, csv_dir: Path) -> None:
    members = {r['member_id']: r for r in read_csv(csv_dir / 'members.csv')}
    display = {r['member_id']: r['display_name']
               for r in read_csv(csv_dir / 'member_display_names.csv')}

    rows = []
    for mid, r in members.items():
        rows.append((
            mid,
            r['name'],
            display.get(mid),
            r['tg'],
            int(r['tg_order']),
            nullable(r['cl_level']),
            nullable(r['mentor']),
            int(r.get('is_part_leader', '0') or '0'),
        ))

    conn.executemany(
        '''INSERT OR REPLACE INTO members
           (member_id, name, display_name, tg, tg_order, cl_level, mentor, is_part_leader)
           VALUES (?,?,?,?,?,?,?,?)''',
        rows,
    )
    conn.commit()
    print(f'  members: {len(rows)} rows')


def import_projects(conn: sqlite3.Connection, csv_dir: Path) -> None:
    rows = []
    for r in read_csv(csv_dir / 'projects.csv'):
        track = r['track'].strip()
        if track not in ('device', 'advance', 'review'):
            print(f"  WARNING: unknown track {track!r} for {r['project_id']}, skipping")
            continue
        rows.append((
            r['project_id'],
            r['name'],
            track,
            nullable(r['platform']),
            nullable(r['background']),
            nullable(r['owner_member_id']),
            nullable(r['created_week']),
            nullable(r['tg']),
        ))

    conn.executemany(
        '''INSERT OR REPLACE INTO projects
           (project_id, name, track, platform, background,
            owner_member_id, created_week, tg)
           VALUES (?,?,?,?,?,?,?,?)''',
        rows,
    )
    conn.commit()
    print(f'  projects: {len(rows)} rows')


def import_milestones(conn: sqlite3.Connection, csv_dir: Path) -> None:
    rows = []
    for i, r in enumerate(read_csv(csv_dir / 'milestones.csv'), start=1):
        pid = r['project_id']
        ms_type = r['type']
        ms_id = f'MS{i:03d}'
        status = nullable(r['status']) or 'pending'
        rows.append((
            ms_id,
            pid,
            ms_type,
            nullable(r['planned_date']),
            nullable(r['actual_date']),
            status,
        ))

    conn.executemany(
        '''INSERT OR REPLACE INTO milestones
           (ms_id, project_id, type, planned_date, actual_date, status)
           VALUES (?,?,?,?,?,?)''',
        rows,
    )
    conn.commit()
    print(f'  milestones: {len(rows)} rows')


def import_mbo(conn: sqlite3.Connection, csv_dir: Path) -> None:
    rows = []
    for r in read_csv(csv_dir / 'mbo_template.csv'):
        if not r.get('mbo_id') or not r.get('title', '').strip():
            continue
        rows.append((
            r['mbo_id'],
            r['member_id'],
            int(r['year']),
            r['title'].strip(),
            int(r['weight']) if r.get('weight', '').strip() else None,
            nullable(r.get('target_metric', '')),
            nullable(r.get('target_value', '')),
            nullable(r.get('linked_project_id', '')),
        ))

    conn.executemany(
        '''INSERT OR REPLACE INTO mbo_objectives
           (mbo_id, member_id, year, title, weight, target_metric,
            target_value, linked_project_id)
           VALUES (?,?,?,?,?,?,?,?)''',
        rows,
    )
    conn.commit()
    print(f'  mbo_objectives: {len(rows)} rows')


def import_tags(conn: sqlite3.Connection, csv_dir: Path) -> None:
    rows = [(r['name'],) for r in read_csv(csv_dir / 'tags.csv') if r.get('name')]
    conn.executemany('INSERT OR IGNORE INTO tags(name) VALUES (?)', rows)
    conn.commit()
    print(f'  tags: {len(rows)} rows')


# ---------------------------------------------------------------------------
# Sanity checks
# ---------------------------------------------------------------------------

def sanity_check(conn: sqlite3.Connection) -> bool:
    ok = True
    cur = conn.cursor()

    (member_count,) = cur.execute('SELECT COUNT(*) FROM members').fetchone()
    if member_count != 17:
        print(f'  FAIL members count={member_count} (expected 17)')
        ok = False
    else:
        print(f'  OK   members == 17')

    for track, expected in (('device', 9), ('advance', 7), ('review', 4)):
        (cnt,) = cur.execute(
            'SELECT COUNT(*) FROM projects WHERE track=?', (track,)
        ).fetchone()
        if cnt != expected:
            print(f'  FAIL projects {track}={cnt} (expected {expected})')
            ok = False
        else:
            print(f'  OK   projects {track} == {expected}')

    bad_ms = cur.execute(
        'SELECT ms_id FROM milestones WHERE project_id NOT IN (SELECT project_id FROM projects)'
    ).fetchall()
    if bad_ms:
        print(f'  FAIL orphan milestones: {bad_ms}')
        ok = False
    else:
        print('  OK   all milestones.project_id exist in projects')

    bad_proj = cur.execute(
        'SELECT project_id FROM projects WHERE owner_member_id IS NOT NULL'
        ' AND owner_member_id NOT IN (SELECT member_id FROM members)'
    ).fetchall()
    if bad_proj:
        print(f'  FAIL projects with unknown owner: {bad_proj}')
        ok = False
    else:
        print('  OK   all project.owner_member_id exist in members')

    return ok


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description='Import seed CSV data into weekly.db')
    parser.add_argument('--db', default='weekly.db', help='SQLite DB path')
    parser.add_argument('--csv-dir', default=None,
                        help='Directory containing CSV files')
    args = parser.parse_args()

    csv_dir: Path
    if args.csv_dir:
        csv_dir = Path(args.csv_dir)
    else:
        upload_root = Path('/root/.claude/uploads')
        candidates = sorted(upload_root.glob('*/members.csv'))
        if not candidates:
            sys.exit('ERROR: Could not find members.csv. Pass --csv-dir explicitly.')
        csv_dir = candidates[-1].parent
        print(f'Auto-detected CSV dir: {csv_dir}')

    print(f'Importing into: {args.db}')
    conn = connect(args.db)

    print('Importing members...')
    import_members(conn, csv_dir)

    print('Importing projects...')
    import_projects(conn, csv_dir)

    print('Importing milestones...')
    import_milestones(conn, csv_dir)

    print('Importing MBO objectives...')
    import_mbo(conn, csv_dir)

    print('Importing tags...')
    import_tags(conn, csv_dir)

    print('\nSanity checks:')
    ok = sanity_check(conn)
    conn.close()

    if ok:
        print('\nSeed import PASSED.')
    else:
        print('\nSeed import FAILED — see above.')
        sys.exit(1)


if __name__ == '__main__':
    main()
