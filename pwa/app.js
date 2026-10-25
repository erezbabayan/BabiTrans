const $ = id => document.getElementById(id);
const cfg = { get base(){ return (localStorage.base || '').replace(/\/$/, ''); }, get token(){ return localStorage.token || ''; } };
const show = id => { document.querySelectorAll('.screen').forEach(s => s.classList.toggle('hidden', s.id !== id)); const w=$('logoWrap'); w.classList.toggle('busy', id==='working'); if(id!=='home') w.classList.remove('glow'); };
const hdrs = () => cfg.token ? { 'X-Matamel-Token': cfg.token } : {};

if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js');

async function health(){
  const r = await fetch(cfg.base + '/health', { headers: hdrs() });
  return r.json();
}
async function refreshStatus(){
  const el = $('status');
  try { const h = await health();
    el.textContent = h.model_loaded ? 'מנוע מוכן' : 'המנוע נטען…';
    $('logoWrap').classList.toggle('glow', !!h.model_loaded);
    el.className = 'status ' + (h.model_loaded ? 'ok' : '');
    if (!h.model_loaded) setTimeout(refreshStatus, 4000);
  } catch { el.textContent = 'אין חיבור'; el.className = 'status err'; $('logoWrap').classList.remove('glow'); }
}

async function transcribe(blob, name){
  show('working');
  $('workInfo').textContent = `${name} · ${(blob.size/1024).toFixed(0)}KB`;
  const fd = new FormData(); fd.append('audio', blob, name);
  try {
    const r = await fetch(cfg.base + '/transcribe', { method: 'POST', body: fd, headers: hdrs() });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.detail || r.status);
    $('text').textContent = j.text || '(לא זוהה דיבור)';
    $('meta').textContent = `${j.duration_sec} שניות · ${j.language}`;
  } catch (e) {
    $('text').textContent = 'שגיאה: ' + e.message; $('meta').textContent = '';
  }
  show('result');
}

async function clearShared(){ const c = await caches.open('matamel-share'); await c.delete('/shared-audio'); }

async function handleShare(){
  const c = await caches.open('matamel-share');
  const r = await c.match('/shared-audio');
  history.replaceState(null, '', '/');
  if (!r) return false;
  const name = decodeURIComponent(r.headers.get('X-Filename') || 'voice.ogg');
  await transcribe(await r.blob(), name);
  return true;
}

$('pick').onchange = e => { const f = e.target.files[0]; if (f) transcribe(f, f.name); };
const dz = $('drop');
const isAudio = f => f && (/^audio\//.test(f.type) || /\.(ogg|opus|m4a|mp3|wav|aac|webm|flac)$/i.test(f.name));
['dragenter','dragover'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.add('over'); }));
['dragleave','dragend'].forEach(ev => dz.addEventListener(ev, e => { if (!dz.contains(e.relatedTarget)) dz.classList.remove('over'); }));
dz.addEventListener('drop', e => { e.preventDefault(); dz.classList.remove('over');
  const f = [...(e.dataTransfer.files || [])].find(isAudio);
  if (f) transcribe(f, f.name); else { $('status').textContent = 'יש לגרור קובץ שמע (ogg/opus/m4a/mp3/wav)'; $('status').className = 'status err'; } });
// prevent the browser from opening files dropped outside the zone
['dragover','drop'].forEach(ev => window.addEventListener(ev, e => { if (!dz.contains(e.target)) e.preventDefault(); }));
dz.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('pick').click(); } });
$('btnCopy').onclick = async () => { await navigator.clipboard.writeText($('text').textContent); $('btnCopy').textContent = 'הועתק ✓'; setTimeout(() => $('btnCopy').textContent = 'העתק', 1500); };
$('btnDone').onclick = async () => { await clearShared(); $('pick').value = ''; show('home'); refreshStatus(); };
$('btnSettings').onclick = () => { $('base').value = localStorage.base || ''; $('token').value = cfg.token; show('settings'); };
$('btnBack').onclick = () => { show('home'); refreshStatus(); };
$('btnSave').onclick = () => { localStorage.base = $('base').value.trim(); localStorage.token = $('token').value.trim(); $('testOut').textContent = 'נשמר'; };
$('btnTest').onclick = async () => { $('btnSave').onclick();
  try { const h = await health(); $('testOut').textContent = 'מחובר ✓ ' + JSON.stringify(h); }
  catch (e) { $('testOut').textContent = 'נכשל: ' + e.message; } };

(async () => {
  if (new URLSearchParams(location.search).has('share') && await handleShare()) return;
  refreshStatus();
})();
