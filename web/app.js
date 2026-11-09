'use strict';
/* OffGrid Messenger v1.3: protocol 2 web client with persistent history and polished UI.
   Still unencrypted test firmware: do not send private or sensitive content yet. */
const $ = id => document.getElementById(id);
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', { fatal: false });
const MAX_BYTES = 180;
const APP_VERSION = '1.3.0';
const STORAGE_PREFIX = 'offgrid_phase13';
let port = null, reader = null, writer = null, readPromise = null, closing = false, connected = false, verified = false, serialBuffer = '';
let handshakeTimer = null, writeChain = Promise.resolve(), localMac = null;
let pendingLocal = [], sentMessages = new Map(), seenIncoming = new Set(), unreadMessages = new Map();
let activeContact = 'Nearby node';
let chatHistory = [];
let theme = 'dark';
const stateRank = { queued: 0, sending: 0, sent: 1, failed: 1, delivered: 2, read: 3 };
try { theme = localStorage.getItem('offgrid_theme') === 'light' ? 'light' : 'dark'; } catch {}
document.body.classList.toggle('light', theme === 'light');
const fmtTime = (ts = Date.now()) => new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const uid = (session, id) => `${session}:${id}`;
const validMac = s => /^[0-9a-f]{2}(:[0-9a-f]{2}){5}$/i.test(s);
const fromHex = hex => { if (hex.length % 2 || !/^[0-9a-f]*$/i.test(hex)) throw Error('Malformed hex'); return decoder.decode(Uint8Array.from(hex.match(/.{2}/g)?.map(v => parseInt(v, 16)) || [])); };
const toHex = text => Array.from(encoder.encode(text), b => b.toString(16).padStart(2, '0')).join('').toUpperCase();
const selfKey = () => localMac ? localMac.replace(/:/g, '-') : 'default';
const keyFor = suffix => `${STORAGE_PREFIX}_${suffix}_${selfKey()}`;
const clampHistory = items => items.slice(-250);

function notify(message) {
  const toast = document.createElement('div');
  toast.className = 'toast';
  toast.textContent = message;
  $('toastHost').appendChild(toast);
  setTimeout(() => toast.remove(), 3500);
}
function isChatVisible() {
  return !document.hidden && document.hasFocus() && (innerWidth > 590 || document.body.classList.contains('mobile-chat'));
}
function setConnection(mode, detail = '') {
  connected = mode !== 'offline';
  verified = mode === 'ready';
  $('statusDot').className = 'status-dot' + (mode === 'ready' ? ' on' : mode === 'waiting' ? ' wait' : '');
  $('connectionState').textContent = mode === 'ready' ? 'Connected' : mode === 'waiting' ? 'Checking firmware' : 'Disconnected';
  $('connectionDescription').textContent = detail || (mode === 'ready' ? 'USB connection established' : mode === 'waiting' ? 'Waiting for protocol handshake' : 'Connect your ESP32-C3 over USB');
  $('nodeAddress').textContent = 'Node: ' + (localMac || '—');
  $('connectButton').disabled = connected;
  $('disconnectButton').disabled = !connected;
  $('messageInput').disabled = !verified;
  $('sendButton').disabled = !verified || !$('messageInput').value.trim();
  $('messageInput').placeholder = verified ? 'Type a message' : 'Connect ESP32 to send messages';
  $('chatSubhead').textContent = mode === 'ready' ? 'Connected over ESP-NOW · channel 6' : mode === 'waiting' ? 'Verifying radio firmware' : 'Offline · connect ESP32';
  $('composerStatus').textContent = mode === 'ready' ? 'Enter to send · Shift+Enter for newline (180-byte limit)' : mode === 'waiting' ? 'Checking firmware protocol 2' : 'Connect an ESP32 using the sidebar';
}
function setIntroVisibility() {
  $('introCard').style.display = chatHistory.length ? 'none' : '';
}
function updatePreview(text, outgoing, ts = Date.now()) {
  $('lastChatPreview').textContent = (outgoing ? 'You: ' : '') + text;
  $('lastChatTime').textContent = fmtTime(ts);
}
function inferReceiptClass(status) {
  if (status === 'read') return 'receipt read';
  if (status === 'delivered' || status === 'sent') return 'receipt';
  if (status === 'sending') return 'receipt pending';
  if (status === 'queued') return 'receipt queued';
  if (status === 'failed') return 'receipt failed';
  return 'receipt';
}
function createReceipt(status) {
  const wrap = document.createElement('span');
  wrap.className = inferReceiptClass(status);
  wrap.dataset.role = 'tick';
  const first = document.createElement('span');
  first.className = 'check first';
  first.textContent = '✓';
  const second = document.createElement('span');
  second.className = 'check second';
  second.textContent = '✓';
  if (status === 'sent') wrap.classList.add('single');
  wrap.append(first, second);
  return wrap;
}
function messageBubble(message, animate = true) {
  const { hid, text, direction, status = '', ts = Date.now(), wireId = '' } = message;
  const row = document.createElement('div');
  row.className = 'message-row ' + (direction === 'out' ? 'out' : 'in') + (animate ? ' is-new' : '');
  row.dataset.hid = hid;
  if (wireId) row.dataset.wireId = wireId;
  const b = document.createElement('div');
  b.className = 'message-bubble';
  const body = document.createElement('div');
  body.className = 'message-text';
  body.textContent = text;
  const meta = document.createElement('div');
  meta.className = 'message-meta';
  const time = document.createElement('span');
  time.textContent = fmtTime(ts);
  meta.append(time);
  if (direction === 'out') {
    const tick = createReceipt(status || 'sending');
    meta.append(tick);
  }
  b.append(body, meta);
  row.appendChild(b);
  $('messages').appendChild(row);
  if (direction === 'out') setMessageState(row, status || 'sending', false);
  $('chatScroll').scrollTop = $('chatScroll').scrollHeight;
  setIntroVisibility();
  return row;
}
function saveHistory() {
  try { localStorage.setItem(keyFor('history'), JSON.stringify(clampHistory(chatHistory))); } catch {}
  setIntroVisibility();
}
function updateSidebarFromHistory() {
  const last = chatHistory[chatHistory.length - 1];
  if (last) updatePreview(last.text, last.direction === 'out', last.ts);
  else {
    $('lastChatPreview').textContent = 'Your off-grid conversation';
    $('lastChatTime').textContent = '';
  }
}
function persistName(name) {
  try { localStorage.setItem(keyFor('name'), name); } catch {}
}
function loadHistory() {
  $('messages').replaceChildren();
  chatHistory = [];
  sentMessages = new Map();
  seenIncoming = new Set();
  try {
    const parsed = JSON.parse(localStorage.getItem(keyFor('history')) || '[]');
    if (Array.isArray(parsed)) chatHistory = parsed;
  } catch {}
  for (const message of chatHistory) {
    const row = messageBubble(message, false);
    if (message.direction === 'out' && message.wireId) sentMessages.set(message.wireId, row);
    if (message.direction === 'in' && message.rxKey) seenIncoming.add(message.rxKey);
  }
  updateSidebarFromHistory();
  setIntroVisibility();
}
function appendHistoryMessage(message, animate = true) {
  chatHistory.push(message);
  chatHistory = clampHistory(chatHistory);
  const row = messageBubble(message, animate);
  saveHistory();
  updateSidebarFromHistory();
  return row;
}
function updateHistoryMessage(hid, patch) {
  const item = chatHistory.find(m => m.hid === hid);
  if (!item) return;
  Object.assign(item, patch);
  saveHistory();
  updateSidebarFromHistory();
}
function setMessageState(row, newStatus, persist = true) {
  if (!row) return;
  const prior = row.dataset.status || 'sending';
  if (newStatus !== 'failed' && newStatus !== 'sending' && (stateRank[newStatus] ?? 0) < (stateRank[prior] ?? 0)) return;
  if (prior === 'read' && newStatus !== 'read') return;
  if (prior === 'delivered' && newStatus === 'failed') return;
  row.dataset.status = newStatus;
  const tick = row.querySelector('[data-role="tick"]');
  if (tick) {
    tick.className = inferReceiptClass(newStatus);
    tick.classList.toggle('single', newStatus === 'sent');
  }
  const labels = { sending: 'Sending', queued: 'Queued', sent: 'Sent', delivered: 'Delivered', read: 'Read', failed: 'Failed' };
  row.title = labels[newStatus] || 'Sending';
  if (persist && row.dataset.hid) updateHistoryMessage(row.dataset.hid, { status: newStatus });
}
function sendLine(line) {
  const job = writeChain.then(async () => {
    if (!writer) throw Error('USB disconnected');
    await writer.write(encoder.encode(line + '\n'));
  });
  writeChain = job.catch(() => {});
  return job;
}
function readUnread() {
  if (!verified || !isChatVisible()) return;
  for (const [key, record] of unreadMessages) {
    sendLine(`READ ${record.mac} ${record.session} ${record.id}`)
      .then(() => unreadMessages.delete(key))
      .catch(() => {});
  }
}
function acceptReceived(mac, session, id, hex) {
  if (!validMac(mac) || !/^\d+$/.test(session) || !/^\d+$/.test(id) || typeof hex !== 'string' || hex.length > MAX_BYTES * 2) return;
  const rxKey = `${mac}:${session}:${id}`;
  if (!seenIncoming.has(rxKey)) {
    let text;
    try { text = fromHex(hex); } catch { return; }
    seenIncoming.add(rxKey);
    const hid = `in-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    appendHistoryMessage({ hid, rxKey, text, direction: 'in', status: 'received', ts: Date.now() });
    unreadMessages.set(rxKey, { mac, session, id });
  }
  sendLine(`APPACK ${mac} ${session} ${id}`).catch(() => {});
  readUnread();
}
function parseLine(raw) {
  const line = raw.trim(); if (!line) return;
  if (line.startsWith('READY|')) {
    const parts = line.split('|'); const proto = parts.find(p => p.startsWith('PROTO='));
    if (proto !== 'PROTO=2') { setConnection('waiting', 'Incompatible firmware (requires protocol 2)'); notify('Flash both boards with Phase 1.2+ firmware'); return; }
    localMac = parts[1] || null;
    if (handshakeTimer) { clearInterval(handshakeTimer); handshakeTimer = null; }
    applyName((() => { try { return localStorage.getItem(keyFor('name')) || activeContact; } catch { return activeContact; } })());
    loadHistory();
    setConnection('ready', localMac + ' · Protocol 2');
    updateByteCount();
    readUnread();
    return;
  }
  if (line.startsWith('QUEUED|')) {
    const [, session, id, hex] = line.split('|');
    const index = pendingLocal.findIndex(v => v.hex === hex);
    if (index >= 0) {
      const [item] = pendingLocal.splice(index, 1);
      const wid = uid(session, id);
      item.row.dataset.wireId = wid;
      sentMessages.set(wid, item.row);
      setMessageState(item.row, 'queued');
      updateHistoryMessage(item.hid, { wireId: wid, status: 'queued' });
    }
    return;
  }
  if (line.startsWith('RX|')) { const [, mac, session, id, hex] = line.split('|'); acceptReceived(mac, session, id, hex); return; }
  if (line.startsWith('READ|')) {
    const [, session, id] = line.split('|');
    setMessageState(sentMessages.get(uid(session, id)), 'read');
    return;
  }
  if (/^(SENT|DELIVERED|FAILED|RETRY|RADIO_ERROR)\|/.test(line)) {
    const [type, session, id, extra] = line.split('|');
    const row = sentMessages.get(uid(session, id));
    if (type === 'SENT') setMessageState(row, 'sent');
    if (type === 'DELIVERED') setMessageState(row, 'delivered');
    if (type === 'FAILED') setMessageState(row, 'failed');
    if (type === 'RADIO_ERROR') notify('ESP-NOW radio issue: ' + (extra || 'unknown'));
    return;
  }
  if (line.startsWith('ERROR|')) notify('ESP32: ' + line.substring(6));
}
function consume(bytes) {
  serialBuffer += decoder.decode(bytes, { stream: true });
  let end;
  while ((end = serialBuffer.indexOf('\n')) >= 0) {
    parseLine(serialBuffer.slice(0, end));
    serialBuffer = serialBuffer.slice(end + 1);
  }
  if (serialBuffer.length > 8192) serialBuffer = '';
}
async function readLoop() {
  try {
    while (port?.readable) {
      reader = port.readable.getReader();
      try {
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          if (value) consume(value);
        }
      } finally { reader.releaseLock(); reader = null; }
    }
  } catch (e) { if (!closing) notify('USB disconnected: ' + e.message); }
  finally { if (!closing) { readPromise = null; void disconnect(); } }
}
async function connect() {
  if (!(navigator.serial && 'requestPort' in navigator.serial)) { notify('Web Serial requires supported Chromium on localhost or HTTPS.'); return; }
  try {
    port = await navigator.serial.requestPort();
    await port.open({ baudRate: 115200 });
    writer = port.writable.getWriter();
    setConnection('waiting');
    readPromise = readLoop();
    await sendLine('STATUS');
    handshakeTimer = setInterval(() => { if (!verified && writer) sendLine('STATUS').catch(() => {}); }, 1200);
  } catch (e) { notify('Could not connect: ' + e.message); await disconnect(); }
}
async function disconnect() {
  if (closing) return; closing = true;
  try {
    if (handshakeTimer) { clearInterval(handshakeTimer); handshakeTimer = null; }
    if (reader) try { await reader.cancel(); } catch {}
    if (readPromise) try { await readPromise; } catch {}
    if (writer) { try { writer.releaseLock(); } catch {} writer = null; }
    if (port) { try { await port.close(); } catch {} port = null; }
  } finally {
    readPromise = null; serialBuffer = ''; verified = false; connected = false; pendingLocal.forEach(p => setMessageState(p.row, 'failed')); pendingLocal = [];
    for (const row of sentMessages.values()) if (['sending', 'queued', 'sent'].includes(row.dataset.status)) setMessageState(row, 'failed');
    unreadMessages.clear(); setConnection('offline'); closing = false;
  }
}
function sendText() {
  const input = $('messageInput');
  const text = input.value.trim();
  if (!text || !verified) return;
  const bytes = encoder.encode(text);
  if (bytes.length > MAX_BYTES) { notify(`Message uses ${bytes.length} bytes; limit ${MAX_BYTES}.`); return; }
  const hex = toHex(text);
  const hid = `out-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  const row = appendHistoryMessage({ hid, text, direction: 'out', status: 'sending', ts: Date.now() });
  const item = { hex, row, hid };
  pendingLocal.push(item);
  input.value = '';
  updateByteCount();
  sendLine('MSGHEX ' + hex).catch(e => {
    pendingLocal = pendingLocal.filter(v => v !== item);
    setMessageState(row, 'failed');
    notify('Could not send: ' + e.message);
  });
}
function updateByteCount() {
  const input = $('messageInput');
  const n = encoder.encode(input.value).length;
  $('byteCount').textContent = `${n}/${MAX_BYTES}`;
  $('byteCount').classList.toggle('over', n > MAX_BYTES);
  input.style.height = '36px'; input.style.height = Math.min(125, input.scrollHeight) + 'px';
  $('sendButton').disabled = !verified || !input.value.trim() || n > MAX_BYTES;
}
function applyName(name) {
  activeContact = name || 'Nearby node';
  $('contactSidebarName').textContent = activeContact;
  $('chatName').textContent = activeContact;
  const initial = activeContact[0]?.toUpperCase() || 'N';
  $('contactAvatar').textContent = initial; $('headerAvatar').textContent = initial;
}
function showDialog(title, render) {
  $('modalTitle').textContent = title;
  const body = $('modalBody'); body.replaceChildren();
  render(body); $('modalBackdrop').classList.remove('hidden');
}
function closeDialog() { $('modalBackdrop').classList.add('hidden'); }
function el(tag, props = {}, text) {
  const element = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (key === 'className') element.className = value; else element.setAttribute(key, value);
  }
  if (text !== undefined) element.textContent = text;
  return element;
}
function showRename() {
  showDialog('Contact name', body => {
    body.append(el('p', {}, 'Give this contact a name, just like a chat contact. The name is stored only in this browser for this ESP32 node.'));
    const label = el('label', { for: 'nameField' }, 'Display name');
    const field = el('input', { id: 'nameField', maxlength: '36', type: 'text' }); field.value = activeContact;
    const actions = el('div', { className: 'modal-actions' });
    const cancel = el('button', { className: 'quiet-button' }, 'Cancel');
    const save = el('button', { className: 'primary-button' }, 'Save name');
    cancel.onclick = closeDialog;
    save.onclick = () => {
      const name = field.value.trim(); if (!name) return;
      applyName(name); persistName(name); closeDialog();
    };
    field.addEventListener('keydown', e => { if (e.key === 'Enter') save.click(); });
    actions.append(cancel, save); body.append(label, field, actions);
    setTimeout(() => { field.focus(); field.select(); }, 30);
  });
}
function clearHistory() {
  chatHistory = [];
  $('messages').replaceChildren();
  saveHistory();
  updateSidebarFromHistory();
  setIntroVisibility();
  notify('Chat history cleared on this browser.');
}
function showInfo() {
  showDialog('Chat information', body => {
    const lines = [
      ['Peer name', activeContact],
      ['Your ESP32', localMac || 'Not connected'],
      ['Transport', 'USB Serial ↔ ESP-NOW'],
      ['Radio channel', '6'],
      ['Protocol', '2 + read receipts'],
      ['Encryption', 'NOT ACTIVE — test only'],
      ['History', 'Stored in this browser']
    ];
    lines.forEach(([k, v]) => {
      const row = el('div', { className: 'info-line' });
      row.append(el('span', {}, k), el('strong', {}, v));
      body.append(row);
    });
    body.append(el('p', {}, 'Single tick = sent to radio. Double grey tick = delivered to the remote browser. Double blue tick = the other side viewed the conversation.'));
  });
}
function showSettings() {
  showDialog('Settings', body => {
    body.append(el('p', {}, 'Phase 1.3 combines a polished UI with actual persistent chat history. Encryption, GPS, and voice will come in later phases.'));
    const rename = el('button', { className: 'primary-button' }, 'Rename contact');
    rename.onclick = () => showRename();
    const clear = el('button', { className: 'soft-button' }, 'Clear chat history');
    clear.onclick = () => { clearHistory(); closeDialog(); };
    const actions = el('div', { className: 'modal-actions' });
    actions.append(clear, rename);
    body.append(actions);
    body.append(el('p', {}, `App version ${APP_VERSION}. Read receipts work when the chat is open and visible. History is stored only in local browser storage for this node.`));
  });
}
$('connectButton').addEventListener('click', connect);
$('disconnectButton').addEventListener('click', disconnect);
$('sendButton').addEventListener('click', sendText);
$('messageInput').addEventListener('input', updateByteCount);
$('messageInput').addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendText(); } });
$('themeToggle').addEventListener('click', () => {
  theme = theme === 'dark' ? 'light' : 'dark';
  document.body.classList.toggle('light', theme === 'light');
  try { localStorage.setItem('offgrid_theme', theme); } catch {}
});
$('renameButton').addEventListener('click', showRename);
$('settingsButton').addEventListener('click', showSettings);
$('infoButton').addEventListener('click', showInfo);
$('contactButton').addEventListener('click', () => { document.body.classList.add('mobile-chat'); readUnread(); });
$('mobileBack').addEventListener('click', () => document.body.classList.remove('mobile-chat'));
$('closeModal').addEventListener('click', closeDialog);
$('modalBackdrop').addEventListener('click', e => { if (e.target === $('modalBackdrop')) closeDialog(); });
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeDialog(); });
document.addEventListener('visibilitychange', readUnread);
window.addEventListener('focus', readUnread);
window.addEventListener('beforeunload', () => { try { saveHistory(); } catch {} });
applyName('Nearby node');
setConnection('offline');
updateByteCount();
console.info(`OffGrid Messenger v${APP_VERSION}: Web Serial / ESP-NOW protocol 2 / persistent local history. Not encrypted.`);
