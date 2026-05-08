// Frequency response / THD analysis for log sine sweeps (Farina method).

import { fft, nextPow2 } from './fft.js';
import { magnitudeOnGrid } from './sweep.js';

// Logarithmic frequency grid covering [fLo, fHi] with `pts` points.
export function logFreqGrid(fLo, fHi, pts) {
  const out = new Float64Array(pts);
  const r = Math.log(fHi / fLo) / (pts - 1);
  for (let i = 0; i < pts; i++) out[i] = fLo * Math.exp(r * i);
  return out;
}

// Smooth a magnitude trace (dB) with a 1/3-octave running mean.
export function thirdOctaveSmooth(freqs, magDb) {
  const out = new Float64Array(freqs.length);
  for (let i = 0; i < freqs.length; i++) {
    const fLo = freqs[i] / Math.pow(2, 1 / 6);
    const fHi = freqs[i] * Math.pow(2, 1 / 6);
    let s = 0, n = 0;
    for (let j = 0; j < freqs.length; j++) {
      if (freqs[j] >= fLo && freqs[j] <= fHi) { s += magDb[j]; n++; }
    }
    out[i] = n ? s / n : magDb[i];
  }
  return out;
}

// Flatness = standard deviation (dB) of the smoothed magnitude in [fLo, fHi].
// Smaller is flatter (better).
export function flatnessStd(freqs, magDb, fLo, fHi) {
  let mean = 0, n = 0;
  for (let i = 0; i < freqs.length; i++) {
    if (freqs[i] >= fLo && freqs[i] <= fHi) { mean += magDb[i]; n++; }
  }
  if (!n) return Infinity;
  mean /= n;
  let s = 0;
  for (let i = 0; i < freqs.length; i++) {
    if (freqs[i] >= fLo && freqs[i] <= fHi) {
      const d = magDb[i] - mean;
      s += d * d;
    }
  }
  return Math.sqrt(s / n);
}

// Mean magnitude in dB over the given band.
export function meanLevelDb(freqs, magDb, fLo, fHi) {
  let s = 0, n = 0;
  for (let i = 0; i < freqs.length; i++) {
    if (freqs[i] >= fLo && freqs[i] <= fHi) { s += magDb[i]; n++; }
  }
  return n ? s / n : -Infinity;
}

// Farina-style THD: locate the linear IR, window each harmonic IR, compute
// integrated energies across the band of interest, return THD percentage.
export function thdFarina(ir, sweepSamples, f1, f2, sampleRate, fLo, fHi) {
  // Sweep length in samples and Farina time-stretch L.
  const T = sweepSamples / sampleRate;
  const L = T / Math.log(f2 / f1);

  // Find peak (linear IR).
  let peakIdx = 0, peakAbs = 0;
  for (let i = 0; i < ir.length; i++) {
    const a = Math.abs(ir[i]);
    if (a > peakAbs) { peakAbs = a; peakIdx = i; }
  }

  const winMs = 20;
  const winLen = Math.max(64, nextPow2(Math.floor(winMs * sampleRate / 1000)));

  function windowAtOffset(offsetSamples) {
    const start = peakIdx - Math.floor(winLen / 2) - offsetSamples;
    const re = new Float64Array(winLen);
    const im = new Float64Array(winLen);
    for (let i = 0; i < winLen; i++) {
      const idx = start + i;
      if (idx >= 0 && idx < ir.length) {
        // Hann window
        const w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (winLen - 1));
        re[i] = ir[idx] * w;
      }
    }
    fft(re, im);
    // Integrate magnitude^2 over the requested band (mapped to harmonic's
    // analysis band — Farina says the N-th harmonic IR re-encodes input
    // frequency f as output 2pi f, so we integrate over [fLo, fHi]).
    const N = winLen;
    let e = 0;
    for (let k = 0; k < N / 2; k++) {
      const f = k * sampleRate / N;
      if (f >= fLo && f <= fHi) {
        e += re[k] * re[k] + im[k] * im[k];
      }
    }
    return e;
  }

  const e1 = windowAtOffset(0);
  let eHarm = 0;
  for (let n = 2; n <= 5; n++) {
    const off = Math.round(L * Math.log(n) * sampleRate);
    eHarm += windowAtOffset(off);
  }
  if (e1 <= 0) return Infinity;
  return Math.sqrt(eHarm / e1) * 100;
}

// Per-mic score for picking the reference (lower is better):
//   score = wFlat * flatness_std_dB + wThd * thd_pct
// (flatness ~0..15 dB, thd ~0..30 %, weighting makes them comparable).
export function micReferenceScore(flatStdDb, thdPct, wFlat = 1.0, wThd = 0.5) {
  return wFlat * flatStdDb + wThd * thdPct;
}

// Tuning objective for evaluating overall "goodness" of a calibrated state.
// Higher is better. Weights (3:5:2) per spec — flatness, loudness, THD.
//   - flatness: small std-dev is good → mapped to (10 - std), clamped to >=0
//   - loudness: mean level dB (mid-band) directly used (relative across runs)
//   - thd:      small THD% is good → mapped to (10 - thd), clamped to >=0
export function tuningObjective(flatStdDb, meanLevelDb, thdPct,
                                wFlat = 3, wLoud = 5, wThd = 2) {
  const flatScore = Math.max(0, 10 - flatStdDb);     // 0..10
  const thdScore = Math.max(0, 10 - thdPct);         // 0..10
  // meanLevelDb is negative; offset by 60 so values around -50..-10 dB
  // produce 0..40 — kept on a comparable order of magnitude as the others.
  const loudScore = Math.max(0, meanLevelDb + 60);
  return wFlat * flatScore + wLoud * loudScore + wThd * thdScore;
}
