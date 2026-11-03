const $ = id => document.getElementById(id);
const cfg = { get base(){ return (localStorage.base || '').replace(/\/$/, ''); }, get token(){ return localStorage.token || ''; } };
const show = id => { document.querySelectorAll('.screen').forEach(s => s.classList.toggle('hidden', s.id !== id)); const w=$('logoWrap'); w.classList.toggle('busy', id==='working'); if(id!=='home') w.classList.remove('glow'); };
const hdrs = () => cfg.token ? { 'X-Matamel-Token': cfg.token } : {};

if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js');

async function health(){
  const c = new AbortController(); const t = setTimeout(() => { c.timedOut = true; c.abort(); }, 8000);
  try { const r = await fetch(cfg.base + '/health', { headers: hdrs(), signal: c.signal });
    if (!r.ok) { const err = new Error('http'); err.status = r.status; throw err; }
    return await r.json();
  } catch (e) { if (e.name === 'AbortError') { e.timeout = true; } throw e; } finally { clearTimeout(t); }
}
async function refreshStatus(){
  const el = $('status');
  try { const h = await health();
    el.textContent = h.model_loaded ? 'מנוע מוכן' : 'המנוע נטען…';
    $('logoWrap').classList.toggle('glow', !!h.model_loaded);
    el.className = 'status ' + (h.model_loaded ? 'ok' : '');
    if (!h.model_loaded) setTimeout(refreshStatus, 4000);
  } catch { el.textContent = localStorage.base ? 'אין חיבור לשרת התמלול' : 'לא הוגדר שרת תמלול'; el.className = 'status err'; $('logoWrap').classList.remove('glow'); $('noConn').classList.remove('hidden'); return; }
  $('noConn').classList.add('hidden');
}

function errMsg(e){
  const st = e && e.status;
  if (e && e.name === 'AbortError') return e.timeout ? 'התמלול לקח יותר מדי זמן. נסו שוב, או שלחו הודעה קצרה יותר.' : 'התמלול בוטל.';
  if (st === 401 || st === 403) return 'השרת דחה את הגישה (טוקן שגוי או חסר). בדקו את הטוקן בהגדרות.';
  if (st === 413) return 'הקובץ גדול מדי לשרת. נסו הודעה קצרה יותר.';
  if (!navigator.onLine) return 'אין חיבור לאינטרנט. התחברו ונסו שוב.';
  if (!st) return 'לא ניתן להתחבר לשרת התמלול. בדקו את כתובת השרת בהגדרות ושהשרת פועל.';
  return 'התמלול נכשל (שגיאת שרת ' + st + '). נסו שוב בעוד רגע.';
}
const TIMEOUT_MS = 180000;
let ctrl = null, last = null;
async function transcribe(blob, name, shared=false){
  last = { blob, name, shared };
  show('working');
  $('workInfo').textContent = `${name} · ${(blob.size/1024).toFixed(0)}KB`;
  const fd = new FormData(); fd.append('audio', blob, name);
  ctrl = new AbortController(); const c = ctrl;
  const t = setTimeout(() => { c.timedOut = true; c.abort(); }, TIMEOUT_MS);
  let ok = false;
  try {
    const r = await fetch(cfg.base + '/transcribe', { method: 'POST', body: fd, headers: hdrs(), signal: c.signal });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) { const err = new Error('http'); err.status = r.status; throw err; }
    $('text').textContent = j.text || '(לא זוהה דיבור)';
    $('meta').textContent = `${j.duration_sec} שניות · ${j.language}`;
    ok = true;
  } catch (e) {
    if (e.name === 'AbortError') e.timeout = !!c.timedOut;
    $('text').textContent = errMsg(e); $('meta').textContent = '';
  } finally { clearTimeout(t); ctrl = null; }
  last.ok = ok;
  $('text').classList.toggle('error', !ok);
  $('btnCopy').classList.toggle('hidden', !ok);
  $('btnRetry').classList.toggle('hidden', ok);
  $('btnDone').textContent = ok ? 'סיום' : 'חזרה';
  show('result');
}

async function clearShared(){ const c = await caches.open('matamel-share'); await c.delete('/shared-audio'); }

async function handleShare(){
  const c = await caches.open('matamel-share');
  const r = await c.match('/shared-audio');
  history.replaceState(null, '', '/');
  if (!r) return false;
  const name = decodeURIComponent(r.headers.get('X-Filename') || 'voice.ogg');
  await transcribe(await r.blob(), name, true);
  return true;
}

$('pick').onchange = e => { const f = e.target.files[0]; if (f) { clearShared(); transcribe(f, f.name); } };
const dz = $('drop');
const isAudio = f => f && (/^audio\//.test(f.type) || /\.(ogg|opus|m4a|mp3|wav|aac|webm|flac)$/i.test(f.name));
['dragenter','dragover'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.add('over'); }));
['dragleave','dragend'].forEach(ev => dz.addEventListener(ev, e => { if (!dz.contains(e.relatedTarget)) dz.classList.remove('over'); }));
dz.addEventListener('drop', e => { e.preventDefault(); dz.classList.remove('over');
  const f = [...(e.dataTransfer.files || [])].find(isAudio);
  if (f) { clearShared(); transcribe(f, f.name); } else { $('status').textContent = 'יש לגרור קובץ שמע (ogg/opus/m4a/mp3/wav)'; $('status').className = 'status err'; } });
// prevent the browser from opening files dropped outside the zone
['dragover','drop'].forEach(ev => window.addEventListener(ev, e => { if (!dz.contains(e.target)) e.preventDefault(); }));
dz.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('pick').click(); } });
$('btnCopy').onclick = async () => {
  let msg = 'הועתק ✓';
  try { await navigator.clipboard.writeText($('text').textContent); }
  catch { const sel = getSelection(), rg = document.createRange(); rg.selectNodeContents($('text')); sel.removeAllRanges(); sel.addRange(rg); msg = 'לא ניתן להעתיק אוטומטית — הטקסט סומן, העתיקו ידנית'; }
  $('btnCopy').textContent = msg; setTimeout(() => $('btnCopy').textContent = 'העתק', 2500); };
$('btnRetry').onclick = () => { if (last) transcribe(last.blob, last.name, last.shared); };
$('btnCancel').onclick = () => { if (ctrl) ctrl.abort(); };
// shared audio is cleared only after a successful result is dismissed (or a new file replaces it)
$('btnDone').onclick = async () => { if (last && last.ok && last.shared) await clearShared(); $('pick').value = ''; show('home'); refreshStatus(); };
$('btnGoSettings').onclick = () => $('btnSettings').onclick();
$('btnSettings').onclick = () => { $('base').value = localStorage.base || ''; $('token').value = cfg.token; show('settings'); };
$('btnBack').onclick = () => { show('home'); refreshStatus(); };
$('btnSave').onclick = () => { localStorage.base = $('base').value.trim(); localStorage.token = $('token').value.trim(); $('testOut').textContent = 'נשמר'; };
$('btnTest').onclick = async () => { $('btnSave').onclick();
  try { const h = await health(); $('testOut').textContent = 'מחובר ✓ ' + (h.model_loaded ? 'המנוע מוכן' : 'המנוע עדיין נטען'); }
  catch (e) { $('testOut').textContent = 'החיבור נכשל. ' + errMsg(e); } };

(async () => {
  if (new URLSearchParams(location.search).has('share') && await handleShare()) return;
  refreshStatus();
})();
