"""
collector.py - yt-dlp 기반 YouTube 링크 수집 및 정렬기 (QThread)
"""
from datetime import datetime
from typing import Optional, List, Dict
from loguru import logger
from PyQt6.QtCore import QThread, pyqtSignal
import yt_dlp

class YouTubeCollectorWorker(QThread):
    # (msg)
    log_msg = pyqtSignal(str)
    # (current, total)
    progress = pyqtSignal(int, int)
    # (results: list of dicts)
    finished_success = pyqtSignal(list)
    # (error_msg)
    error = pyqtSignal(str)

    def __init__(self, keyword: str, date_after: str, date_before: str, max_links: int):
        super().__init__()
        self.keyword = keyword
        self.date_after = date_after  # "YYYYMMDD"
        self.date_before = date_before  # "YYYYMMDD"
        self.max_links = max_links

    def run(self):
        try:
            self._log(f"🔍 검색 시작: '{self.keyword}'")
            self._log(f"📅 기간 설정: {self.date_after} ~ {self.date_before}")
            self._log(f"🎯 목표 개수: {self.max_links}개")
            
            # YouTube 검색 시 더 많은 풀을 가져와서 정렬/필터링을 수행하기 위해 배수로 요청
            # 단, 너무 많이 요청하면 시간이 오래 걸리거나 응답이 끊길 수 있음.
            search_pool = self.max_links * 3 
            self._log(f"데이터 확보를 위해 {search_pool}개를 먼저 검색합니다...")

            ydl_opts = {
                "quiet": True,
                "no_warnings": True,
                "extract_flat": True,
                "skip_download": True,
                "playlistend": search_pool,
            }

            query = f"ytsearch{search_pool}:{self.keyword}"
            raw_results = []

            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                self._log("yt-dlp 엔진 검색 중... (수십 초 소요될 수 있습니다)")
                info = ydl.extract_info(query, download=False)
                raw_results = info.get("entries", []) or []

            self._log(f"✅ 원본 검색 결과: {len(raw_results)}개 수신 완료")

            # 1. 날짜 필터링 및 데이터 정제
            filtered_results = []
            for i, entry in enumerate(raw_results):
                if not entry or not entry.get("id"):
                    continue

                upload_date = entry.get("upload_date", "")
                if not self._in_date_range(upload_date, self.date_after, self.date_before):
                    continue

                view_count = entry.get("view_count") or 0
                title = entry.get("title", "제목 없음")
                url = f"https://www.youtube.com/watch?v={entry['id']}"

                filtered_results.append({
                    "url": url,
                    "title": title,
                    "upload_date": upload_date,
                    "view_count": view_count,
                    "channel": entry.get("channel") or entry.get("uploader") or "알 수 없음",
                })
                
                # 진행 상황 업데이트
                if i % 10 == 0:
                    self.progress.emit(i, len(raw_results))

            self.progress.emit(len(raw_results), len(raw_results))
            self._log(f"📅 날짜 필터 통과 결과: {len(filtered_results)}개")

            # 2. 조회수 내림차순 정렬
            self._log("📈 조회수 기준으로 내림차순 정렬 중...")
            filtered_results.sort(key=lambda x: x["view_count"], reverse=True)

            # 3. 최대 개수 제한 적용
            final_results = filtered_results[:self.max_links]
            
            self._log(f"🎉 최종 확정 결과: {len(final_results)}개")
            self.finished_success.emit(final_results)

        except Exception as e:
            logger.error(f"수집 실패: {e}")
            self.error.emit(str(e))

    def _log(self, msg: str):
        logger.info(msg)
        self.log_msg.emit(msg)

    def _in_date_range(self, date_str: str, after: str, before: str) -> bool:
        """날짜 범위 검증 (YYYYMMDD)"""
        if not date_str:
            return True
        try:
            d = datetime.strptime(date_str, "%Y%m%d")
            if after:
                if d < datetime.strptime(after, "%Y%m%d"):
                    return False
            if before:
                if d > datetime.strptime(before, "%Y%m%d"):
                    return False
        except ValueError:
            return True
        return True
