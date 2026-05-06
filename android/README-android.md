# Audio Verify (Galaxy S25 / Tab S10)

오디오 스트림 타입(Ringtone / Deep Buffer / Fast Track LL / ULL / Offload / Fast Others) × 포맷(WAV / MP3 / AAC / MP4) × 라우트(Speaker / BT / USB) 매트릭스를 자동·수동으로 검증하는 안드로이드 앱.

## 빌드 (GitHub Actions)

`.github/workflows/android.yml`이 main 또는 `claude/**` 브랜치 푸시·PR마다 실행:

1. JDK 17 + Android SDK 35 + NDK 26 + cmake 3.22.1 + ffmpeg 설치
2. `gradle wrapper --gradle-version 8.9`로 래퍼 부트스트랩
3. `bash scripts/gen_assets.sh app/src/main/assets/audio`로 24개 음원 자동 생성
4. `./gradlew :app:assembleDebug`
5. `app-debug.apk`를 워크플로 artifact `app-debug-apk`로 업로드

artifact를 다운로드해 `adb install -r app-debug.apk`.

## 로컬 빌드

```bash
cd android
gradle wrapper --gradle-version 8.9   # 최초 1회
bash scripts/gen_assets.sh app/src/main/assets/audio
./gradlew :app:assembleDebug
```

## 음원 자산

`gen_assets.sh`가 빌드마다 6개 주파수 × 4개 포맷 = 24개 sine 톤을 생성. 각 streamType에 다른 주파수가 매핑되어 동시 재생 시 화음으로 들림:

| StreamType | Freq |
|---|---|
| RINGTONE | 440 Hz (A4) |
| DEEP_BUFFER | 523 Hz (C5) |
| FAST_LL | 659 Hz (E5) |
| ULL | 784 Hz (G5) |
| OFFLOAD | 880 Hz (A5) |
| FAST_OTHERS | 988 Hz (B5) |

## UI

### Manual 탭 — 빠른 검증, 다중 선택 지원
- **Source**: TONE(빌트인 톤) / FILE(SAF로 외부 파일)
- **Stream type**: 다중 선택. 동시 재생 시 각 freq의 화음으로 들림
- **Format**: 단일 선택. OFFLOAD가 선택 스트림에 포함되면 디바이스 미지원 포맷 회색
- **Route**: 다중 선택. **미연결 디바이스 칩은 회색 + 선택 불가**
- 선택 (streamType × route) 모든 조합에 대해 독립 엔진 인스턴스 → 동시 재생
- ULL EXCLUSIVE 미허용 시 errorContainer 색상 배너로 안내(비차단)

### Matrix 탭 — 자동 매트릭스
- **Start matrix**: streamType × format × route 자동 순회
- BT/USB 미연결 케이스는 자동 SKIP
- ULL EXCLUSIVE 거부 시 RelaxPromptDialog (Accept / FAIL / Retry)
- **OFFLOAD prune 토글** (기본 OFF): 켜면 디바이스 미지원 OFFLOAD 조합 자동 SKIP

### Reports 탭
- 상단 **Capabilities 카드**: Build.MODEL/SoC, OFFLOAD 지원 포맷, 출력 디바이스 목록. **Re-probe 버튼**으로 BT/USB 핫플러그 후 갱신
- 과거 실행 리포트: HTML/JSON 저장, Share로 외부 전송

### Help 다이얼로그
- TopAppBar 우측 ? 아이콘 → 사용 가이드 + 검증 절차 표시

## 사전 무효 조합 (matrix.json)

- `OFFLOAD × WAV` — 오프로드는 압축 비트스트림만 지원
- `RINGTONE × BT` — 안드로이드는 `USAGE_NOTIFICATION_RINGTONE`을 BT A2DP로 라우팅하지 않음

## 검증 절차 (Tier 0~3)

매 빌드/디바이스/주변기기 구성마다 한 번 실행해 디바이스 인증.

### Tier 0 — Smoke (주변기기 없음, 약 3분)

1. APK 설치 → 권한 부여 → Help 다이얼로그 표시 확인
2. Reports → Capabilities 카드: 디바이스 정보 + OFFLOAD caps 메모
3. Manual: TONE × WAV × SPK × 6개 streamType 단일 선택 순회 → 6개 주파수 청취 확인
4. Manual: TONE × LL × SPK × {MP3, AAC, MP4} 순회 → routedDeviceType=2
5. Manual: TONE × ULL × SPK → perfMode/sharing/MMAP 확인. 거부 시 fallback 배너
6. Manual: TONE × OFFLOAD × SPK × 각 Format 칩 → 미지원은 회색
7. Manual: FILE → 외부 mp3 → SPK → 들림
8. Manual 다중 선택: streams {LL, ULL} × routes {SPK} → 두 주파수 동시
9. 칩 토글 시 Status가 즉시 Idle로 리셋
10. Route 칩에서 BT, USB는 회색 (미연결)

### Tier 1 — Matrix 자동 (주변기기 없음, 약 10분)

1. Matrix → 토글 OFF로 Start
2. ULL EXCLUSIVE 거부 다이얼로그 응답 (S25: MARK_FAIL, Tab S10: ACCEPT_RELAXED)
3. 완료 → Reports에서 HTML 열기, Capabilities 카드와 일치 확인
4. (선택) 토글 ON으로 재실행 → OFFLOAD 미지원 케이스가 SKIPPED
5. `adb pull` → `jq . report.json | head` 파싱 OK, `prunedByOffloadCaps` 토글 상태 반영

### Tier 2 — 주변기기 (약 10분)

1. BT 페어링 → Capabilities Re-probe → Manual Route 칩에서 BT 활성
2. Manual: 비-RINGTONE 다중 stream × WAV × {SPK, BT} → 양쪽 들림, routedDeviceType per engine
3. USB 연결 → Re-probe → USB 활성
4. Manual: ULL × WAV × {SPK, USB} → 양쪽 들림
5. Matrix 재실행 → BT/USB 케이스 실제 verdicts 보유

### Tier 3 — 아티팩트 (약 2분)

```bash
adb pull /sdcard/Android/data/com.myclaud.audioverify/files/reports/<ts>/
jq '.records | length' report.json
jq '.prunedByOffloadCaps' report.json
jq '.records[] | select(.overall=="FAIL") | .caseId' report.json
open report.html
```

### 인증 기준
- 빌드 X 인증: Tier 0 전부 PASS + Tier 1 완주 + 예상 외 FAIL 0
- 풀 인증: + Tier 2 PASS

## 검증 방법론

- 케이스별 실측: `AudioTrack.getUnderrunCount()`, `AAudio_getXRunCount()`, `getRoutedDevice()`, AAudio `getPerformanceMode/getSharingMode` 모드 협상 결과
- 임계는 `app/src/main/assets/thresholds.json`. 매트릭스는 `assets/matrix.json`
- **자동 완화 코드 경로 없음.** ULL EXCLUSIVE 미허용 같은 디바이스 종속 결과는 항상 `RelaxationGate`를 통해 사람이 결정
- OFFLOAD prune 토글은 기본 OFF + 매번 의식적으로 ON
- 임계·매트릭스 JSON 또는 assertion 의미 변경 시 사람이 PR을 승인해야 함

## 모듈

| 모듈 | 역할 |
|---|---|
| `:app` | Compose UI, 권한, 매니페스트, Help/Capabilities |
| `:core-audio` | `PlaybackEngine` 인터페이스/구현, OffloadCapabilityProbe, 디코더, 라우팅 |
| `:core-runner` | 매트릭스, MatrixRunner, Assertion, RelaxationGate, JSON/HTML 리포터 |
| `:native-aaudio` | C++/CMake AAudio MMAP 협상 |

## 알려진 제한

- **마이크 루프백을 통한 글리치 검출은 v1 범위 외.** 카운터 + 라우팅 일치만 봄
- AAudio NDK는 `isMMap()` 직접 노출 API가 없어 `EXCLUSIVE + LOW_LATENCY`로 추정. `dumpsys media.audio_flinger`로 운영자가 교차 확인
- BT 코덱(LDAC vs SBC) 강제 불가 — 활성 코덱은 정보로만 기록
- STREAM_RING은 방해금지(DND) 모드에서 묵음 처리될 수 있음
- 동시 OFFLOAD 트랙은 일반적으로 디바이스가 1개만 허용 — 다중 선택 시 두 번째 엔진 open이 실패할 수 있음(status에 기록)
