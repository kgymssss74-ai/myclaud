@echo off
chcp 65001 >nul
cd /d "D:\Claud\youtube-collecting"
echo [%date% %time%] Starting... > run_output.txt 2>&1
where python >> run_output.txt 2>&1
python --version >> run_output.txt 2>&1
python -c "import yt_dlp; print('yt_dlp OK')" >> run_output.txt 2>&1
python -c "import PyQt6; print('PyQt6 OK')" >> run_output.txt 2>&1
echo Done. >> run_output.txt 2>&1
