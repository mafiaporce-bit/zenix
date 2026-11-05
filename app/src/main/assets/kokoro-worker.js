// Runs Kokoro (ZeniX's free natural voice) in the background so the screen never freezes.
let tts = null;
let loading = null;

async function load() {
  if (tts) return tts;
  if (!loading) {
    loading = (async () => {
      const { KokoroTTS } = await import('https://cdn.jsdelivr.net/npm/kokoro-js/+esm');
      tts = await KokoroTTS.from_pretrained('onnx-community/Kokoro-82M-v1.0-ONNX', {
        dtype: 'q8',
        device: 'wasm',
        progress_callback: (p) => {
          if (p && p.status === 'progress' && p.file && /\.onnx/.test(p.file)) {
            self.postMessage({ type: 'progress', p: Math.round(p.progress || 0) });
          }
        },
      });
      return tts;
    })().catch((e) => { loading = null; throw e; });
  }
  return loading;
}

self.onmessage = async (e) => {
  const m = e.data || {};
  try {
    if (m.type === 'load') {
      await load();
      self.postMessage({ type: 'ready' });
    } else if (m.type === 'gen') {
      const engine = await load();
      const audio = await engine.generate(m.text, { voice: m.voice });
      self.postMessage({ type: 'audio', id: m.id, blob: audio.toBlob() });
    }
  } catch (err) {
    self.postMessage({ type: 'error', id: m.id, msg: String((err && err.message) || err) });
  }
};
