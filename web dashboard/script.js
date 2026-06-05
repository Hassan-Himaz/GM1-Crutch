/* ============================================================
   STEPWISE — Clinical Dashboard
   Analysis algorithms ported from Advanced_Analysis-2.ipynb
   ============================================================
   SensorPacket BLE layout (46 bytes, little-endian):
     float ax ay az (g)  |  float gx gy gz (°/s)
     int16 mx my mz      |  float roll pitch yaw (°)
   No force sensor on hardware — force estimated from IMU.
   ============================================================ */

// ── Config ─────────────────────────────────────────────────────
const PROXY_BASE             = 'http://localhost:5050';
const CAMGENIUM_API_BASE     = 'https://apisoftdev.l2s2.com';
const DEFAULT_INSTRUMENT_ID  = '4779fbb9b035ce55';
const STORAGE_KEY            = 'stepwise_patients_v2';
const HISTORY_KEY            = 'stepwise_history_v2';

// ── App State ──────────────────────────────────────────────────
const S = {
  patients: [],
  currentFilter: 'all',
  selectedPatientId: null,
  lastPackets: [],
  lastAnalysis: null,
  lastAnalysisPatientId: null,
  history: {},
};

// ── CNN model (TF.js) ──────────────────────────────────────────
let gaitModel = null;

// ── Random Forest model ────────────────────────────────────────
let _rfTrees    = null;   // array of tree objects
let _rfNorm     = null;   // {feature: {mean, std}}

// Feature order must match training script extract_rf_features()
// [stance_duration, force_mean/std/max/min, pitch_..., yaw_...,
//  roll_..., az_..., mz_..., accel_mag_...]
const RF_ALIASES = ['force','pitch','yaw','roll','az','mz','accel_mag'];

async function loadRFModel() {
  try {
    const [mRes, nRes] = await Promise.all([
      fetch('./rf_model.json'),
      fetch('./model_norm_stats.json'),
    ]);
    if (!mRes.ok || !nRes.ok) return;
    _rfTrees = (await mRes.json()).trees;
    _rfNorm  = await nRes.json();
    const statusEl = document.getElementById('modelStatus');
    if (statusEl) { statusEl.textContent = 'RF: ready ✓'; statusEl.classList.add('loaded'); }
  } catch { /* files not present — stay on rule-based */ }
}

function _rfPredict(features) {
  // features: {channels, duration, packets}
  // channels order matches CH_NAMES in script:
  // ax(0) ay(1) az(2) gx(3) gy(4) gz(5) pitch(6) yaw(7) roll(8) accel_mag(9) gyro_mag(10) mz(11)
  const pk = features.packets || [];   // raw packets for force + mz
  const ch = features.channels;        // normalised 12-ch × 100-pt arrays

  // Map channel alias → raw time-series (100 pts, interpolated)
  const raw = {
    force:     pk.length ? features.packets.map(p =>
                 Math.max(0, p.load_kg != null && isFinite(p.load_kg)
                   ? p.load_kg * 9.81
                   : (Math.abs(p.az) - 0.98) * 9.81)) : Array.from(ch[2]),
    pitch:     Array.from(ch[6]),
    yaw:       Array.from(ch[7]),
    roll:      Array.from(ch[8]),
    az:        Array.from(ch[2]),
    mz:        Array.from(ch[11]),
    accel_mag: Array.from(ch[9]),
  };

  // Normalise each channel using training stats
  const norm = {};
  for (const alias of RF_ALIASES) {
    const { mean, std } = (_rfNorm[alias] || { mean: 0, std: 1 });
    norm[alias] = raw[alias].map(v => (v - mean) / (std + 1e-6));
  }

  // Build feature vector: [stance_duration, alias_mean/std/max/min ...]
  const vec = [features.duration];
  for (const alias of RF_ALIASES) {
    const a = norm[alias];
    const n = a.length || 1;
    const mu  = a.reduce((s,v) => s+v, 0) / n;
    const sq  = a.reduce((s,v) => s+(v-mu)**2, 0) / n;
    vec.push(mu);                          // mean
    vec.push(Math.sqrt(sq));               // std
    vec.push(Math.max(...a));              // max
    vec.push(Math.min(...a));              // min
  }

  // Walk each tree and vote
  function walkTree(node) {
    if ('leaf' in node) return node.leaf;
    return vec[node.f] <= node.t ? walkTree(node.l) : walkTree(node.r);
  }
  const votes = [0, 0, 0, 0];
  for (const tree of _rfTrees) votes[walkTree(tree)]++;
  return votes.indexOf(Math.max(...votes));
}

// ============================================================
// INIT
// ============================================================

window.addEventListener('DOMContentLoaded', () => {
  document.getElementById('todayDate').textContent =
    new Date().toLocaleDateString('en-GB', {
      weekday: 'short', day: 'numeric', month: 'short', year: 'numeric',
    });
  loadPatients();
  showTab('dashboard');
  autoLoadCNNModel();
  loadRFModel();
});

async function autoLoadCNNModel() {
  const statusEl = document.getElementById('modelStatus');
  try {
    const model = await tf.loadLayersModel('./tfjs_model/model.json');
    gaitModel = model;
    statusEl.textContent = 'CNN: ready ✓';
    statusEl.classList.add('loaded');
  } catch {
    // No model folder present — stay on rule-based fallback silently
  }
}

// ============================================================
// PATIENT STORAGE
// ============================================================

async function loadPatients() {
  try { S.history = JSON.parse(localStorage.getItem(HISTORY_KEY) || '{}'); } catch { S.history = {}; }

  try {
    const res = await fetch('patients.json');
    if (res.ok) {
      S.patients = (await res.json()).map(normalisePatient);
      savePatients();
      onPatientsReady();
      return;
    }
  } catch {}
  const stored = localStorage.getItem(STORAGE_KEY);
  if (stored) { try { S.patients = JSON.parse(stored); } catch { S.patients = []; } }
  onPatientsReady();
}

function savePatients() { localStorage.setItem(STORAGE_KEY, JSON.stringify(S.patients)); }
function saveHistory()  { localStorage.setItem(HISTORY_KEY,  JSON.stringify(S.history));  }

function recordDailyEntry(patient, analysis) {
  const date = new Date().toISOString().split('T')[0];
  if (!S.history[patient.id]) S.history[patient.id] = {};
  S.history[patient.id][date] = {
    cusi:                   analysis.cusi,
    gait_distribution:      analysis.gaitDistribution,
    step_count:             analysis.stepCount,
    crutch_expired:         patient.crutchExpired,
    avg_weight_bearing_pct: analysis.avgWBPct,
    adherence_pct:          analysis.overallAdherence,
    packet_count:           analysis.packetCount,
  };
  saveHistory();
}

function onPatientsReady() {
  S.selectedPatientId = S.patients[0]?.id ?? null;
  populateSelectors();
  renderDashboard();
  buildSummaryPills();
  renderEditPatientList();
  buildPatientAppData();
}

function normalisePatient(p) {
  const id = p.id || (p.initials || 'P') + '_' + (p.name || '').replace(/\s+/g, '').toLowerCase();
  return {
    id,
    initials:   (p.initials || id.slice(0, 2)).toUpperCase(),
    name:       p.name    || 'Unnamed',
    detail:     p.detail  || 'Active patient',
    gender:     p.gender  || 'Other',
    status:     p.status  || 'review',
    statusText: p.statusText || 'Review needed',
    load: Number(p.load ?? 0), limit: p.limit || '—',
    gait: p.gait || '—', steps: p.steps || '—',
    adherence: p.adherence || '—', cusi: Number(p.cusi ?? 0),
    action: p.action || 'Run analysis after first session',
    crutch: p.crutch || '✓', crutchExpired: Boolean(p.crutchExpired ?? false),
    age: Number(p.age ?? 65), bodyWeightKg: Number(p.bodyWeightKg ?? 75),
    prescribedGait: p.prescribedGait || '3-Point',
    prescribedWeightBearingPercent: Number(p.prescribedWeightBearingPercent ?? 30),
    dailyStepTarget: Number(p.dailyStepTarget ?? 5000),
    cusiThreshold: Number(p.cusiThreshold ?? 15),
    instrumentId: p.instrumentId || DEFAULT_INSTRUMENT_ID,
    rehabDurationDays: Number(p.rehabDurationDays ?? 25),
    startDate: p.startDate || new Date().toISOString().split('T')[0],
  };
}

function getPatient(id) { return S.patients.find(p => p.id === id); }
function getSelectedPatient(selId) {
  const id = document.getElementById(selId)?.value || S.selectedPatientId;
  return getPatient(id) || S.patients[0] || null;
}

// ============================================================
// SELECTORS / TABS / FILTERS
// ============================================================

function populateSelectors() {
  const opts = S.patients.length === 0
    ? '<option value="">No patients — register one first</option>'
    : S.patients.map(p => `<option value="${p.id}">${p.name} (${p.initials})</option>`).join('');
  ['analyticsPatientSelect', 'appPatientSelect'].forEach(id => {
    const el = document.getElementById(id);
    if (!el) return;
    el.innerHTML = opts;
    if (S.selectedPatientId) el.value = S.selectedPatientId;
  });
  const instr = document.getElementById('instrumentIdInput');
  if (instr && S.selectedPatientId) {
    instr.value = getPatient(S.selectedPatientId)?.instrumentId || DEFAULT_INSTRUMENT_ID;
  }
}

function onAnalyticsPatientChange() {
  S.selectedPatientId = document.getElementById('analyticsPatientSelect').value;
  const instr = document.getElementById('instrumentIdInput');
  if (instr) instr.value = getPatient(S.selectedPatientId)?.instrumentId || DEFAULT_INSTRUMENT_ID;
}

function showTab(name) {
  document.querySelectorAll('.tab-section').forEach(s => s.classList.remove('active-section'));
  document.querySelectorAll('.tab').forEach(b => b.classList.remove('active'));
  document.getElementById(name + 'Tab')?.classList.add('active-section');
  Array.from(document.querySelectorAll('.tab'))
    .find(b => b.getAttribute('onclick')?.includes(`'${name}'`))?.classList.add('active');
  if (name === 'patientapp') buildPatientAppData();
}

function setFilter(type, btn) {
  S.currentFilter = type;
  document.querySelectorAll('.filter').forEach(b => b.classList.remove('active'));
  btn?.classList.add('active');
  renderDashboard();
}

// ============================================================
// DASHBOARD
// ============================================================

function renderDashboard() {
  buildSummaryPills();
  const container = document.getElementById('patientRows');
  if (!container) return;
  const search = (document.getElementById('searchInput')?.value || '').toLowerCase();
  const list = S.patients.filter(p => {
    const m = p.name.toLowerCase().includes(search) || p.detail.toLowerCase().includes(search);
    if (!m) return false;
    if (S.currentFilter === 'all')    return true;
    if (S.currentFilter === 'overdue') return p.crutchExpired;
    return p.status === S.currentFilter;
  });

  if (!list.length) {
    container.innerHTML = `<div class="empty-message">${
      S.patients.length === 0
        ? 'No patients registered yet. Go to <b>Register Patient</b> to add the first patient.'
        : 'No patients match this filter.'
    }</div>`;
    return;
  }

  container.innerHTML = '';
  list.forEach(p => {
    const row = document.createElement('div');
    row.className = `row ${p.status}`;
    const col  = p.status === 'ontrack' ? 'green' : p.status === 'review' ? 'amber' : 'red';
    const an   = parseInt(p.adherence);
    const ac   = isNaN(an) ? 'empty' : an >= 80 ? 'good' : an >= 60 ? 'warn' : 'bad';
    const actc = p.status === 'review' ? 'warning' : p.status === 'flagged' ? 'danger' : '';
    const cc   = p.crutchExpired ? 'overdue' : '';
    const cc2  = p.cusi > 0 ? (p.cusi >= p.cusiThreshold ? 'bad' : 'good') : 'empty';
    row.innerHTML = `
      <div class="patient">
        <div class="avatar ${col}">${p.initials}</div>
        <div><div class="name">${p.name}</div><div class="detail">${p.detail}</div></div>
      </div>
      <div><span class="badge ${col}">${p.statusText}</span></div>
      <div>
        <div class="progress"><div class="progress-fill ${col}" style="width:${Math.min(p.load,100)}%"></div></div>
        <div class="detail">${p.load > 0 ? p.load + '% of target' : 'No data yet'}</div>
      </div>
      <div><span class="gait">${p.gait}</span></div>
      <div class="steps">${p.steps}</div>
      <div>
        <div class="adherence ${cc2}">${p.cusi > 0 ? p.cusi : '—'}</div>
        <div class="detail">/ ${p.cusiThreshold}</div>
      </div>
      <div>
        <div class="adherence ${ac}">${p.adherence}</div>
        <div class="detail">overall</div>
      </div>
      <button class="action ${actc}" onclick="openPatient('${p.id}')">${p.action}</button>
      <button class="tick ${cc}"    onclick="markReturned('${p.id}')">${p.crutchExpired ? '×' : '✓'}</button>`;
    container.appendChild(row);
  });
}

function buildSummaryPills() {
  const c = {
    ontrack: S.patients.filter(p => p.status === 'ontrack').length,
    review:  S.patients.filter(p => p.status === 'review').length,
    flagged: S.patients.filter(p => p.status === 'flagged').length,
    overdue: S.patients.filter(p => p.crutchExpired).length,
  };
  const el = document.getElementById('summaryPills');
  if (!el) return;
  el.innerHTML = `
    <button class="pill green"  onclick="setFilter('ontrack',null)">${c.ontrack} On track</button>
    <button class="pill amber"  onclick="setFilter('review', null)">${c.review} Need review</button>
    <button class="pill red"    onclick="setFilter('flagged',null)">${c.flagged} Flagged</button>
    <button class="pill danger" onclick="setFilter('overdue',null)">${c.overdue} Crutch overdue</button>`;
}

function openPatient(id) { S.selectedPatientId = id; populateSelectors(); showTab('analytics'); }

function markReturned(id) {
  const p = getPatient(id);
  if (!p) return;
  p.crutchExpired = false; p.crutch = '✓';
  if (p.status === 'flagged') { p.status = 'review'; p.statusText = 'Review needed'; p.action = 'Crutch returned — review rehab progress'; }
  savePatients(); renderDashboard();
}

// ============================================================
// CAMGENIUM API  (via local proxy.py)
// ============================================================

let _lastCursor = null;

async function fetchAllPackets(instrumentId, signal) {
  // Probe proxy
  try {
    const probe = await fetch(`${PROXY_BASE}/api/auth`, { signal: AbortSignal.timeout(3000) });
    if (!probe.ok) throw new Error('proxy not OK');
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    throw new Error(
      'Local proxy not running.\n' +
      'Open a terminal and run:\n  cd <project folder>\n  python3 proxy.py\nThen click Analyse again.'
    );
  }

  // First poll: fetch latest 500 packets. Subsequent polls: only new packets since last cursor.
  const url = new URL(`${PROXY_BASE}/api/v1/harvester/instruments/${instrumentId}/data`);
  url.searchParams.set('limit', '500');
  if (_lastCursor !== null) url.searchParams.set('since', String(_lastCursor));

  const res  = await fetch(url.toString(), { signal });
  if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.error || `HTTP ${res.status}`); }
  const body = await res.json();
  if (body.error) throw new Error(`API error: ${body.error}${body.details ? ' — ' + body.details : ''}`);

  const items = Array.isArray(body) ? body : (body.data || []);
  if (body.nextCursor) _lastCursor = body.nextCursor;
  return items;
}

// ============================================================
// BINARY PACKET DECODING
// SensorPacket layout (46 bytes, LE):
//   float ax@0  ay@4  az@8  gx@12 gy@16 gz@20
//   int16 mx@24 my@26 mz@28
//   float roll@30 pitch@34 yaw@38
//   float load_kg@42  (direct load cell reading)
// ============================================================

function decodeBinaryPacket(b64, meta) {
  try {
    const bin = atob(b64);
    const buf = new ArrayBuffer(bin.length);
    const u8  = new Uint8Array(buf);
    for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    if (buf.byteLength >= 42) {
      const v = new DataView(buf), L = true;
      return {
        timestamp: meta?.timestamp || new Date().toISOString(),
        sequenceNumber: meta?.deviceDataId || 0,
        ax: v.getFloat32(0,L),  ay: v.getFloat32(4,L),  az: v.getFloat32(8,L),
        gx: v.getFloat32(12,L), gy: v.getFloat32(16,L), gz: v.getFloat32(20,L),
        mx: v.getInt16(24,L),   my: v.getInt16(26,L),   mz: v.getInt16(28,L),
        roll:    v.getFloat32(30,L),
        pitch:   v.getFloat32(34,L),
        yaw:     v.getFloat32(38,L),
        load_kg: buf.byteLength >= 46 ? v.getFloat32(42,L) : null,
      };
    }
  } catch {}
  try { return { timestamp: meta?.timestamp, ...JSON.parse(atob(b64)) }; } catch {}
  return null;
}

function decodeApiItems(items) {
  return items.flatMap(item => {
    if (item.dataValue) { const p = decodeBinaryPacket(item.dataValue, item); return p ? [p] : []; }
    if (item.ax !== undefined) return [item];
    return [];
  });
}

// ============================================================
// ANALYSIS ALGORITHMS  (from Advanced_Analysis-2.ipynb)
// ============================================================

// ── R_max grip-strength normalisation table ───────────────────
const R_MAX_TABLE = {
  Male:   { '18-24': 0.4897, '25-34': 0.4597, '35-44': 0.4141, '45-54': 0.3871, '55-64': 0.3732 },
  Female: { '18-24': 0.3870, '25-34': 0.4042, '35-44': 0.3722, '45-54': 0.3367, '55-64': 0.2904 },
};
function getRmax(age, gender) {
  const cat = age < 25 ? '18-24' : age < 35 ? '25-34' : age < 45 ? '35-44' : age < 55 ? '45-54' : '55-64';
  if (R_MAX_TABLE[gender]) return R_MAX_TABLE[gender][cat];
  return (R_MAX_TABLE.Male[cat] + R_MAX_TABLE.Female[cat]) / 2;
}

// ── Force from load cell (or IMU fallback) ────────────────────
// Uses direct load_kg reading when available (byte 42 of packet).
// Falls back to IMU estimate if packet is old 42-byte format.
function estimateForceN(pkt, bwKg) {
  if (pkt.load_kg != null && isFinite(pkt.load_kg)) {
    return Math.max(0, pkt.load_kg * 9.81);
  }
  const pitchRad = ((pkt.pitch || 0) * Math.PI) / 180;
  return Math.max(0, (Math.abs(pkt.az) * Math.cos(pitchRad) - 0.98) * bwKg * 9.81);
}

// ── Rolling mean (notebook: smoothing_window=5) ───────────────
function rollingMean(arr, w) {
  return arr.map((_, i) => {
    const s = Math.max(0, i - Math.floor(w / 2));
    const e = Math.min(arr.length, s + w);
    const sl = arr.slice(s, e);
    return sl.reduce((a, v) => a + v, 0) / sl.length;
  });
}

// ── Stance detection ──────────────────────────────────────────
// Notebook: threshold = 5% BW on force.
// IMU proxy: smoothed acc_mag > 1.12 g indicates crutch loaded.
function detectStance(packets) {
  const mags    = packets.map(p => Math.sqrt(p.ax ** 2 + p.ay ** 2 + p.az ** 2));
  const smooth  = rollingMean(mags, 5);
  const threshold = 1.12;
  return smooth.map(v => v > threshold);
}

// ── Step counting ─────────────────────────────────────────────
// Each stance-phase rising edge = one step
function countSteps(packets) {
  const stance = detectStance(packets);
  let steps = 0;
  for (let i = 1; i < stance.length; i++) if (stance[i] && !stance[i - 1]) steps++;
  return steps;
}

// ── Stride segmentation ───────────────────────────────────────
// Extract stance-phase windows (5–150 samples = 0.17–5 s at 30 Hz)
function segmentStrides(packets) {
  const stance = detectStance(packets);
  const strides = [];
  let inStance = false, start = 0;
  for (let i = 0; i < stance.length; i++) {
    if (stance[i] && !inStance)  { start = i; inStance = true; }
    if (!stance[i] && inStance) {
      const len = i - start;
      if (len >= 5 && len <= 150)
        strides.push({ packets: packets.slice(start, i), duration: len / 30 });
      inStance = false;
    }
  }
  return strides;
}

// ── Feature extraction for CNN ────────────────────────────────
// 12 channels × N=100 points + duration scalar
// Matches notebook's prepare_normalized_strides (12-feature variant)
const CH_NAMES = ['ax','ay','az','gx','gy','gz','pitch','yaw','roll','accel_mag','gyro_mag','mz'];
const N_POINTS = 100;

function interpolateToN(arr, N) {
  if (!arr.length) return new Float32Array(N).fill(0);
  if (arr.length === 1) return new Float32Array(N).fill(arr[0]);
  const out = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const pos = (i / (N - 1)) * (arr.length - 1);
    const lo  = Math.floor(pos), hi = Math.min(lo + 1, arr.length - 1);
    out[i] = arr[lo] * (1 - (pos - lo)) + arr[hi] * (pos - lo);
  }
  return out;
}

function extractStrideFeatures(stride) {
  const pk = stride.packets;
  const raw = {
    ax:        pk.map(p => p.ax || 0),
    ay:        pk.map(p => p.ay || 0),
    az:        pk.map(p => p.az || 0),
    gx:        pk.map(p => p.gx || 0),
    gy:        pk.map(p => p.gy || 0),
    gz:        pk.map(p => p.gz || 0),
    pitch:     pk.map(p => p.pitch || 0),
    yaw:       pk.map(p => p.yaw  || 0),
    roll:      pk.map(p => p.roll || 0),
    accel_mag: pk.map(p => Math.sqrt(p.ax**2 + p.ay**2 + p.az**2)),
    gyro_mag:  pk.map(p => Math.sqrt((p.gx||0)**2 + (p.gy||0)**2 + (p.gz||0)**2)),
    mz:        pk.map(p => p.mz || 0),
  };
  return { channels: CH_NAMES.map(f => interpolateToN(raw[f], N_POINTS)), duration: stride.duration, packets: pk };
}

// ── CNN gait model (TF.js) ────────────────────────────────────
// Architecture = StrideCNN from notebook
// Labels: 0=swing, 1=2-Point, 2=3-Point, 3=4-Point
async function loadCNNModel(event) {
  const file = event.target.files[0];
  if (!file) return;
  const statusEl = document.getElementById('modelStatus');
  statusEl.textContent = 'CNN: loading…';
  try {
    gaitModel = await tf.loadLayersModel(tf.io.browserFiles([file]));
    statusEl.textContent = 'CNN: model loaded ✓';
    statusEl.style.color = '#059669';
  } catch (err) {
    statusEl.textContent = 'CNN: load failed — using fallback';
    statusEl.style.color = '#dc2626';
    console.error(err);
  }
}

function classifyStrideRuleBased(features) {
  // Rule-based fallback using same features as CNN
  const gyroMag = features.channels[10]; // gyro_mag channel
  const az      = features.channels[2];  // az channel
  const meanGyro = gyroMag.reduce((s, v) => s + v, 0) / gyroMag.length;
  const meanAz   = az.reduce((s, v) => s + v, 0) / az.length;
  const dur = features.duration;
  if (dur < 0.25 || meanAz < 1.05) return 0; // swing / no contact
  if (meanGyro > 80)               return 1; // 2-Point (fast)
  if (meanGyro > 32)               return 2; // 3-Point (swing-through)
  return 3;                                   // 4-Point (slow alternating)
}

async function classifyStride(features) {
  if (_rfTrees) return _rfPredict(features);
  return classifyStrideRuleBased(features);
}

async function classifyAllStrides(strides) {
  const counts = [0, 0, 0, 0];
  const labels = [];
  for (const stride of strides) {
    const feat  = extractStrideFeatures(stride);
    const label = await classifyStride(feat);
    counts[label]++;
    labels.push(label);
  }
  const total = strides.length || 1;
  const nonSwing = counts.slice(1);
  const maxIdx   = nonSwing.indexOf(Math.max(...nonSwing));
  return {
    labels,
    counts,
    distribution: {
      'Swing':   Math.round((counts[0] / total) * 100),
      '2-Point': Math.round((counts[1] / total) * 100),
      '3-Point': Math.round((counts[2] / total) * 100),
      '4-Point': Math.round((counts[3] / total) * 100),
    },
    detectedGait: ['2-Point', '3-Point', '4-Point'][maxIdx],
  };
}

// ── Weight bearing ────────────────────────────────────────────
// From notebook injured_load():
//   swing (0):   L = 0
//   2-point (1): L = BW_N − peak_force
//   3/4-pt (2,3):L = BW_N − 2 × peak_force  (bilateral crutches)
function calcWeightBearing(strides, strideLabels, patient) {
  const BW_N = patient.bodyWeightKg * 9.81;
  const loads = [];
  strides.forEach((stride, i) => {
    const label = strideLabels[i];
    if (label === 0) { loads.push(0); return; }
    const forces   = stride.packets.map(p => estimateForceN(p, patient.bodyWeightKg));
    const smoothed = rollingMean(forces, 5);
    const peak     = Math.max(...smoothed);
    const L = label === 1
      ? Math.max(0, BW_N - peak)
      : Math.max(0, BW_N - 2 * peak);
    loads.push(L);
  });
  const meanLoad   = loads.length ? loads.reduce((s, v) => s + v, 0) / loads.length : 0;
  const avgWBPct   = Math.max(0, Math.round((meanLoad / BW_N) * 100));
  const target     = patient.prescribedWeightBearingPercent;
  const wbAdherence = target === 0
    ? (avgWBPct < 5 ? 95 : Math.max(0, 95 - avgWBPct * 6))
    : Math.max(0, Math.round(100 - Math.abs(avgWBPct - target)));
  return { avgWBPct, wbAdherence };
}

// ── CUSI — RSI formula (exact from notebook) ──────────────────
function calcIM(I) {
  if (I <= 0)   return 0;
  if (I <= 0.4) return 30*I**3 - 15.6*I**2 + 13*I + 0.4;
  return Math.min(30, 36*I**3 - 33.3*I**2 + 24.77*I - 1.86);
}
function calcEM(E) {
  if (E <= 0)  return 0;
  if (E <= 90) return 0.10 + 0.25 * E;
  return 0.00334 * E**1.96;
}
function calcDM(D) {
  if (D <= 0)  return 0;
  if (D <= 60) return 0.45 + 0.31 * D;
  return 19.17 * Math.log(D) - 59.44;
}
function calcPM(P) {
  if (P < 0)   return 1.2 * Math.exp(0.009 * Math.abs(P)) - 0.2;
  if (P <= 30) return 1.0;
  return 1.0 + 0.00028 * (P - 30)**2;
}
function calcHM(H) {
  if (H <= 0)    return 0;
  if (H <= 0.05) return 0.20;
  return 0.042 * H + 0.090 * Math.log(H) + 0.477;
}
function calcRSI(I, E, D, H, P = 47) {
  return calcIM(I) * calcEM(E) * calcDM(D) * calcPM(P) * calcHM(H);
}

function calcCUSI(strides, strideLabels, patient) {
  if (!strides.length) return 1;
  // CUSI formula assumes a full walking session — unreliable on short clips.
  // Require at least 60 s of total stride time before computing.
  const totalStrideSecs = strides.reduce((s, st) => s + st.duration, 0);
  if (totalStrideSecs < 60) return 1;
  const BW_N   = patient.bodyWeightKg * 9.81;
  const R_max  = getRmax(patient.age, patient.gender || 'Other');
  const P_DEG  = 47; // fixed crutch wrist posture angle (from notebook)

  // Group non-swing strides by gait label
  const byGait = {};
  strides.forEach((stride, i) => {
    const lbl = strideLabels[i];
    if (lbl === 0) return;
    if (!byGait[lbl]) byGait[lbl] = [];
    byGait[lbl].push(stride);
  });

  const sessionRSIs = Object.values(byGait).map(gaitStrides => {
    const h  = gaitStrides.reduce((s, st) => s + st.duration, 0) / 3600;
    const allF = gaitStrides.flatMap(st => st.packets.map(p => estimateForceN(p, patient.bodyWeightKg)));
    const avgF  = allF.length ? allF.reduce((s, v) => s + v, 0) / allF.length : 0;
    const I = Math.min(1.0, Math.max(0.001, avgF / (BW_N * R_max)));
    const E = h > 0 ? gaitStrides.length / (h * 60) : 1;
    const D = h > 0 ? (gaitStrides.reduce((s, st) => s + st.duration, 0) / gaitStrides.length) : 0.5;
    return { rsi: calcRSI(I, E, D, h, P_DEG), h };
  });

  if (!sessionRSIs.length) return 1;

  // Notebook: sort by RSI desc, then integrate c_i × d_i
  sessionRSIs.sort((a, b) => b.rsi - a.rsi);
  let cusi = 0, cumH = 0;
  for (const sess of sessionRSIs) {
    const HM_i = calcHM(sess.h);
    const c_i  = HM_i > 0 ? sess.rsi / HM_i : 0;
    const prev = cumH; cumH += sess.h;
    cusi += c_i * (calcHM(cumH) - calcHM(prev));
  }
  // Scale raw CUSI to 1–20 clinical display range
  // RSI: <1 = low, 1-3 = moderate, >3 = high risk
  return Math.max(1, Math.min(20, Math.round(1 + (cusi / 0.4) * 19)));
}

// ── Overall adherence ─────────────────────────────────────────
function calcAdherence(stepAdherence, wbAdherence, gaitMatchPct, cusiOk) {
  return Math.round(
    0.30 * stepAdherence +
    0.30 * wbAdherence   +
    0.25 * gaitMatchPct  +
    0.15 * (cusiOk ? 100 : 45)
  );
}

// ── Clinician action ──────────────────────────────────────────
function buildAction({ stepAdherence, avgWBPct, gaitMatchPct, cusi, patient }) {
  const t = patient.prescribedWeightBearingPercent;
  if (cusi >= patient.cusiThreshold)
    return `High wrist strain (CUSI ${cusi}/${patient.cusiThreshold}) — check crutch height and grip`;
  if (t > 0 && avgWBPct > t * 1.15)
    return `Excess loading ${avgWBPct}% BW vs ${t}% prescribed — reinforce WB limit`;
  if (t > 0 && avgWBPct < t * 0.60)
    return `Under-loading ${avgWBPct}% BW vs ${t}% prescribed — encourage prescribed WB`;
  if (gaitMatchPct < 75)
    return `Gait mismatch — reinforce prescribed ${patient.prescribedGait} pattern`;
  if (stepAdherence < 60)
    return 'Low step count — assess pain, confidence and mobility barriers';
  return 'Mixed adherence — review with patient at next appointment';
}

// ── Master analysePackets ─────────────────────────────────────
async function analysePackets(packets, patient) {
  if (!packets.length) return nullAnalysis();

  const clean = packets.filter(p => isFinite(p.ax) && isFinite(p.ay) && isFinite(p.az));
  if (!clean.length) return nullAnalysis();

  setStatus(`Processing ${clean.length} packets — segmenting strides…`);
  const strides = segmentStrides(clean);

  setStatus(`${strides.length} strides found — running RF gait classification…`);
  const gaitResult = await classifyAllStrides(strides);

  const stepCount    = countSteps(clean);
  const stepAdherence = Math.min(100, Math.round((stepCount / patient.dailyStepTarget) * 100));

  const { avgWBPct, wbAdherence } = calcWeightBearing(strides, gaitResult.labels, patient);

  const prescGait = patient.prescribedGait;
  const prescIdx  = ['2-Point','3-Point','4-Point'].indexOf(prescGait);
  const prescCount = prescIdx >= 0 ? gaitResult.counts[prescIdx + 1] : 0;
  const gaitMatchPct = strides.length ? Math.round((prescCount / strides.length) * 100) : 0;

  const cusi    = calcCUSI(strides, gaitResult.labels, patient);
  const cusiOk  = cusi < patient.cusiThreshold;
  const overall = calcAdherence(stepAdherence, wbAdherence, gaitMatchPct, cusiOk);
  const fallResult = detectFalls(clean, patient);

  let status, statusText, action;
  if (patient.crutchExpired) {
    status = 'flagged'; statusText = 'Crutch overdue';
    action = 'Crutch not returned — contact patient immediately';
  } else if (fallResult.userFalls > 0) {
    status = 'flagged'; statusText = 'Fall detected';
    action = `Fall detected — contact patient`;
  } else if (overall >= 85 && cusiOk) {
    status = 'ontrack'; statusText = 'On track';
    action = 'Progressing well — continue current protocol';
  } else {
    status = 'review'; statusText = 'Review needed';
    action = buildAction({ stepAdherence, avgWBPct, gaitMatchPct, cusi, patient });
  }

  return {
    stepCount, stepAdherence,
    avgWBPct, wbAdherence,
    gaitDistribution: gaitResult.distribution,
    detectedGait: gaitResult.detectedGait,
    gaitMatchPct, cusi, cusiOk,
    overallAdherence: overall,
    status, statusText, clinicianAction: action,
    fallResult,
    packetCount: clean.length,
    strides,
    strideLabels: gaitResult.labels,
  };
}

// ============================================================
// FALL DETECTION
// Algorithm (from report):
//   1. Crutch fall: pitch range > 30° within any 1.5 s window
//   2. User fall: crutch was loaded > 5% BW at any point in
//      the 2 s immediately before the crutch fall
// ============================================================

function detectFalls(packets, patient) {
  if (packets.length < 5) return { crutchFalls: 0, userFalls: 0, events: [] };

  const SAMPLE_RATE   = 30;          // Hz
  const WINDOW_1_5S   = Math.round(1.5 * SAMPLE_RATE);   // 45 samples
  const LOOKBACK_2S   = Math.round(2.0 * SAMPLE_RATE);   // 60 samples
  const PITCH_THRESH  = 30;          // degrees
  const LOAD_THRESH_N = 0.05 * patient.bodyWeightKg * 9.81;  // 5% BW

  const events = [];
  let crutchFalls = 0, userFalls = 0;
  let lastFallIdx = -WINDOW_1_5S;   // debounce — one event per window

  for (let i = WINDOW_1_5S; i < packets.length; i++) {
    if (i - lastFallIdx < WINDOW_1_5S) continue;  // debounce

    const window = packets.slice(i - WINDOW_1_5S, i);
    const pitches  = window.map(p => p.pitch || 0);
    const accMags  = window.map(p => Math.sqrt(p.ax**2 + p.ay**2 + p.az**2));

    // 1. Sustained pitch drop > 30° with no recovery > 10°
    const pitchDrop = pitches[0] - pitches[pitches.length - 1];
    let maxRecovery = 0, minSeen = pitches[0];
    for (const p of pitches) {
      if (p < minSeen) minSeen = p;
      else maxRecovery = Math.max(maxRecovery, p - minSeen);
    }
    const isSustainedDrop = pitchDrop > PITCH_THRESH && maxRecovery < 10;

    // 2. Impact spike — acc_mag exceeds 2g at some point during the window
    const hasImpact = Math.max(...accMags) > 2.0;

    if (isSustainedDrop && hasImpact) {
      crutchFalls++;
      lastFallIdx = i;

      // Look back 2 s before this window for any loading > 5% BW
      const lookback = packets.slice(Math.max(0, i - WINDOW_1_5S - LOOKBACK_2S),
                                     i - WINDOW_1_5S);
      const wasLoaded = lookback.some(p => {
        const f = p.load_kg != null && isFinite(p.load_kg)
          ? p.load_kg * 9.81
          : Math.max(0, (Math.abs(p.az) - 0.98) * patient.bodyWeightKg * 9.81);
        return f > LOAD_THRESH_N;
      });

      if (wasLoaded) userFalls++;
      events.push({
        packetIndex: i,
        timestamp: packets[i].timestamp,
        pitchDrop: Math.round(pitchDrop),
        userFell: wasLoaded,
      });
    }
  }

  return { crutchFalls, userFalls, events };
}

function nullAnalysis() {
  return {
    stepCount: 0, stepAdherence: 0, avgWBPct: 0, wbAdherence: 0,
    gaitDistribution: { Swing: 0, '2-Point': 0, '3-Point': 0, '4-Point': 100 },
    detectedGait: '—', gaitMatchPct: 0, cusi: 0, cusiOk: true,
    overallAdherence: 0, status: 'review', statusText: 'No data',
    clinicianAction: 'No sensor data — check instrument connection',
    packetCount: 0, strides: [], strideLabels: [],
  };
}

// Maths helpers
function mean(a) { const c = a.filter(isFinite); return c.length ? c.reduce((s,v)=>s+v,0)/c.length : 0; }
function std(a)  { const m = mean(a); return Math.sqrt(mean(a.map(v=>(v-m)**2))); }
function formatSteps(n) { return n >= 1000 ? (n/1000).toFixed(1)+'k' : String(n); }

// ============================================================
// ANALYSE BUTTON
// ============================================================

let _pollController = null;

function stopLivePolling() {
  if (_pollController) { _pollController.abort(); _pollController = null; }
  document.getElementById('analyseBtn').disabled = false;
  document.getElementById('stopBtn').style.display = 'none';
  setStatus('Live polling stopped.', 'warn');
}

async function analysePatient() {
  const patient = getSelectedPatient('analyticsPatientSelect');
  if (!patient) { setStatus('No patient selected.', 'error'); return; }
  if (_pollController) { stopLivePolling(); return; }
  S.selectedPatientId = patient.id;
  const instrumentId = document.getElementById('instrumentIdInput')?.value?.trim()
    || patient.instrumentId || DEFAULT_INSTRUMENT_ID;

  _pollController = new AbortController();
  _lastCursor = null;
  const signal = _pollController.signal;
  document.getElementById('analyseBtn').disabled = true;
  document.getElementById('stopBtn').style.display = '';
  setStatus('Connecting to Camgenium via proxy…');

  while (!signal.aborted) {
    try {
      const rawItems = await fetchAllPackets(instrumentId, signal);
      if (signal.aborted) break;
      const pkts = decodeApiItems(rawItems);
      if (pkts.length) { S.lastPackets = pkts; await runAnalysis(patient); }
      else setStatus('Waiting for packets…', 'warn');
    } catch (err) {
      if (err.name === 'AbortError') break;
      if (!signal.aborted) setStatus(err.message, 'error');
    }
    if (!signal.aborted) await new Promise(r => setTimeout(r, 1000));
  }
}

async function loadDemoData() {
  setStatus('Loading demo data…');
  try {
    const res = await fetch('raw_packets_demo.json');
    if (!res.ok) throw new Error('raw_packets_demo.json not found');
    S.lastPackets = decodeApiItems(await res.json());
    const patient = getSelectedPatient('analyticsPatientSelect');
    if (!patient) { setStatus('No patient selected.', 'error'); return; }
    await runAnalysis(patient);
  } catch (err) { setStatus(`${err.message}`, 'error'); }
}

function loadJsonFile(event) {
  const file = event.target.files[0]; if (!file) return;
  const reader = new FileReader();
  reader.onload = async e => {
    try {
      const raw = JSON.parse(e.target.result);
      S.lastPackets = decodeApiItems(Array.isArray(raw) ? raw : (raw.data || []));
      const patient = getSelectedPatient('analyticsPatientSelect');
      if (!patient) { setStatus('No patient selected.', 'error'); return; }
      await runAnalysis(patient);
    } catch { setStatus('Invalid JSON file.', 'error'); }
  };
  reader.readAsText(file);
}

async function runAnalysis(patient) {
  S.lastAnalysis          = await analysePackets(S.lastPackets, patient);
  S.lastAnalysisPatientId = patient.id;

  const p = getPatient(patient.id);
  if (p) {
    p.load       = Math.min(150, S.lastAnalysis.avgWBPct);
    p.limit      = `${S.lastAnalysis.avgWBPct}% BW vs ${patient.prescribedWeightBearingPercent}% target`;
    p.gait       = S.lastAnalysis.detectedGait;
    p.steps      = formatSteps(S.lastAnalysis.stepCount);
    p.adherence  = `${S.lastAnalysis.overallAdherence}%`;
    p.cusi       = S.lastAnalysis.cusi;
    p.status     = S.lastAnalysis.status;
    p.statusText = S.lastAnalysis.statusText;
    p.action     = S.lastAnalysis.clinicianAction;
    savePatients();
  }

  recordDailyEntry(patient, S.lastAnalysis);
  renderAnalyticsResults(S.lastAnalysis, S.lastPackets, patient);
  renderDashboard();
  buildPatientAppData();
  setStatus(
    `Analysis complete — ${S.lastPackets.length} packets, ` +
    `${S.lastAnalysis.strides.length} strides, ` +
    `${S.lastAnalysis.stepCount} steps. Dashboard updated.`,
    'success'
  );
}

// ============================================================
// ANALYTICS RESULTS DISPLAY
// ============================================================

function renderAnalyticsResults(analysis, packets, patient) {
  document.getElementById('analyticsContent')?.classList.remove('hidden');
  document.getElementById('metricSteps').textContent     = `${analysis.stepCount} / ${patient.dailyStepTarget}`;
  document.getElementById('metricWB').textContent        = `${analysis.avgWBPct}% (target: ${patient.prescribedWeightBearingPercent}%)`;
  document.getElementById('metricGait').textContent      = `${analysis.detectedGait} — ${analysis.gaitMatchPct}% match`;
  document.getElementById('metricCUSI').textContent      = `${analysis.cusi} / ${patient.cusiThreshold} limit`;
  document.getElementById('metricAdherence').textContent = `${analysis.overallAdherence}%`;

  const colour = (good) => good ? '#059669' : '#dc2626';
  document.getElementById('metricSteps').style.color     = colour(analysis.stepAdherence    >= 80);
  document.getElementById('metricWB').style.color        = colour(analysis.wbAdherence      >= 80);
  document.getElementById('metricGait').style.color      = colour(analysis.gaitMatchPct     >= 80);
  document.getElementById('metricCUSI').style.color      = colour(analysis.cusiOk);
  document.getElementById('metricAdherence').style.color = colour(analysis.overallAdherence >= 80);

  // Fall detection banner
  const fallEl = document.getElementById('fallAlert');
  if (fallEl) {
    const fr = analysis.fallResult;
    if (fr && fr.userFalls > 0) {
      fallEl.style.display = '';
      fallEl.textContent = `⚠ Fall detected — crutch fall registered with patient loaded. `
        + `${fr.crutchFalls} crutch fall(s), ${fr.userFalls} likely patient fall(s). Contact patient.`;
    } else if (fr && fr.crutchFalls > 0) {
      fallEl.style.display = '';
      fallEl.style.background = '#f59e0b';
      fallEl.textContent = `⚠ Crutch fall detected (${fr.crutchFalls} event(s)) — patient was not loaded at the time. May be a drop or trip.`;
    } else {
      fallEl.style.display = 'none';
    }
  }

  drawAllCharts(packets, patient, analysis);
  document.getElementById('rawPreview').textContent = JSON.stringify(packets.slice(0, 3), null, 2);
  drawHistoryChart(patient);
}

// ============================================================
// CHARTS
// ============================================================

function drawAllCharts(packets, patient, analysis) {
  const MAX = 600, skip = Math.max(1, Math.floor(packets.length / MAX));
  const s = packets.filter((_, i) => i % skip === 0);

  drawLineChart('accelChart',
    s.map(p => Math.sqrt(p.ax**2 + p.ay**2 + p.az**2)),
    'g', '#0f766e', 1.0);

  drawLineChart('gyroChart',
    s.map(p => Math.sqrt((p.gx||0)**2 + (p.gy||0)**2 + (p.gz||0)**2)),
    '°/s', '#7c3aed', null);

  drawLineChart('forceChart',
    s.map(p => estimateForceN(p, patient.bodyWeightKg)),
    'N (est.)', '#f59e0b', null);

  drawStepChart('stepChart', packets, skip);
  drawGaitDonut('gaitChart', analysis.gaitDistribution, patient.prescribedGait);
  drawStanceChart('stanceChart', packets, skip, patient);

  // Gait signature waveforms (like notebook's plot_gait_signatures)
  drawGaitSignature('sigAccelChart', analysis.strides, analysis.strideLabels, 9,  'Accel mag (g)',  '°/s');
  drawGaitSignature('sigGyroChart',  analysis.strides, analysis.strideLabels, 10, 'Gyro mag (°/s)', '°/s');
  drawGaitSignature('sigPitchChart', analysis.strides, analysis.strideLabels, 6,  'Pitch (°)',      '°');
}

// ── Generic line chart ────────────────────────────────────────
function drawLineChart(id, values, unit, colour, refLine) {
  const canvas = document.getElementById(id); if (!canvas) return;
  canvas.width = canvas.offsetWidth || 600; canvas.height = 200;
  const ctx = canvas.getContext('2d');
  const W = canvas.width, H = canvas.height;
  const pl = 50, pr = 16, pt = 28, pb = 28;
  const w = W-pl-pr, h = H-pt-pb;
  ctx.clearRect(0,0,W,H); ctx.fillStyle='#f8fbff'; ctx.fillRect(0,0,W,H);
  if (!values.length) return;
  const minV = Math.min(...values), maxV = Math.max(...values), range = (maxV-minV)||1;
  const toX = i => pl + (i/Math.max(values.length-1,1))*w;
  const toY = v => pt + h - ((v-minV)/range)*h;
  // grid
  ctx.strokeStyle='#e2e8f0'; ctx.lineWidth=1;
  for (let g=0;g<=4;g++) { const y=pt+(g/4)*h; ctx.beginPath(); ctx.moveTo(pl,y); ctx.lineTo(pl+w,y); ctx.stroke(); }
  // y labels
  ctx.fillStyle='#94a3b8'; ctx.font='10px Arial'; ctx.textAlign='right';
  for (let g=0;g<=4;g++) ctx.fillText((maxV-(g/4)*range).toFixed(1), pl-4, pt+(g/4)*h+4);
  // ref line
  if (refLine != null) {
    const ry = toY(refLine);
    if (ry>=pt && ry<=pt+h) {
      ctx.strokeStyle='#94a3b8'; ctx.setLineDash([5,4]); ctx.lineWidth=1;
      ctx.beginPath(); ctx.moveTo(pl,ry); ctx.lineTo(pl+w,ry); ctx.stroke(); ctx.setLineDash([]);
    }
  }
  // fill
  ctx.save(); ctx.globalAlpha=0.12; ctx.fillStyle=colour;
  ctx.beginPath();
  values.forEach((v,i)=>{ const x=toX(i),y=toY(v); i===0?ctx.moveTo(x,y):ctx.lineTo(x,y); });
  ctx.lineTo(toX(values.length-1),pt+h); ctx.lineTo(toX(0),pt+h); ctx.closePath(); ctx.fill(); ctx.restore();
  // line
  ctx.strokeStyle=colour; ctx.lineWidth=1.5;
  ctx.beginPath();
  values.forEach((v,i)=>{ const x=toX(i),y=toY(v); i===0?ctx.moveTo(x,y):ctx.lineTo(x,y); });
  ctx.stroke();
  // unit label
  ctx.fillStyle='#334155'; ctx.font='bold 11px Arial'; ctx.textAlign='left';
  ctx.fillText(unit, pl+4, pt-6);
}

// ── Step chart with stance shading ────────────────────────────
function drawStepChart(id, packets, skip) {
  const canvas = document.getElementById(id); if (!canvas) return;
  canvas.width = canvas.offsetWidth||600; canvas.height=200;
  const ctx = canvas.getContext('2d');
  const W=canvas.width, H=canvas.height, pl=50,pr=16,pt=28,pb=28, w=W-pl-pr, h=H-pt-pb;
  ctx.clearRect(0,0,W,H); ctx.fillStyle='#f8fbff'; ctx.fillRect(0,0,W,H);
  const samp  = packets.filter((_,i)=>i%(skip||1)===0);
  const mags  = samp.map(p=>Math.sqrt(p.ax**2+p.ay**2+p.az**2));
  const stance= detectStance(samp);
  if (!mags.length) return;
  const minV=Math.min(...mags), maxV=Math.max(...mags), range=(maxV-minV)||1;
  const toX = i=>pl+(i/Math.max(mags.length-1,1))*w;
  const toY = v=>pt+h-((v-minV)/range)*h;
  const mu=mean(mags), thr=1.12;
  // stance shading
  ctx.fillStyle='rgba(15,118,110,0.08)';
  stance.forEach((s,i)=>{ if(s) ctx.fillRect(toX(i),pt,Math.max(1,toX(i+1||i)-toX(i)),h); });
  // threshold
  ctx.strokeStyle='#ef4444'; ctx.setLineDash([4,4]); ctx.lineWidth=1;
  ctx.beginPath(); ctx.moveTo(pl,toY(thr)); ctx.lineTo(pl+w,toY(thr)); ctx.stroke(); ctx.setLineDash([]);
  // accel line
  ctx.strokeStyle='#0f766e'; ctx.lineWidth=1.5;
  ctx.beginPath();
  mags.forEach((v,i)=>{ const x=toX(i),y=toY(v); i===0?ctx.moveTo(x,y):ctx.lineTo(x,y); });
  ctx.stroke();
  // step peaks
  let lastPeak=-8;
  ctx.fillStyle='#f59e0b';
  mags.forEach((v,i)=>{ if(v>thr&&(i===0||v>=mags[i-1])&&(i===mags.length-1||v>=mags[i+1])&&(i-lastPeak)>=8){ ctx.beginPath(); ctx.arc(toX(i),toY(v),4,0,Math.PI*2); ctx.fill(); lastPeak=i; } });
  ctx.fillStyle='#334155'; ctx.font='bold 11px Arial'; ctx.textAlign='left';
  ctx.fillText('|a| g  (green=stance, amber=step)', pl+4, pt-6);
}

// ── Stance overlay chart ──────────────────────────────────────
function drawStanceChart(id, packets, skip, patient) {
  const canvas = document.getElementById(id); if (!canvas) return;
  canvas.width = canvas.offsetWidth||600; canvas.height=200;
  const ctx = canvas.getContext('2d');
  const W=canvas.width,H=canvas.height,pl=50,pr=16,pt=28,pb=28,w=W-pl-pr,h=H-pt-pb;
  ctx.clearRect(0,0,W,H); ctx.fillStyle='#f8fbff'; ctx.fillRect(0,0,W,H);
  const samp  = packets.filter((_,i)=>i%(skip||1)===0);
  const mags  = samp.map(p=>Math.sqrt(p.ax**2+p.ay**2+p.az**2));
  const stance= detectStance(samp);
  const bwKg  = patient?.bodyWeightKg ?? 70;
  const forces= samp.map(p=>estimateForceN(p, bwKg));
  if (!mags.length) return;
  const maxF=Math.max(...forces)||1;
  const toX=i=>pl+(i/Math.max(samp.length-1,1))*w;
  const toYf=v=>pt+h-(v/maxF)*h;
  // stance bands
  ctx.fillStyle='rgba(245,158,11,0.15)';
  stance.forEach((s,i)=>{ if(s) ctx.fillRect(toX(i),pt,Math.max(1,2),h); });
  // force line
  ctx.strokeStyle='#f59e0b'; ctx.lineWidth=1.5;
  ctx.beginPath();
  forces.forEach((v,i)=>{ const x=toX(i),y=toYf(v); i===0?ctx.moveTo(x,y):ctx.lineTo(x,y); });
  ctx.stroke();
  ctx.fillStyle='#334155'; ctx.font='bold 11px Arial'; ctx.textAlign='left';
  ctx.fillText('Est. force (N) — amber bands = stance phases', pl+4, pt-6);
}

// ── Gait donut ────────────────────────────────────────────────
function drawGaitDonut(id, distribution, prescribed) {
  const canvas = document.getElementById(id); if (!canvas) return;
  canvas.width=canvas.offsetWidth||300; canvas.height=280;
  const ctx=canvas.getContext('2d'), W=canvas.width, H=canvas.height;
  ctx.clearRect(0,0,W,H); ctx.fillStyle='#f8fbff'; ctx.fillRect(0,0,W,H);
  const cx=W/2, cy=(H-70)/2+14, r=Math.min(W*0.36,(H-80)/2);
  const colours={'Swing':'#94a3b8','2-Point':'#7c3aed','3-Point':'#0f766e','4-Point':'#f59e0b'};
  const entries=Object.entries(distribution), total=entries.reduce((s,[,v])=>s+v,0)||1;
  let angle=-Math.PI/2;
  entries.forEach(([lbl,val])=>{
    const sl=(val/total)*Math.PI*2;
    ctx.beginPath(); ctx.moveTo(cx,cy); ctx.arc(cx,cy,r,angle,angle+sl); ctx.closePath();
    ctx.fillStyle=colours[lbl]||'#94a3b8'; ctx.fill(); angle+=sl;
  });
  ctx.beginPath(); ctx.arc(cx,cy,r*0.54,0,Math.PI*2); ctx.fillStyle='#f8fbff'; ctx.fill();
  const dominant=entries.reduce((a,b)=>b[1]>a[1]?b:a,entries[0])[0];
  ctx.fillStyle='#334155'; ctx.font='bold 11px Arial'; ctx.textAlign='center'; ctx.fillText(dominant,cx,cy+4);
  let ly=cy+r+22;
  entries.forEach(([lbl,val])=>{
    ctx.fillStyle=colours[lbl]||'#94a3b8'; ctx.fillRect(cx-80,ly-12,14,14);
    ctx.fillStyle=lbl===prescribed?'#059669':'#334155';
    ctx.font=lbl===prescribed?'bold 13px Arial':'13px Arial'; ctx.textAlign='left';
    ctx.fillText(`${lbl}: ${Math.round(val)}%${lbl===prescribed?' ✓':''}`,cx-62,ly); ly+=22;
  });
}

// ── Gait signature waveforms (notebook: plot_gait_signatures) ─
// Mean ± 1 SD per gait class for a given feature channel
function drawGaitSignature(id, strides, labels, channelIdx, yLabel) {
  const canvas = document.getElementById(id); if (!canvas) return;
  canvas.width = canvas.offsetWidth||600; canvas.height=200;
  const ctx = canvas.getContext('2d');
  const W=canvas.width, H=canvas.height, pl=50, pr=16, pt=28, pb=36, w=W-pl-pr, h=H-pt-pb;
  ctx.clearRect(0,0,W,H); ctx.fillStyle='#f8fbff'; ctx.fillRect(0,0,W,H);

  const GAIT_COLOURS = ['#94a3b8','#7c3aed','#0f766e','#f59e0b'];
  const GAIT_NAMES   = ['Swing','2-Point','3-Point','4-Point'];
  const x = Array.from({length: N_POINTS}, (_,i) => i); // 0–99 = gait cycle %

  // Group interpolated channel data by gait label
  const byLabel = {0:[],1:[],2:[],3:[]};
  strides.forEach((stride, si) => {
    const feat = extractStrideFeatures(stride);
    byLabel[labels[si]].push(Array.from(feat.channels[channelIdx]));
  });

  // Compute per-class mean and std
  const series = Object.entries(byLabel)
    .filter(([,data]) => data.length > 0)
    .map(([lbl, data]) => {
      const n = data.length;
      const mu  = Array.from({length:N_POINTS}, (_,i) => data.reduce((s,d)=>s+d[i],0)/n);
      const sig = Array.from({length:N_POINTS}, (_,i) => Math.sqrt(data.reduce((s,d)=>s+(d[i]-mu[i])**2,0)/n));
      return { lbl: Number(lbl), mu, sig };
    });

  if (!series.length) {
    ctx.fillStyle='#94a3b8'; ctx.font='13px Arial'; ctx.textAlign='center';
    ctx.fillText('No stride data yet', W/2, H/2); return;
  }

  // Compute global min/max for y-axis
  let allVals = series.flatMap(({mu,sig})=>mu.map((v,i)=>[v+sig[i],v-sig[i]]).flat());
  const minV = Math.min(...allVals), maxV = Math.max(...allVals), range=(maxV-minV)||1;
  const toX  = i => pl + (i/(N_POINTS-1))*w;
  const toY  = v => pt + h - ((v-minV)/range)*h;

  // Grid
  ctx.strokeStyle='#e2e8f0'; ctx.lineWidth=1;
  for (let g=0;g<=4;g++){const y=pt+(g/4)*h; ctx.beginPath();ctx.moveTo(pl,y);ctx.lineTo(pl+w,y);ctx.stroke();}
  ctx.fillStyle='#94a3b8'; ctx.font='10px Arial'; ctx.textAlign='right';
  for (let g=0;g<=4;g++) ctx.fillText((maxV-(g/4)*range).toFixed(1),pl-4,pt+(g/4)*h+4);

  // Draw each class
  series.forEach(({lbl,mu,sig}) => {
    const col = GAIT_COLOURS[lbl];
    // Shaded ±1 SD band
    ctx.save(); ctx.globalAlpha=0.18; ctx.fillStyle=col;
    ctx.beginPath();
    mu.forEach((v,i)=>{ const y=toY(v+sig[i]); i===0?ctx.moveTo(toX(i),y):ctx.lineTo(toX(i),y); });
    for (let i=N_POINTS-1;i>=0;i--) ctx.lineTo(toX(i),toY(mu[i]-sig[i]));
    ctx.closePath(); ctx.fill(); ctx.restore();
    // Mean line
    ctx.strokeStyle=col; ctx.lineWidth=2;
    ctx.beginPath();
    mu.forEach((v,i)=>{ i===0?ctx.moveTo(toX(i),toY(v)):ctx.lineTo(toX(i),toY(v)); });
    ctx.stroke();
  });

  // X-axis label
  ctx.fillStyle='#64748b'; ctx.font='11px Arial'; ctx.textAlign='center';
  ctx.fillText('Stride cycle (%)', pl+w/2, H-6);
  // Y-axis label
  ctx.fillStyle='#334155'; ctx.font='bold 11px Arial'; ctx.textAlign='left';
  ctx.fillText(yLabel, pl+4, pt-6);

  // Legend
  let lx = pl;
  series.forEach(({lbl}) => {
    ctx.fillStyle=GAIT_COLOURS[lbl]; ctx.fillRect(lx, 5, 10, 10);
    ctx.fillStyle='#334155'; ctx.font='11px Arial'; ctx.textAlign='left';
    ctx.fillText(GAIT_NAMES[lbl], lx+13, 14); lx += 70;
  });
}

// ── Multi-day history chart ───────────────────────────────────
function drawHistoryChart(patient) {
  const history = S.history[patient.id] || {};
  const dates   = Object.keys(history).sort();
  const section = document.getElementById('historySection');
  if (!section) return;
  if (dates.length < 2) { section.classList.add('hidden'); return; }
  section.classList.remove('hidden');

  const canvas = document.getElementById('historyChart'); if (!canvas) return;
  canvas.width = canvas.offsetWidth||900; canvas.height=220;
  const ctx=canvas.getContext('2d'), W=canvas.width, H=canvas.height;
  const pl=56,pr=20,pt=28,pb=48, w=W-pl-pr, h=H-pt-pb;
  ctx.clearRect(0,0,W,H); ctx.fillStyle='#f8fbff'; ctx.fillRect(0,0,W,H);

  const steps  = dates.map(d=>history[d].step_count||0);
  const cusis  = dates.map(d=>history[d].cusi||0);
  const wbs    = dates.map(d=>history[d].avg_weight_bearing_pct||0);
  const maxS   = Math.max(...steps, patient.dailyStepTarget, 1);
  const n      = dates.length;
  const toX    = i=>pl+(i/Math.max(n-1,1))*w;

  // Grid
  ctx.strokeStyle='#e2e8f0'; ctx.lineWidth=1;
  for (let g=0;g<=4;g++){const y=pt+(g/4)*h;ctx.beginPath();ctx.moveTo(pl,y);ctx.lineTo(pl+w,y);ctx.stroke();}

  // Step target line
  const ty=pt+h-(patient.dailyStepTarget/maxS)*h;
  ctx.strokeStyle='#0f766e'; ctx.setLineDash([5,4]); ctx.lineWidth=1;
  ctx.beginPath(); ctx.moveTo(pl,ty); ctx.lineTo(pl+w,ty); ctx.stroke(); ctx.setLineDash([]);

  function drawSeries(vals, maxVal, col, lxOffset) {
    const toY=v=>pt+h-(v/Math.max(maxVal,1))*h;
    ctx.save(); ctx.globalAlpha=0.1; ctx.fillStyle=col;
    ctx.beginPath(); vals.forEach((v,i)=>{ i===0?ctx.moveTo(toX(i),toY(v)):ctx.lineTo(toX(i),toY(v)); });
    ctx.lineTo(toX(n-1),pt+h); ctx.lineTo(toX(0),pt+h); ctx.closePath(); ctx.fill(); ctx.restore();
    ctx.strokeStyle=col; ctx.lineWidth=2;
    ctx.beginPath(); vals.forEach((v,i)=>{ i===0?ctx.moveTo(toX(i),toY(v)):ctx.lineTo(toX(i),toY(v)); }); ctx.stroke();
    vals.forEach((v,i)=>{ ctx.fillStyle=col; ctx.beginPath(); ctx.arc(toX(i),toY(v),4,0,Math.PI*2); ctx.fill(); });
    ctx.fillStyle=col; ctx.fillRect(pl+lxOffset,6,12,12);
    ctx.fillStyle='#334155'; ctx.font='12px Arial'; ctx.textAlign='left';
  }

  drawSeries(steps, maxS,  '#0f766e', 0);   ctx.fillText('Steps', pl+16, 17);
  drawSeries(wbs,   100,   '#f59e0b', 130); ctx.fillText('WB% target', pl+146, 17);
  drawSeries(cusis, 20,    '#7c3aed', 270); ctx.fillText('CUSI (÷20)', pl+286, 17);

  // X labels
  ctx.fillStyle='#64748b'; ctx.font='11px Arial'; ctx.textAlign='center';
  dates.forEach((d,i)=>ctx.fillText(d.slice(5),toX(i),pt+h+18));
}

// ============================================================
// REGISTER / EDIT PATIENT
// ============================================================

function updateWBDisplay() {
  const sel = document.getElementById('reg_wbLevel');
  document.getElementById('customWBGroup').style.display = sel.value==='custom'?'':'none';
}

function savePatient(event) {
  event.preventDefault();
  const wbSel = document.getElementById('reg_wbLevel').value;
  const wbPct = wbSel==='custom' ? Number(document.getElementById('reg_wbCustom').value||30) : Number(wbSel);
  const name     = document.getElementById('reg_name').value.trim();
  const initials = document.getElementById('reg_initials').value.trim().toUpperCase();
  const editId   = document.getElementById('reg_editId').value;
  const existIdx = editId ? S.patients.findIndex(p=>p.id===editId) : -1;
  const id       = existIdx>=0 ? S.patients[existIdx].id : initials+'_'+Date.now();

  const newP = normalisePatient({
    id, initials, name, gender: document.getElementById('reg_gender').value,
    detail: document.getElementById('reg_detail').value.trim(),
    age:    document.getElementById('reg_age').value,
    bodyWeightKg: document.getElementById('reg_weight').value,
    prescribedGait: document.getElementById('reg_gait').value,
    prescribedWeightBearingPercent: wbPct,
    dailyStepTarget: document.getElementById('reg_steps').value,
    cusiThreshold:   document.getElementById('reg_cusi').value,
    rehabDurationDays: document.getElementById('reg_duration').value||25,
    instrumentId: document.getElementById('reg_instrumentId').value.trim()||DEFAULT_INSTRUMENT_ID,
    status: existIdx>=0 ? S.patients[existIdx].status : 'review',
    statusText: existIdx>=0 ? S.patients[existIdx].statusText : 'New patient',
    action: 'Run analysis after first session',
    startDate: existIdx>=0 ? S.patients[existIdx].startDate : new Date().toISOString().split('T')[0],
  });

  if (existIdx>=0) S.patients[existIdx] = { ...S.patients[existIdx], ...newP };
  else S.patients.push(newP);

  S.selectedPatientId = id;
  savePatients(); populateSelectors(); renderDashboard(); renderEditPatientList(); buildPatientAppData();
  clearRegisterForm();

  const el = document.getElementById('registerStatus');
  if (el) { el.textContent=`Patient "${name}" saved.`; el.className='status-msg success';
    setTimeout(()=>{el.textContent='';el.className='status-msg';},3000); }
  showTab('dashboard');
}

function clearRegisterForm() {
  document.getElementById('registerForm').reset();
  document.getElementById('reg_editId').value='';
  document.getElementById('reg_stepsVal').textContent='5000';
  document.getElementById('reg_cusi').value='15';
  document.getElementById('reg_cusiVal').textContent='15';
  document.getElementById('customWBGroup').style.display='none';
}

function renderEditPatientList() {
  const c = document.getElementById('editPatientRows'); if (!c) return;
  if (!S.patients.length) { c.innerHTML='<div class="empty-message">No patients yet.</div>'; return; }
  c.innerHTML = S.patients.map(p=>`
    <div class="edit-patient-row">
      <div class="avatar ${p.status==='ontrack'?'green':p.status==='review'?'amber':'red'}">${p.initials}</div>
      <div><div class="name">${p.name}</div><div class="detail">${p.detail} · ${p.prescribedGait} · ${p.prescribedWeightBearingPercent}% BW</div></div>
      <button class="btn-secondary small" onclick="editPatient('${p.id}')">Edit</button>
      <button class="btn-danger small"    onclick="deletePatient('${p.id}')">Remove</button>
    </div>`).join('');
}

function editPatient(id) {
  const p = getPatient(id); if (!p) return;
  document.getElementById('reg_editId').value = p.id;
  document.getElementById('reg_name').value      = p.name;
  document.getElementById('reg_initials').value  = p.initials;
  document.getElementById('reg_age').value       = p.age;
  document.getElementById('reg_gender').value    = p.gender||'Other';
  document.getElementById('reg_weight').value    = p.bodyWeightKg;
  document.getElementById('reg_detail').value    = p.detail;
  document.getElementById('reg_gait').value      = p.prescribedGait;
  document.getElementById('reg_steps').value     = p.dailyStepTarget;
  document.getElementById('reg_stepsVal').textContent = p.dailyStepTarget;
  document.getElementById('reg_cusi').value      = p.cusiThreshold;
  document.getElementById('reg_cusiVal').textContent  = p.cusiThreshold;
  document.getElementById('reg_duration').value  = p.rehabDurationDays;
  document.getElementById('reg_instrumentId').value = p.instrumentId||'';
  const presets=['0','12','20','40','75','100'], wbStr=String(p.prescribedWeightBearingPercent);
  const sel=document.getElementById('reg_wbLevel');
  if (presets.includes(wbStr)) { sel.value=wbStr; document.getElementById('customWBGroup').style.display='none'; }
  else { sel.value='custom'; document.getElementById('reg_wbCustom').value=p.prescribedWeightBearingPercent; document.getElementById('customWBGroup').style.display=''; }
  showTab('register');
}

function deletePatient(id) {
  const p = getPatient(id); if (!p||!confirm(`Remove ${p.name}?`)) return;
  S.patients = S.patients.filter(q=>q.id!==id);
  if (S.selectedPatientId===id) S.selectedPatientId=S.patients[0]?.id??null;
  savePatients(); populateSelectors(); renderDashboard(); renderEditPatientList();
}

// ============================================================
// PATIENT APP DATA
// ============================================================

function buildPatientAppData() {
  const id      = document.getElementById('appPatientSelect')?.value || S.selectedPatientId;
  const patient = getPatient(id) || S.patients[0];
  if (!patient) { const o=document.getElementById('patientAppOutput'); if(o) o.textContent='No patients registered.'; return; }
  const start    = new Date(patient.startDate||Date.now());
  const dayNum   = Math.max(1, Math.round((Date.now()-start)/86400000));
  const analysis = S.lastAnalysis && S.lastAnalysisPatientId===patient.id ? S.lastAnalysis : null;
  const appData  = {
    patientId: patient.id, initials: patient.initials, name: patient.name,
    timestamp: new Date().toISOString(), dayNumber: dayNum,
    rehabDurationDays: patient.rehabDurationDays,
    metrics: {
      stepCount:                    analysis?.stepCount     ?? null,
      dailyStepTarget:              patient.dailyStepTarget,
      stepAdherencePct:             analysis?.stepAdherence ?? null,
      weightBearingPctBW:           analysis?.avgWBPct      ?? null,
      prescribedWeightBearingPctBW: patient.prescribedWeightBearingPercent,
      detectedGait:                 analysis?.detectedGait  ?? null,
      prescribedGait:               patient.prescribedGait,
      gaitMatchPct:                 analysis?.gaitMatchPct  ?? null,
      cusi:                         analysis?.cusi          ?? patient.cusi ?? null,
      cusiThreshold:                patient.cusiThreshold,
      overallAdherencePct:          analysis?.overallAdherence ?? null,
    },
    message: buildPatientMessage(patient, analysis),
    status: patient.status,
    // Full per-day history dict for any date the app wants
    history: S.history[patient.id] || {},
  };
  const out = document.getElementById('patientAppOutput');
  if (out) out.textContent = JSON.stringify(appData, null, 2);
  return appData;
}

function buildPatientMessage(patient, analysis) {
  if (!analysis) return 'Your clinician will update your progress after your next session.';
  if (!analysis.cusiOk) return 'Your wrist strain is above the recommended level — check crutch height and grip.';
  const t = patient.prescribedWeightBearingPercent;
  if (t>0 && analysis.avgWBPct>t*1.15) return 'You are loading the injured side more than prescribed.';
  if (analysis.status==='ontrack') return `Great work! ${analysis.stepCount} steps today. Keep using your ${patient.prescribedGait} gait.`;
  return 'Review your gait and weight-bearing instructions. Your clinician will be in touch.';
}

function downloadPatientAppJson() {
  const data = buildPatientAppData(); if (!data) return;
  const a = Object.assign(document.createElement('a'), {
    href: URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'})),
    download: `stepwise_${data.patientId}_${new Date().toISOString().split('T')[0]}.json`,
  });
  a.click(); URL.revokeObjectURL(a.href);
}

async function pushToNgrok() {
  const el  = document.getElementById('ngrokStatus');
  const data = buildPatientAppData(); if (!data) { alert('No data to push.'); return; }
  // Save file to disk via proxy, then show teammate URL
  try {
    const res = await fetch(`${PROXY_BASE}/save-patient-data`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    });
    const ngrokBase = (document.getElementById('ngrokUrl')?.value?.trim() || '')
      .replace(/\/patient[-_]data(\.json)?$/, '');
    if (el) {
      el.textContent = `Ready! Teammate URL: ${ngrokBase}/patient_app_data.json`;
      el.className = 'status-msg success';
    }
  } catch(err) {
    // Proxy unreachable — fall back to download
    downloadPatientAppJson();
    if (el) { el.textContent = 'Proxy unavailable — file downloaded instead. Share it manually.'; el.className = 'status-msg warn'; }
  }
}

function setStatus(msg, type='') {
  const el = document.getElementById('analyticsStatus');
  if (el) { el.textContent=msg; el.className='status-msg '+type; }
}
