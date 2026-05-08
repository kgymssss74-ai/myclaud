// EQ + multi-band DRC parameter design from measurement results.

// 1/3-octave parametric EQ centres covering 300 Hz - 7 kHz (ISO).
export const EQ_CENTERS = [
  315, 400, 500, 630, 800, 1000, 1250, 1600,
  2000, 2500, 3150, 4000, 5000, 6300,
];

// 4-band crossover layout (Hz). Linkwitz-Riley 4th order at the boundaries.
export const MBDRC_BANDS = [
  { id: 'low',     loHz: 300,  hiHz: 500  },
  { id: 'lowMid',  loHz: 500,  hiHz: 1500 },
  { id: 'mid',     loHz: 1500, hiHz: 3500 },
  { id: 'high',    loHz: 3500, hiHz: 7000 },
];

// Compute a parametric peaking EQ that inverts the smoothed measured magnitude
// against its in-band mean. Gains capped at ±12 dB to avoid pathological boosts.
export function designEq(freqs, smoothedDb, fLo = 300, fHi = 7000, capDb = 12) {
  // In-band reference level = mean of smoothed magnitude in [fLo, fHi].
  let mean = 0, cnt = 0;
  for (let i = 0; i < freqs.length; i++) {
    if (freqs[i] >= fLo && freqs[i] <= fHi) { mean += smoothedDb[i]; cnt++; }
  }
  mean = cnt ? mean / cnt : 0;

  // For each EQ centre, sample the smoothed response and build a peaking band.
  const bands = [];
  for (const fc of EQ_CENTERS) {
    if (fc < fLo || fc > fHi) continue;
    // Nearest grid index.
    let nearest = 0, best = Infinity;
    for (let i = 0; i < freqs.length; i++) {
      const d = Math.abs(Math.log(freqs[i] / fc));
      if (d < best) { best = d; nearest = i; }
    }
    const measured = smoothedDb[nearest];
    let gain = mean - measured;          // boost dips, cut peaks
    if (gain > capDb) gain = capDb;
    if (gain < -capDb) gain = -capDb;
    bands.push({ frequency: fc, Q: 1.4142, gainDb: gain, type: 'peaking' });
  }
  return { referenceDb: mean, bands };
}

// Given THD-vs-level results per band (each: array of {levelDb, thdPct}),
// design an MBDRC. Per-band threshold = highest measured level whose THD
// stays under `thdLimitPct`. Ratio default 4:1. Make-up gain pushes the
// post-compressor mean back toward 0 dBFS for loudness.
export function designMbdrc(perBandLevelThd, thdLimitPct = 3.0, ratio = 4.0) {
  const out = [];
  for (const band of MBDRC_BANDS) {
    const samples = perBandLevelThd[band.id] || [];
    // Sort ascending by level.
    samples.sort((a, b) => a.levelDb - b.levelDb);
    let safeMaxDb = -30;
    for (const s of samples) {
      if (s.thdPct <= thdLimitPct) safeMaxDb = Math.max(safeMaxDb, s.levelDb);
    }
    // Threshold: 3 dB below the highest "clean" level so the compressor takes
    // over before THD escalates. Ratio compresses everything above that.
    const thresholdDb = Math.min(-3, safeMaxDb - 3);
    // Make-up gain: bring the band's typical (programme) level back up.
    // For ratio=4, signals above threshold are compressed by 3/4; we add back
    // an amount equal to roughly (0 - threshold) * (1 - 1/ratio) but clipped
    // to keep peaks under 0 dBFS.
    const makeup = Math.min(
      Math.max(0, (-thresholdDb) * (1 - 1 / ratio) - 1),
      12,
    );
    out.push({
      band: band.id,
      loHz: band.loHz,
      hiHz: band.hiHz,
      thresholdDb,
      ratio,
      kneeDb: 6,
      attackS: 0.005,
      releaseS: 0.05,
      makeupDb: makeup,
    });
  }
  return out;
}
