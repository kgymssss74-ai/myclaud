// Top-level orchestration for the Galaxy S25 speaker auto-tuner.
//
// Pipeline:
//   1) Enumerate microphone inputs.
//   2) Mic selection: sweep each channel at -18 dBrms, evaluate every mic by
//      300 Hz - 7 kHz flatness + THD, pick the best one as the reference.
//   3) Multi-level measurement on the reference mic at -30, -18, -12, -6 dBrms
//      to characterise THD vs drive level per MBDRC band.
//   4) Design EQ (1/3-oct peaking) + MBDRC (4-band) per channel.
//   5) Re-sweep with calibration applied and report flatness / loudness / THD.

import { logSineSweep, deconvolve, magnitudeOnGrid } from './sweep.js';
import {
  logFreqGrid, thirdOctaveSmooth, flatnessStd, meanLevelDb,
  thdFarina, micReferenceScore, tuningObjective,
} from './analyze.js';
import { designEq, designMbdrc, MBDRC_BANDS } from './calibrate.js';
import { listMicDevices, playAndRecord } from './pipeline.js';

const FLO = 300;
const FHI = 7000;
const SWEEP_DUR = 4.0;
const TEST_LEVELS_DB = [-30, -18, -12, -6];
const SR = 48000;

const state = {
  mics: [],
  refMicId: null,
  perChannel: { 0: null, 1: null }, // 0=L, 1=R
};

function $(sel) { return document.querySelector(sel); }
function log(msg) {
  const el = $('#log');
  el.textContent += msg + '\n';
  el.scrollTop = el.scrollHeight;
}
function setProgress(pct) { $('#progress').style.width = pct + '%'; }
function setStatus(s) { $('#status').textContent = s; }

// -------- Phase 1: pick best mic --------

async function chooseReferenceMic() {
  setStatus('마이크 비교 측정 (좌측 스피커, -18 dBrms)');
  const sweep = logSineSweep(FLO, FHI, SWEEP_DUR, SR, -18);
  const grid = logFreqGrid(FLO, FHI, 256);

  const scores = [];
  for (let i = 0; i < state.mics.length; i++) {
    const mic = state.mics[i];
    log(`  • mic[${i}] "${mic.label}" 측정 중…`);
    const rec = await playAndRecord(sweep.samples, 0, SR, mic.deviceId);
    const dec = deconvolve(rec.samples, sweep.samples);
    const mag = magnitudeOnGrid(dec.hRe, dec.hIm, rec.sampleRate, grid);
    const sm = thirdOctaveSmooth(grid, mag);
    const flat = flatnessStd(grid, sm, FLO, FHI);
    const thd = thdFarina(dec.ir, sweep.samples.length, FLO, FHI,
                          rec.sampleRate, FLO, FHI);
    const score = micReferenceScore(flat, thd);
    log(`     평탄도 σ=${flat.toFixed(2)} dB · THD=${thd.toFixed(2)} % · score=${score.toFixed(2)}`);
    scores.push({ mic, flat, thd, score });
  }
  scores.sort((a, b) => a.score - b.score);
  const best = scores[0];
  state.refMicId = best.mic.deviceId;
  log(`▶ 기준 마이크 선택: "${best.mic.label}" (score=${best.score.toFixed(2)})`);
  return best;
}

// -------- Phase 2: per-channel multi-level measurement --------

async function measureChannelLevels(channel) {
  const label = channel === 0 ? 'L' : 'R';
  setStatus(`${label} 스피커 다단계 측정`);
  const grid = logFreqGrid(FLO, FHI, 256);

  const results = [];
  for (const lvl of TEST_LEVELS_DB) {
    const sweep = logSineSweep(FLO, FHI, SWEEP_DUR, SR, lvl);
    log(`  • ${label} @ ${lvl} dBrms`);
    const rec = await playAndRecord(sweep.samples, channel, SR, state.refMicId);
    const dec = deconvolve(rec.samples, sweep.samples);
    const mag = magnitudeOnGrid(dec.hRe, dec.hIm, rec.sampleRate, grid);
    const sm = thirdOctaveSmooth(grid, mag);
    // Band-resolved THD for MBDRC design.
    const bandThd = {};
    for (const band of MBDRC_BANDS) {
      bandThd[band.id] = thdFarina(dec.ir, sweep.samples.length, FLO, FHI,
                                   rec.sampleRate, band.loHz, band.hiHz);
    }
    const overallThd = thdFarina(dec.ir, sweep.samples.length, FLO, FHI,
                                 rec.sampleRate, FLO, FHI);
    const flat = flatnessStd(grid, sm, FLO, FHI);
    const meanDb = meanLevelDb(grid, sm, FLO, FHI);
    log(`     σ=${flat.toFixed(2)} dB · mean=${meanDb.toFixed(1)} dB · THD=${overallThd.toFixed(2)} %`);
    results.push({ levelDb: lvl, grid, mag, smoothed: sm, flat, meanDb,
                   overallThd, bandThd });
  }
  return results;
}

// -------- Phase 3: design calibration --------

function designForChannel(measurements) {
  // Use the -18 dBrms sweep (mid drive, low distortion) for EQ shape.
  const ref = measurements.find(m => m.levelDb === -18) || measurements[1];
  const eq = designEq(ref.grid, ref.smoothed, FLO, FHI);

  // Build per-band {levelDb, thdPct} arrays for MBDRC design.
  const perBand = {};
  for (const band of MBDRC_BANDS) {
    perBand[band.id] = measurements.map(m => ({
      levelDb: m.levelDb, thdPct: m.bandThd[band.id],
    }));
  }
  const mbdrc = designMbdrc(perBand, 3.0, 4.0);
  return { eq, mbdrc };
}

// -------- Phase 4: verify --------

async function verifyChannel(channel, cal) {
  const label = channel === 0 ? 'L' : 'R';
  setStatus(`${label} 검증 측정 (보정 적용)`);
  const grid = logFreqGrid(FLO, FHI, 256);
  const sweep = logSineSweep(FLO, FHI, SWEEP_DUR, SR, -12);
  const rec = await playAndRecord(sweep.samples, channel, SR, state.refMicId, cal);
  const dec = deconvolve(rec.samples, sweep.samples);
  const mag = magnitudeOnGrid(dec.hRe, dec.hIm, rec.sampleRate, grid);
  const sm = thirdOctaveSmooth(grid, mag);
  const flat = flatnessStd(grid, sm, FLO, FHI);
  const meanDb = meanLevelDb(grid, sm, FLO, FHI);
  const thd = thdFarina(dec.ir, sweep.samples.length, FLO, FHI,
                        rec.sampleRate, FLO, FHI);
  const objective = tuningObjective(flat, meanDb, thd, 3, 5, 2);
  log(`  ▶ ${label} 보정 후: σ=${flat.toFixed(2)} dB · mean=${meanDb.toFixed(1)} dB`
    + ` · THD=${thd.toFixed(2)} % · 목표함수=${objective.toFixed(1)}`);
  return { flat, meanDb, thd, objective };
}

// -------- Result rendering --------

function renderCalibration(channelLabel, cal) {
  const root = document.createElement('div');
  root.className = 'cal-block';
  root.innerHTML = `
    <h3>${channelLabel} 채널 보정 파라미터</h3>
    <h4>EQ (1/3-oct peaking, 기준 ${cal.eq.referenceDb.toFixed(1)} dB)</h4>
    <table>
      <thead><tr><th>fc (Hz)</th><th>Q</th><th>Gain (dB)</th></tr></thead>
      <tbody>
        ${cal.eq.bands.map(b =>
          `<tr><td>${b.frequency}</td><td>${b.Q.toFixed(2)}</td><td>${b.gainDb.toFixed(2)}</td></tr>`
        ).join('')}
      </tbody>
    </table>
    <h4>MBDRC (4-band Linkwitz-Riley)</h4>
    <table>
      <thead><tr>
        <th>Band</th><th>Lo</th><th>Hi</th><th>Thr (dB)</th>
        <th>Ratio</th><th>Knee</th><th>Atk</th><th>Rel</th><th>Makeup</th>
      </tr></thead>
      <tbody>
        ${cal.mbdrc.map(b =>
          `<tr>
             <td>${b.band}</td><td>${b.loHz}</td><td>${b.hiHz}</td>
             <td>${b.thresholdDb.toFixed(1)}</td><td>${b.ratio.toFixed(1)}</td>
             <td>${b.kneeDb}</td><td>${(b.attackS*1000).toFixed(0)} ms</td>
             <td>${(b.releaseS*1000).toFixed(0)} ms</td>
             <td>${b.makeupDb.toFixed(1)} dB</td>
           </tr>`
        ).join('')}
      </tbody>
    </table>
  `;
  $('#results').appendChild(root);
}

// -------- Main --------

async function run() {
  $('#startBtn').disabled = true;
  $('#log').textContent = '';
  $('#results').innerHTML = '';
  setProgress(0);

  log('1) 마이크 디바이스 검색…');
  state.mics = await listMicDevices();
  if (state.mics.length === 0) { log('❌ 사용 가능한 마이크가 없습니다.'); return; }
  log(`   ${state.mics.length}개 마이크 발견`);
  state.mics.forEach((m, i) => log(`   - [${i}] ${m.label}`));
  setProgress(10);

  log('\n2) 기준 마이크 선택 (300 Hz – 7 kHz 평탄도 + THD)');
  await chooseReferenceMic();
  setProgress(25);

  for (const ch of [0, 1]) {
    const label = ch === 0 ? 'L' : 'R';
    log(`\n3-${label}) ${label} 채널 다단계 측정 (드라이브 -30 → -6 dBrms)`);
    const meas = await measureChannelLevels(ch);
    setProgress(25 + (ch === 0 ? 25 : 50));

    log(`\n4-${label}) ${label} 채널 EQ + MBDRC 설계`);
    const cal = designForChannel(meas);
    state.perChannel[ch] = cal;
    renderCalibration(label, cal);

    log(`\n5-${label}) ${label} 채널 검증 (보정 후 재측정)`);
    cal.verify = await verifyChannel(ch, cal);
    setProgress(50 + (ch === 0 ? 25 : 50));
  }

  // Total objective (3:5:2 weighting on each channel, averaged).
  const totalObj = (state.perChannel[0].verify.objective
                  + state.perChannel[1].verify.objective) / 2;
  log(`\n✔ 자동 튜닝 완료 — 평균 목표함수(평탄:음량:THD = 3:5:2) = ${totalObj.toFixed(1)}`);
  setStatus('완료');
  setProgress(100);
  $('#startBtn').disabled = false;
  $('#exportBtn').disabled = false;
}

function exportJson() {
  const payload = {
    referenceMicId: state.refMicId,
    referenceMicLabel: (state.mics.find(m => m.deviceId === state.refMicId) || {}).label,
    channels: {
      L: state.perChannel[0],
      R: state.perChannel[1],
    },
    weights: { flatness: 3, loudness: 5, thd: 2 },
    band: { fLo: FLO, fHi: FHI },
    testLevelsDb: TEST_LEVELS_DB,
    sweepDurationS: SWEEP_DUR,
    sampleRate: SR,
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 's25-speaker-calibration.json';
  a.click();
  URL.revokeObjectURL(url);
}

window.addEventListener('DOMContentLoaded', () => {
  $('#startBtn').addEventListener('click', () => {
    run().catch(err => {
      log('❌ ' + (err.stack || err.message || err));
      $('#startBtn').disabled = false;
    });
  });
  $('#exportBtn').addEventListener('click', exportJson);
});
