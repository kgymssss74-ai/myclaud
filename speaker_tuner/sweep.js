// Logarithmic sine sweep (Farina) generator + spectral deconvolution.

import { fft, ifft, nextPow2 } from './fft.js';

// Build a log sine sweep from f1 to f2 over `duration` seconds at the given
// sample rate. RMS level is set to `rmsDb` (dB FS, 0 = max). 50 ms raised-cosine
// fade is applied at both ends.
export function logSineSweep(f1, f2, duration, sampleRate, rmsDb = -12) {
  const N = Math.floor(duration * sampleRate);
  const out = new Float32Array(N);
  const w1 = 2 * Math.PI * f1;
  const ratio = f2 / f1;
  const T = duration;
  const K = (T * w1) / Math.log(ratio);
  const L = T / Math.log(ratio);
  for (let n = 0; n < N; n++) {
    const t = n / sampleRate;
    out[n] = Math.sin(K * (Math.exp(t / L) - 1));
  }
  const fade = Math.floor(0.05 * sampleRate);
  for (let i = 0; i < fade; i++) {
    const w = 0.5 - 0.5 * Math.cos(Math.PI * i / fade);
    out[i] *= w;
    out[N - 1 - i] *= w;
  }
  // Scale to target RMS (a unity sine has RMS = 1/sqrt(2) ~ -3 dB FS).
  const targetRms = Math.pow(10, rmsDb / 20);
  let s = 0;
  for (let i = 0; i < N; i++) s += out[i] * out[i];
  const curRms = Math.sqrt(s / N) || 1;
  const g = targetRms / curRms;
  for (let i = 0; i < N; i++) out[i] *= g;
  return { samples: out, f1, f2, duration, sampleRate, L };
}

// Frequency-domain deconvolution: H(f) = Y(f) / X(f) with Tikhonov regularization.
// Returns the impulse response (time domain) and complex spectrum.
export function deconvolve(recorded, sweep) {
  const M = recorded.length + sweep.length;
  const N = nextPow2(M);
  const yRe = new Float64Array(N), yIm = new Float64Array(N);
  const xRe = new Float64Array(N), xIm = new Float64Array(N);
  for (let i = 0; i < recorded.length; i++) yRe[i] = recorded[i];
  for (let i = 0; i < sweep.length; i++) xRe[i] = sweep[i];
  fft(yRe, yIm);
  fft(xRe, xIm);
  let maxMag2 = 0;
  for (let i = 0; i < N; i++) {
    const m2 = xRe[i] * xRe[i] + xIm[i] * xIm[i];
    if (m2 > maxMag2) maxMag2 = m2;
  }
  const eps = maxMag2 * 1e-5;
  const hRe = new Float64Array(N), hIm = new Float64Array(N);
  for (let i = 0; i < N; i++) {
    const denom = xRe[i] * xRe[i] + xIm[i] * xIm[i] + eps;
    hRe[i] = (yRe[i] * xRe[i] + yIm[i] * xIm[i]) / denom;
    hIm[i] = (yIm[i] * xRe[i] - yRe[i] * xIm[i]) / denom;
  }
  // Time-domain IR (copy spectrum because ifft is destructive).
  const irRe = Float64Array.from(hRe);
  const irIm = Float64Array.from(hIm);
  ifft(irRe, irIm);
  return { N, hRe, hIm, ir: irRe };
}

// Bin index for a given frequency.
export function freqBin(freq, N, sampleRate) {
  return Math.round(freq * N / sampleRate);
}

// Magnitude response (dB) over a logarithmic frequency grid.
export function magnitudeOnGrid(hRe, hIm, sampleRate, freqs) {
  const N = hRe.length;
  const out = new Float64Array(freqs.length);
  for (let i = 0; i < freqs.length; i++) {
    const k = freqBin(freqs[i], N, sampleRate);
    const m = Math.sqrt(hRe[k] * hRe[k] + hIm[k] * hIm[k]);
    out[i] = 20 * Math.log10(m + 1e-30);
  }
  return out;
}
