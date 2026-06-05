"""
backup.py — 일1회 VACUUM INTO + uploads 동봉 ZIP.

Usage:
    python backup/backup.py [--db weekly.db] [--uploads uploads/] [--out backup/]
"""
import argparse
import os
import sqlite3
import zipfile
from datetime import date
from pathlib import Path


def run_backup(db_path: str, upload_dir: str, out_dir: str) -> str:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    today = date.today().strftime('%Y%m%d')
    vacuumed = out / f'weekly_{today}.db'
    zip_path  = out / f'backup_{today}.zip'

    # VACUUM INTO creates a clean copy
    conn = sqlite3.connect(db_path)
    conn.execute('PRAGMA integrity_check')
    conn.execute(f'VACUUM INTO ?', (str(vacuumed),))
    conn.close()

    with zipfile.ZipFile(str(zip_path), 'w', zipfile.ZIP_DEFLATED) as zf:
        zf.write(str(vacuumed), arcname=vacuumed.name)
        upload_root = Path(upload_dir)
        if upload_root.exists():
            for f in upload_root.rglob('*'):
                if f.is_file():
                    zf.write(str(f), arcname=str(f.relative_to(upload_root.parent)))

    os.remove(str(vacuumed))
    print(f'Backup created: {zip_path}')
    return str(zip_path)


def main():
    parser = argparse.ArgumentParser(description='Backup weekly.db + uploads to ZIP')
    parser.add_argument('--db',      default='weekly.db')
    parser.add_argument('--uploads', default='uploads/')
    parser.add_argument('--out',     default='backup/')
    args = parser.parse_args()
    run_backup(args.db, args.uploads, args.out)


if __name__ == '__main__':
    main()
