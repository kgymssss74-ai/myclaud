import asyncio
import json
from datetime import datetime
from typing import AsyncGenerator

from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import StreamingResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from loguru import logger
import yt_dlp
import os

app = FastAPI()

SECRET_TOKEN = "champ00^^"

# Add CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], # 배포 환경을 위해 모든 도메인 허용
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

frontend_path = os.path.join(os.path.dirname(__file__), "frontend")
if not os.path.exists(frontend_path):
    os.makedirs(frontend_path)

def _in_date_range(date_str: str, after: str, before: str) -> bool:
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

async def search_youtube_generator(keyword: str, date_after: str, date_before: str, max_links: int) -> AsyncGenerator[str, None]:
    def sse_message(event: str, data: dict):
        return f"event: {event}\ndata: {json.dumps(data)}\n\n"
    
    yield sse_message("log", {"msg": f"🔍 검색 시작: '{keyword}'"})
    yield sse_message("log", {"msg": f"📅 기간 설정: {date_after} ~ {date_before}"})
    yield sse_message("log", {"msg": f"🎯 목표 개수: {max_links}개"})
    
    search_pool = max_links * 3
    yield sse_message("log", {"msg": f"데이터 확보를 위해 {search_pool}개를 먼저 검색합니다..."})

    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,
        "skip_download": True,
        "playlistend": search_pool,
    }
    
    query = f"ytsearch{search_pool}:{keyword}"
    
    yield sse_message("log", {"msg": "yt-dlp 엔진 검색 중... (수십 초 소요될 수 있습니다)"})
    
    def fetch_info():
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(query, download=False)
            return info.get("entries", []) or []

    try:
        raw_results = await asyncio.to_thread(fetch_info)
        yield sse_message("log", {"msg": f"✅ 원본 검색 결과: {len(raw_results)}개 수신 완료"})
        
        filtered_results = []
        total = len(raw_results)
        
        for i, entry in enumerate(raw_results):
            if not entry or not entry.get("id"):
                continue

            upload_date = entry.get("upload_date", "")
            if not _in_date_range(upload_date, date_after, date_before):
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
            
            if i % 10 == 0:
                yield sse_message("progress", {"current": i, "total": total})
                await asyncio.sleep(0.01) # Small sleep to yield control
                
        yield sse_message("progress", {"current": total, "total": total})
        yield sse_message("log", {"msg": f"📅 날짜 필터 통과 결과: {len(filtered_results)}개"})
        
        yield sse_message("log", {"msg": "📈 조회수 기준으로 내림차순 정렬 중..."})
        filtered_results.sort(key=lambda x: x["view_count"], reverse=True)
        
        final_results = filtered_results[:max_links]
        yield sse_message("log", {"msg": f"🎉 최종 확정 결과: {len(final_results)}개"})
        
        yield sse_message("finished", {"results": final_results})
        
    except Exception as e:
        logger.error(f"수집 실패: {e}")
        yield sse_message("error", {"msg": str(e)})

@app.get("/api/search")
async def api_search(keyword: str, date_after: str, date_before: str, max_links: int, token: str = ""):
    if token != SECRET_TOKEN:
        raise HTTPException(status_code=401, detail="Unauthorized")
        
    return StreamingResponse(
        search_youtube_generator(keyword, date_after, date_before, max_links), 
        media_type="text/event-stream"
    )

# Serve frontend static files
@app.get("/")
async def root():
    index_path = os.path.join(frontend_path, "index.html")
    if os.path.exists(index_path):
        with open(index_path, "r", encoding="utf-8") as f:
            return HTMLResponse(content=f.read())
    return HTMLResponse(content="<h1>Frontend not found. Please create 'frontend/index.html'</h1>")

app.mount("/", StaticFiles(directory=frontend_path, html=True), name="frontend")

if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 8000))
    uvicorn.run("server:app", host="0.0.0.0", port=port, reload=False)
