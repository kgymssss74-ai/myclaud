document.addEventListener('DOMContentLoaded', () => {
    // Lock Screen Logic
    const lockScreen = document.getElementById('lock-screen');
    const mainApp = document.getElementById('main-app');
    const pinInput = document.getElementById('pin-input');
    const unlockBtn = document.getElementById('unlock-btn');
    const lockError = document.getElementById('lock-error');
    
    let authToken = sessionStorage.getItem('authToken');

    function unlockApp(token) {
        authToken = token;
        sessionStorage.setItem('authToken', token);
        lockScreen.classList.add('hidden');
        mainApp.classList.remove('hidden');
    }

    if (authToken) {
        unlockApp(authToken);
    }

    unlockBtn.addEventListener('click', () => {
        const pin = pinInput.value.trim();
        if (pin === "champ00^^") { // Frontend check (backend also verifies)
            unlockApp(pin);
        } else {
            lockError.classList.remove('hidden');
            pinInput.value = '';
            setTimeout(() => lockError.classList.add('hidden'), 2000);
        }
    });

    pinInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') unlockBtn.click();
    });

    // 날짜 초기화
    const today = new Date();
    const lastYear = new Date();
    lastYear.setFullYear(today.getFullYear() - 1);

    document.getElementById('date-before').value = today.toISOString().split('T')[0];
    document.getElementById('date-after').value = lastYear.toISOString().split('T')[0];

    const runBtn = document.getElementById('run-btn');
    const keywordInput = document.getElementById('keyword');
    const progressContainer = document.getElementById('progress-container');
    const progressText = document.getElementById('progress-text');
    const progressPct = document.getElementById('progress-pct');
    const progressBarFill = document.getElementById('progress-bar-fill');
    const logView = document.getElementById('log-view');
    const resultsContainer = document.getElementById('results-container');
    const resultsList = document.getElementById('results-list');
    const resultCount = document.getElementById('result-count');
    const copyBtn = document.getElementById('copy-btn');

    let collectedUrls = [];
    let eventSource = null;

    function appendLog(msg, isError = false) {
        const div = document.createElement('div');
        div.textContent = msg;
        if (isError) div.className = 'log-error';
        logView.appendChild(div);
        logView.scrollTop = logView.scrollHeight;
    }

    runBtn.addEventListener('click', () => {
        const keyword = keywordInput.value.trim();
        if (!keyword) {
            alert('검색 키워드를 입력해주세요.');
            return;
        }

        const dateAfterRaw = document.getElementById('date-after').value.replace(/-/g, '');
        const dateBeforeRaw = document.getElementById('date-before').value.replace(/-/g, '');
        const maxLinks = document.getElementById('max-links').value;

        if (dateAfterRaw > dateBeforeRaw) {
            alert('시작일이 종료일보다 늦을 수 없습니다.');
            return;
        }

        // UI Reset
        runBtn.disabled = true;
        logView.innerHTML = '';
        resultsList.innerHTML = '';
        collectedUrls = [];
        resultCount.textContent = '0';
        
        progressContainer.classList.remove('hidden');
        resultsContainer.classList.add('hidden');
        progressBarFill.style.width = '0%';
        progressPct.textContent = '0%';
        progressText.textContent = '처리 중...';

        if (eventSource) {
            eventSource.close();
        }

        const url = `/api/search?keyword=${encodeURIComponent(keyword)}&date_after=${dateAfterRaw}&date_before=${dateBeforeRaw}&max_links=${maxLinks}&token=${authToken}`;
        
        eventSource = new EventSource(url);

        eventSource.addEventListener('log', (e) => {
            const data = JSON.parse(e.data);
            appendLog(data.msg);
        });

        eventSource.addEventListener('progress', (e) => {
            const data = JSON.parse(e.data);
            const current = data.current;
            const total = data.total;
            if (total > 0) {
                const pct = Math.floor((current / total) * 100);
                progressBarFill.style.width = `${pct}%`;
                progressPct.textContent = `${pct}%`;
                progressText.textContent = `항목 필터링 중... ${current}/${total}`;
            }
        });

        eventSource.addEventListener('error', (e) => {
            const data = JSON.parse(e.data);
            appendLog(`오류: ${data.msg}`, true);
            progressText.textContent = '오류 발생';
            runBtn.disabled = false;
            eventSource.close();
        });

        eventSource.addEventListener('finished', (e) => {
            const data = JSON.parse(e.data);
            const results = data.results;
            
            progressBarFill.style.width = '100%';
            progressPct.textContent = '100%';
            progressText.textContent = '완료됨';
            
            appendLog('\n==================================================');
            appendLog('🎯 [최종 수집 결과] (조회수 내림차순)');
            appendLog('==================================================\n');

            resultCount.textContent = results.length;
            resultsContainer.classList.remove('hidden');

            results.forEach((item, index) => {
                collectedUrls.push(item.url);
                
                appendLog(`[${index + 1}] ${item.title}`);
                appendLog(`  ► 조회수: ${item.view_count.toLocaleString()} | 업로드: ${item.upload_date}`);
                appendLog(`  ► ${item.url}\n`);

                const resultItem = document.createElement('div');
                resultItem.className = 'result-item';
                
                // Formatter
                let dateFormatted = item.upload_date;
                if(dateFormatted && dateFormatted.length === 8) {
                    dateFormatted = `${dateFormatted.slice(0,4)}-${dateFormatted.slice(4,6)}-${dateFormatted.slice(6,8)}`;
                }

                resultItem.innerHTML = `
                    <div class="result-title">${item.title}</div>
                    <div class="result-meta">
                        <span><i class="fas fa-eye"></i> ${item.view_count.toLocaleString()}</span>
                        <span><i class="far fa-calendar"></i> ${dateFormatted}</span>
                    </div>
                    <a href="${item.url}" target="_blank" class="result-url">${item.url}</a>
                `;
                resultsList.appendChild(resultItem);
            });

            if(results.length > 0) {
                // 진동 피드백 (모바일)
                if (navigator.vibrate) navigator.vibrate(200);
            }

            runBtn.disabled = false;
            eventSource.close();
        });
    });

    copyBtn.addEventListener('click', async () => {
        if (collectedUrls.length === 0) return;
        
        const textToCopy = collectedUrls.join('\n');
        try {
            await navigator.clipboard.writeText(textToCopy);
            
            const originalHtml = copyBtn.innerHTML;
            copyBtn.innerHTML = '<i class="fas fa-check" style="color: #10b981;"></i>';
            if (navigator.vibrate) navigator.vibrate(100);
            
            setTimeout(() => {
                copyBtn.innerHTML = originalHtml;
            }, 2000);
        } catch (err) {
            console.error('Failed to copy text: ', err);
            
            // Fallback
            const textArea = document.createElement("textarea");
            textArea.value = textToCopy;
            document.body.appendChild(textArea);
            textArea.select();
            try {
                document.execCommand('copy');
                alert('복사되었습니다.');
            } catch (err2) {
                alert('클립보드 복사에 실패했습니다.');
            }
            document.body.removeChild(textArea);
        }
    });
});
