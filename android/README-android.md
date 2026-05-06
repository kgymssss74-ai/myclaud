# Audio Verify (Galaxy S25 / Tab S10)

오디오 스트림 타입(Ringtone / Deep Buffer / Fast Track LL / ULL / Offload / Fast Others) × 포맷(WAV / MP3 / AAC / MP4) × 라우트(Speaker / BT / USB / Speaker+BT / Speaker+USB) 매트릭스를 자동·수동으로 검증하는 안드로이드 앱.

## 빌드 (GitHub Actions)

`.github/workflows/android.yml`이 main 또는 `claude/**` 브랜치 푸시·PR마다 실행:

1. JDK 17 + Android SDK 35 + NDK 26 + cmake 3.22.1 + ffmpeg 설치
2. `gradle wrapper --gradle-version 8.9`로 래퍼 부트스트랩
3. `./gradlew :app:assembleDebug`
4. `app-debug.apk`를 워크플로 artifact `app-debug-apk`로 업로드

artifact를 다운로드해 `adb install -r app-debug.apk`.

## 로컬 빌드 (선택)

```bash
cd android
gradle wrapper --gradle-version 8.9   # 최초 1회
bash scripts/gen_assets.sh app/src/main/assets/audio   # 음원 자동 생성
./gradlew :app:assembleDebug
```

요구사항:
- JDK 17
- Android SDK Platform 35, Build-Tools 35.0.0
- NDK 26.3.11579264, cmake 3.22.1
- `ffmpeg` (자산 자동 생성용)
- `local.properties`에 `sdk.dir=...` 지정

## 매트릭스 실행 절차

1. APK 설치 후 앱 실행 → 권한 부여(`MODIFY_AUDIO_SETTINGS`, `BLUETOOTH_CONNECT`, `READ_MEDIA_AUDIO`, `POST_NOTIFICATIONS`).
2. (선택) BT 헤드셋 페어링, USB-C 오디오 디바이스 연결. **연결 없이도 자동 매트릭스 실행 가능** — BT/USB가 필요한 케이스는 자동 SKIP되며 리포트에 사유 기록.
3. **Manual** 탭: 빌트인 톤(WAV/MP3/AAC/MP4) 또는 **Pick file**로 외부 파일을 선택해 검증. 파일은 SAF로 어떤 위치에서든 가져올 수 있고, 포맷은 확장자/MIME으로 자동 감지.
4. **Matrix** 탭: `Start matrix` → 모든 자동 케이스 순회.
   - 디바이스가 ULL EXCLUSIVE를 거부하면 `RelaxPromptDialog`가 뜸 → **Accept relaxed** / **Mark FAIL** / **Retry** 중 결정.
   - BT/USB 미연결 케이스는 자동 SKIP (`AUTO_SKIPPED`로 리포트 기록).
5. 완료되면 `Reports` 탭에서 HTML 공유 또는 `adb pull /sdcard/Android/data/com.myclaud.audioverify/files/reports/`.

## 사전 무효 조합 (matrix.json `invalidCombinations`)

- `OFFLOAD × WAV` — 오프로드는 압축 비트스트림만 지원
- `RINGTONE × BT`, `RINGTONE × SPEAKER_PLUS_BT` — 안드로이드는 `USAGE_NOTIFICATION_RINGTONE`을 BT A2DP로 라우팅하지 않음 (실측 확인된 OS 동작)

## 검증 방법론 (요약)

- 케이스별 실측: `AudioTrack.getUnderrunCount()`, `AAudio_getXRunCount()`, `getRoutedDevice()`, AAudio `getPerformanceMode/getSharingMode` 모드 협상 결과.
- 임계는 `app/src/main/assets/thresholds.json`. 매트릭스는 `assets/matrix.json`.
- **자동 완화 코드 경로 없음.** ULL EXCLUSIVE 미허용 같은 디바이스 종속 결과는 항상 `RelaxationGate`를 통해 사람이 결정.
- 임계·매트릭스 JSON 또는 assertion 의미를 바꿔야 한다면 사람이 PR을 승인해야 함(이 앱이 자체적으로 변경하지 않음).

## 모듈

| 모듈 | 역할 |
|---|---|
| `:app` | Compose UI, 권한, 매니페스트 |
| `:core-audio` | `PlaybackEngine` 인터페이스/구현(AudioTrack/Offload/MediaPlayer/AAudio JNI 래퍼), 디코더(MediaCodec/WAV), 라우팅 |
| `:core-runner` | 매트릭스, MatrixRunner, Assertion, RelaxationGate, JSON/HTML 리포터 |
| `:native-aaudio` | C++/CMake AAudio MMAP 협상 |

## 알려진 제한

- **마이크/USB 음향 루프백을 통한 글리치 검출은 v1 범위 외.** 카운터 + 라우팅 일치만 봄.
- AAudio NDK는 `isMMap()` 직접 노출 API가 없어 `EXCLUSIVE + LOW_LATENCY`로 추정. `dumpsys media.audio_flinger`로 운영자가 교차 확인 권장.
- BT 코덱(LDAC vs SBC) 강제 불가 — 활성 코덱은 정보로만 기록.
- STREAM_RING은 방해금지(DND) 모드에서 묵음 처리될 수 있음.
