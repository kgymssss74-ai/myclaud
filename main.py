"""
main.py - 프로젝트 진입점
"""
import sys
from PyQt6.QtWidgets import QApplication
from PyQt6.QtGui import QIcon
from gui import MainWindow
from loguru import logger

def main():
    logger.add("youtube_collector.log", rotation="10 MB", level="INFO")
    
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    
    window = MainWindow()
    window.show()
    
    sys.exit(app.exec())

if __name__ == "__main__":
    main()
