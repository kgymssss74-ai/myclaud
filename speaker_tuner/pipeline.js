// Web Audio graph builders for playback (raw or calibrated) and recording.

// Build the playback graph. `channel` is 0 (left) or 1 (right). When
// `cal` is provided ({ eq: bands, mbdrc: bands }), the chain is:
//   source -> EQ (cascaded peaking biquads)
//          -> 4-way LR4 crossover
//          -> per-band DynamicsCompressor + makeup gain
//          -> sum
//          -> channel splitter/merger (route to one speaker)
//          -> destination
function makePlaybackChain(ctx, source, channel, cal) {
  let node = source;
  if (cal && cal.eq) {
    for (const b of cal.eq.bands) {
      const f = ctx.createBiquadFilter();
      f.type = b.type;
      f.frequency.value = b.frequency;
      f.Q.value = b.Q;
      f.gain.value = b.gainDb;
      node.connect(f);
      node = f;
    }
  }

  let postBand = node;
  if (cal && cal.mbdrc) {
    const sum = ctx.createGain();
    sum.gain.value = 1.0;
    for (const band of cal.mbdrc) {
      // Linkwitz-Riley 4th order = two cascaded Butterworth 2nd order filters.
      const lp1 = ctx.createBiquadFilter(); lp1.type = 'lowpass';
      lp1.frequency.value = band.hiHz; lp1.Q.value = Math.SQRT1_2;
      const lp2 = ctx.createBiquadFilter(); lp2.type = 'lowpass';
      lp2.frequency.value = band.hiHz; lp2.Q.value = Math.SQRT1_2;
      const hp1 = ctx.createBiquadFilter(); hp1.type = 'highpass';
      hp1.frequency.value = band.loHz; hp1.Q.value = Math.SQRT1_2;
      const hp2 = ctx.createBiquadFilter(); hp2.type = 'highpass';
      hp2.frequency.value = band.loHz; hp2.Q.value = Math.SQRT1_2;

      const comp = ctx.createDynamicsCompressor();
      comp.threshold.value = band.thresholdDb;
      comp.ratio.value = band.ratio;
      comp.knee.value = band.kneeDb;
      comp.attack.value = band.attackS;
      comp.release.value = band.releaseS;

      const makeup = ctx.createGain();
      makeup.gain.value = Math.pow(10, band.makeupDb / 20);

      node.connect(hp1); hp1.connect(hp2);
      hp2.connect(lp1); lp1.connect(lp2);
      lp2.connect(comp); comp.connect(makeup);
      makeup.connect(sum);
    }
    postBand = sum;
  }

  // Route the (mono) processed signal to L or R only.
  const merger = ctx.createChannelMerger(2);
  // Mono-to-one-channel mapping requires source channelCount=1 connecting to a
  // specific input of the merger. With mono source, output 0 -> input `channel`.
  postBand.connect(merger, 0, channel);
  // Other channel stays silent (default).
  merger.connect(ctx.destination);
}

// Play a Float32Array (mono) on the chosen channel and return a promise that
// resolves when playback ends. `cal` optional: { eq, mbdrc } designed objects.
export async function playBuffer(samples, channel, sampleRate, cal = null) {
  const ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate });
  if (ctx.state === 'suspended') await ctx.resume();
  const buf = ctx.createBuffer(1, samples.length, sampleRate);
  buf.getChannelData(0).set(samples);
  const src = ctx.createBufferSource();
  src.buffer = buf;
  makePlaybackChain(ctx, src, channel, cal);
  await new Promise((resolve) => {
    src.onended = resolve;
    src.start();
  });
  await ctx.close();
}

// Enumerate available audio input devices. Returns [{deviceId, label}, ...].
// Labels are populated only after the user has granted microphone permission
// at least once.
export async function listMicDevices() {
  // Trigger permission prompt with a dummy stream so labels populate.
  try {
    const probe = await navigator.mediaDevices.getUserMedia({ audio: true });
    probe.getTracks().forEach(t => t.stop());
  } catch (_) { /* ignored - user denied or no devices */ }
  const devs = await navigator.mediaDevices.enumerateDevices();
  return devs
    .filter(d => d.kind === 'audioinput')
    .map((d, i) => ({ deviceId: d.deviceId, label: d.label || `mic-${i}` }));
}

// Record from a chosen mic for `durationS` seconds. Resolves to Float32Array
// (mono, downmixed) plus the actual sample rate used.
export async function recordMic(deviceId, durationS, preferredRate = 48000) {
  const constraints = {
    audio: {
      deviceId: deviceId ? { exact: deviceId } : undefined,
      echoCancellation: false,
      noiseSuppression: false,
      autoGainControl: false,
      channelCount: 1,
      sampleRate: preferredRate,
    },
  };
  const stream = await navigator.mediaDevices.getUserMedia(constraints);
  const ctx = new (window.AudioContext || window.webkitAudioContext)();
  const src = ctx.createMediaStreamSource(stream);

  const target = Math.ceil(durationS * ctx.sampleRate);
  const chunks = [];
  let captured = 0;

  const proc = ctx.createScriptProcessor(4096, 1, 1);
  src.connect(proc);
  // ScriptProcessor needs a destination connection to pump audio, but we don't
  // want to hear it - send to a muted gain.
  const mute = ctx.createGain(); mute.gain.value = 0;
  proc.connect(mute); mute.connect(ctx.destination);

  await new Promise((resolve) => {
    proc.onaudioprocess = (ev) => {
      if (captured >= target) return;
      const inp = ev.inputBuffer.getChannelData(0);
      const need = Math.min(inp.length, target - captured);
      chunks.push(inp.slice(0, need));
      captured += need;
      if (captured >= target) resolve();
    };
  });

  proc.disconnect(); src.disconnect();
  stream.getTracks().forEach(t => t.stop());
  const sr = ctx.sampleRate;
  await ctx.close();

  const out = new Float32Array(captured);
  let off = 0;
  for (const c of chunks) { out.set(c.subarray(0, Math.min(c.length, captured - off)), off); off += c.length; }
  return { samples: out, sampleRate: sr };
}

// Run sweep + capture together: start the recorder slightly before playback,
// then stop slightly after. Returns recorded mic samples.
export async function playAndRecord(sweep, channel, sampleRate, micId, cal = null,
                                    leadInS = 0.2, tailS = 0.3) {
  const totalS = leadInS + (sweep.length / sampleRate) + tailS;
  const recPromise = recordMic(micId, totalS, sampleRate);
  // Slight delay before starting playback so lead-in silence is captured.
  await new Promise(r => setTimeout(r, leadInS * 1000));
  await playBuffer(sweep, channel, sampleRate, cal);
  return await recPromise;
}
