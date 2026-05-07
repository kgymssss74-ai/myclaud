package com.myclaud.audioverify.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Audio Verify 사용 가이드") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Section("Manual 탭 — 빠른 검증")
                Body(
                    """
                    1. Source: TONE(빌트인 톤) 또는 FILE(외부 파일).
                    2. Stream type: 1개 이상 선택. 다중 선택 시 동시 재생(화음).
                       RING 440Hz / DEEP 523Hz / LL 659Hz / ULL 784Hz / OFL 880Hz / FAST 988Hz
                    3. Format: TONE 모드에서 선택. OFFLOAD가 포함되면 디바이스 미지원
                       포맷은 회색.
                    4. Route: 1개 이상 선택. 미연결 디바이스는 회색이며 선택 불가.
                    5. Play → 선택 조합(streamType × route)별 엔진이 동시 시작.
                       Status Card에 각 엔진의 underrun, perf/share/mmap, routed device를
                       라이브 표시.
                    6. 칩을 바꾸면 자동으로 정지 + Idle 복귀 → 다시 Play.
                    """.trimIndent()
                )
                Section("Matrix 탭 — 자동 검증")
                Body(
                    """
                    streamType × format × route(SPK/BT/USB) 전체를 자동 순회.
                    BT/USB 미연결 케이스는 자동 SKIP 후 사유 기록.
                    ULL EXCLUSIVE/MMAP 거부 시 RelaxPromptDialog로 사용자 결정 요청
                    (Accept relaxed / Mark FAIL / Retry).
                    OFFLOAD 토글: ON이면 디바이스 미지원 OFFLOAD 조합도 자동 SKIP.
                    기본 OFF — 매번 의식적으로 선택.
                    """.trimIndent()
                )
                Section("Reports 탭")
                Body(
                    """
                    상단 Capabilities 카드: 디바이스 모델/SoC, OFFLOAD 지원 포맷,
                    출력 디바이스 목록을 한눈에 확인.
                    Re-probe 버튼으로 BT/USB 핫플러그 후 정보 갱신.
                    실행 리포트는 HTML/JSON 모두 저장되며 Share 버튼으로 외부 전송.
                    """.trimIndent()
                )
                Section("권장 검증 절차")
                Body(
                    """
                    Tier 0 (3분, 주변기기 없음): Manual에서 모든 stream/format/route를
                      1회씩 Play 해서 들리는지 확인.
                    Tier 1 (10분): Matrix Start, BT/USB AUTO_SKIP 확인, ULL 응답.
                    Tier 2 (10분, BT+USB 연결): 다중 선택으로 동시 재생, Matrix 재실행.
                    Tier 3 (2분): adb pull 리포트, jq로 JSON 검증.
                    """.trimIndent()
                )
            }
        },
    )
}

@Composable
private fun Section(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}
