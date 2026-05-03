"""
gui.py - PyQt6 메인 GUI 모듈
"""
import sys
from PyQt6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
    QLineEdit, QDateEdit, QComboBox, QPushButton, QProgressBar,
    QTextEdit, QLabel, QGroupBox, QMessageBox, QApplication
)
from PyQt6.QtCore import QDate, Qt
from PyQt6.QtGui import QFont, QTextCursor

from loguru import logger
from collector import YouTubeCollectorWorker

class MainWindow(QMainWindow):

    STYLE = """
    QMainWindow { background-color: #f5f7fa; }
    QGroupBox {
        font-weight: bold; font-size: 13px;
        border: 1.5px solid #d0d7de; border-radius: 8px;
        margin-top: 8px; padding: 12px 10px 10px 10px;
        background: white;
    }
    QGroupBox::title { subcontrol-origin: margin; left: 10px; padding: 0 4px; color: #1a73e8; }
    QLineEdit, QDateEdit, QComboBox {
        border: 1.5px solid #d0d7de; border-radius: 6px;
        padding: 6px 10px; font-size: 13px; background: white;
    }
    QLineEdit:focus, QDateEdit:focus, QComboBox:focus { border-color: #1a73e8; }
    QPushButton {
        border-radius: 7px; padding: 8px 18px;
        font-size: 13px; font-weight: 600;
    }
    QPushButton#runBtn {
        background: #1a73e8; color: white; border: none;
        padding: 10px 28px; font-size: 14px;
    }
    QPushButton#runBtn:hover { background: #1557b0; }
    QPushButton#runBtn:disabled { background: #b0c4e8; }
    QPushButton#copyBtn { background: #34a853; color: white; border: none; }
    QPushButton#copyBtn:hover { background: #2d8f47; }
    QPushButton#copyBtn:disabled { background: #b0d8bc; }
    QProgressBar {
        border: 1.5px solid #d0d7de; border-radius: 6px;
        background: #f0f0f0; height: 18px; text-align: center; font-size: 11px;
    }
    QProgressBar::chunk { background: #1a73e8; border-radius: 5px; }
    QTextEdit {
        border: 1.5px solid #d0d7de; border-radius: 8px;
        background: #1e1e2e; color: #cdd6f4;
        font-family: 'Consolas', 'D2Coding', monospace; font-size: 13px;
        padding: 8px;
    }
    """

    def __init__(self):
        super().__init__()
        self.setWindowTitle("YouTube Link Collector (조회수 정렬)")
        self.setMinimumSize(700, 600)
        self.setStyleSheet(self.STYLE)
        
        self.worker = None
        self.collected_urls = []
        
        self._build_ui()

    def _build_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setSpacing(12)
        root.setContentsMargins(16, 16, 16, 16)

        # 타이틀
        title = QLabel("📺 YouTube 자동 검색 및 정렬 수집기")
        title.setFont(QFont("Segoe UI", 16, QFont.Weight.Bold))
        title.setStyleSheet("color: #1a73e8; margin-bottom: 4px;")
        root.addWidget(title)

        # ── 검색 조건 ──
        input_box = QGroupBox("검색 조건 설정")
        grid = QGridLayout(input_box)
        grid.setSpacing(10)

        # 키워드
        grid.addWidget(QLabel("검색 키워드"), 0, 0)
        self.keyword_input = QLineEdit()
        self.keyword_input.setPlaceholderText("예: audio engine C++")
        grid.addWidget(self.keyword_input, 0, 1, 1, 3)

        # 날짜
        grid.addWidget(QLabel("시작일"), 1, 0)
        self.date_after = QDateEdit()
        self.date_after.setCalendarPopup(True)
        self.date_after.setDate(QDate.currentDate().addYears(-1))
        self.date_after.setDisplayFormat("yyyy-MM-dd")
        grid.addWidget(self.date_after, 1, 1)

        grid.addWidget(QLabel("종료일"), 1, 2)
        self.date_before = QDateEdit()
        self.date_before.setCalendarPopup(True)
        self.date_before.setDate(QDate.currentDate())
        self.date_before.setDisplayFormat("yyyy-MM-dd")
        grid.addWidget(self.date_before, 1, 3)

        # 최대 개수 프리셋
        grid.addWidget(QLabel("최대 수집 개수"), 2, 0)
        self.preset_combo = QComboBox()
        self.preset_combo.addItems(["50", "100", "200", "300"])
        grid.addWidget(self.preset_combo, 2, 1)

        root.addWidget(input_box)

        # ── 실행 버튼 및 프로그레스 ──
        ctrl_layout = QHBoxLayout()
        self.run_btn = QPushButton("▶ 수집 시작")
        self.run_btn.setObjectName("runBtn")
        self.run_btn.setFixedHeight(42)
        self.run_btn.clicked.connect(self._on_run)
        ctrl_layout.addWidget(self.run_btn)
        
        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 100)
        self.progress_bar.setValue(0)
        self.progress_bar.setFixedHeight(22)
        ctrl_layout.addWidget(self.progress_bar)

        root.addLayout(ctrl_layout)

        # ── 상태 로그 및 결과 아웃풋 ──
        self.log_view = QTextEdit()
        self.log_view.setReadOnly(True)
        self.log_view.setPlaceholderText("진행 로그 및 수집된 링크가 여기에 출력됩니다...")
        root.addWidget(self.log_view)
        
        # ── 하단 컨트롤 ──
        bottom_layout = QHBoxLayout()
        bottom_layout.addStretch()
        self.copy_btn = QPushButton("📋 결과 링크 복사")
        self.copy_btn.setObjectName("copyBtn")
        self.copy_btn.setEnabled(False)
        self.copy_btn.clicked.connect(self._on_copy_links)
        bottom_layout.addWidget(self.copy_btn)
        root.addLayout(bottom_layout)

    def _append_log(self, msg: str):
        self.log_view.append(msg)
        # 자동 스크롤
        cursor = self.log_view.textCursor()
        cursor.movePosition(QTextCursor.MoveOperation.End)
        self.log_view.setTextCursor(cursor)

    def _on_run(self):
        keyword = self.keyword_input.text().strip()
        if not keyword:
            QMessageBox.warning(self, "입력 오류", "검색 키워드를 입력해주세요.")
            return

        if self.date_after.date() > self.date_before.date():
            QMessageBox.warning(self, "날짜 오류", "시작일이 종료일보다 늦을 수 없습니다.")
            return

        max_links = int(self.preset_combo.currentText())

        # UI 초기화
        self.run_btn.setEnabled(False)
        self.copy_btn.setEnabled(False)
        self.progress_bar.setValue(0)
        self.log_view.clear()
        self.collected_urls.clear()

        # 워커 실행
        self.worker = YouTubeCollectorWorker(
            keyword=keyword,
            date_after=self.date_after.date().toString("yyyyMMdd"),
            date_before=self.date_before.date().toString("yyyyMMdd"),
            max_links=max_links
        )
        self.worker.log_msg.connect(self._append_log)
        self.worker.progress.connect(self._on_progress)
        self.worker.finished_success.connect(self._on_finished)
        self.worker.error.connect(self._on_error)
        self.worker.start()

    def _on_progress(self, current: int, total: int):
        if total > 0:
            pct = int(current / total * 100)
            self.progress_bar.setValue(pct)
            self.progress_bar.setFormat(f"처리 중... {current}/{total} ({pct}%)")

    def _on_finished(self, results: list):
        self.progress_bar.setValue(100)
        self.progress_bar.setFormat("완료됨")
        self.run_btn.setEnabled(True)
        
        self._append_log("\n" + "="*50)
        self._append_log("🎯 [최종 수집 결과] (조회수 내림차순)")
        self._append_log("="*50 + "\n")
        
        for i, item in enumerate(results, 1):
            url = item['url']
            title = item['title']
            views = item['view_count']
            date = item['upload_date']
            
            self.collected_urls.append(url)
            self._append_log(f"[{i}] {title}\n  ► 조회수: {views:,} | 업로드: {date}\n  ► {url}\n")
            
        if self.collected_urls:
            self.copy_btn.setEnabled(True)
            QMessageBox.information(self, "완료", f"총 {len(results)}개의 링크 수집이 완료되었습니다.")
        else:
            QMessageBox.warning(self, "결과 없음", "검색 및 필터링 결과가 없습니다.")

    def _on_error(self, err_msg: str):
        self.run_btn.setEnabled(True)
        self.progress_bar.setValue(0)
        self.progress_bar.setFormat("오류 발생")
        QMessageBox.critical(self, "에러", f"수집 중 오류가 발생했습니다:\n{err_msg}")

    def _on_copy_links(self):
        if not self.collected_urls:
            return
        
        text = "\n".join(self.collected_urls)
        cb = QApplication.clipboard()
        cb.setText(text)
        QMessageBox.information(self, "복사 완료", "결과 링크가 클립보드에 복사되었습니다.")
