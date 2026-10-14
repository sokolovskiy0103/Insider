// One page for everyone. The host phone opens /host#<token> in the app's WebView,
// players open / from the QR code. The server decides what each one may see.
(function () {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (_) { return null; } },
    set(k, v) { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (_) {} },
  };

  const isHostPage = location.pathname.startsWith('/host');
  const token = isHostPage ? location.hash.slice(1) : '';
  const K = isHostPage ? 'insider.host.' : 'insider.';
  const app = $('#app');

  const ROLE = {
    MASTER: { name: 'Майстер', what: 'Ти знаєш слово. Відповідай на питання лише «так», «ні» або «не знаю». Коли хтось назве слово — натисни, хто вгадав.' },
    INSIDER: { name: 'Інсайдер', what: 'Ти теж знаєш слово. Непомітно підведи всіх до відповіді — і не видай себе.' },
    COMMON: { name: 'Гравець', what: 'Ставте Майстру питання «так/ні» і вгадайте слово. А потім знайдіть Інсайдера.' },
  };

  function eye(cls = 'eye') {
    return `<svg class="${cls}" viewBox="22 30 64 48" aria-hidden="true">
      <path fill="#f5b942" d="M26,54 C34,40 44,34 54,34 C64,34 74,40 82,54 C74,68 64,74 54,74 C44,74 34,68 26,54 Z"/>
      <path fill="#0e1320" d="M32,54 C39,44 46,40 54,40 C62,40 69,44 76,54 C69,64 62,68 54,68 C46,68 39,64 32,54 Z"/>
      <circle fill="#f5b942" cx="54" cy="54" r="12"/>
      <path fill="#0e1320" d="M54,47 C57,47 58.5,49 58.5,51.5 C58.5,53.2 57.6,54.4 56.4,55.1 L57.6,61 L50.4,61 L51.6,55.1 C50.4,54.4 49.5,53.2 49.5,51.5 C49.5,49 51,47 54,47 Z"/></svg>`;
  }
  $('#logoEye').innerHTML = eye();
  if (isHostPage) { $('#hostTag').hidden = false; $('#menuBtn').hidden = false; }

  // ---------- state ----------
  let pid = store.get(K + 'pid');
  let st = null;
  let joining = false;
  let clockOffset = 0;       // server time - local time
  let revealed = false;      // role card flipped open
  let roundKey = '';
  let lastPhase = '';
  let pickedGuesser = null;  // Master's two-step "who guessed"
  let menuOpen = false;
  let showInvite = false;
  let armed = null;          // two-tap confirmations

  // ---------- socket ----------
  let ws = null, tries = 0;
  function open() {
    const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws' + (isHostPage ? '?host=' + encodeURIComponent(token) : '');
    ws = new WebSocket(url);
    ws.onopen = () => { tries = 0; status(true); if (pid) send({ t: 'resume', pid }); else render(); };
    ws.onmessage = e => { let m; try { m = JSON.parse(e.data); } catch (_) { return; } onMessage(m); };
    ws.onclose = () => { status(false); setTimeout(open, Math.min(4000, 400 * ++tries)); };
    ws.onerror = () => { try { ws.close(); } catch (_) {} };
  }
  function send(o) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); }
  function status(ok) { $('#conn').innerHTML = `<span class="dot ${ok ? '' : 'off'}"></span>${ok ? '' : ' <span class="small muted">зв\'язок…</span>'}`; }
  document.addEventListener('visibilitychange', () => { if (!document.hidden && ws && ws.readyState > 1) open(); });
  open();

  function onMessage(m) {
    if (m.t === 'welcome') { pid = m.pid; store.set(K + 'pid', pid); joining = false; return; }
    if (m.t === 'unknown') { pid = null; store.set(K + 'pid', null); render(); return; }
    if (m.t !== 'state') return;
    clockOffset = m.now - Date.now();
    if (m.me && m.me.id !== pid) { pid = m.me.id; store.set(K + 'pid', pid); joining = false; }
    const key = m.round + ':' + (m.word || '') + ':' + (m.me && m.me.role);
    if (key !== roundKey && m.phase === 'ROLES') revealed = false;
    roundKey = key;
    if (m.phase !== lastPhase) {
      pickedGuesser = null; armed = null;
      if (lastPhase && !isHostPage && navigator.vibrate) { try { navigator.vibrate(120); } catch (_) {} }
      if (m.phase !== 'LOBBY') showInvite = false;
      lastPhase = m.phase;
    }
    st = m;
    render();
  }

  // ---------- helpers ----------
  const nameOf = id => esc(((st && st.players.find(p => p.id === id)) || {}).name || '?');
  const me = () => st && st.me;
  const myRole = () => me() && me().role;
  const isMaster = () => st && me() && st.master === me().id;
  const canRun = () => st && (st.isHost || isMaster());

  function remainingMs() {
    if (!st || !st.deadline) return 0;
    return Math.max(0, st.deadline - (Date.now() + clockOffset));
  }
  function fmt(ms) {
    const s = Math.ceil(ms / 1000);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }
  function timerHtml(total) {
    return `<div class="stack"><div class="timer" id="timer">${fmt(remainingMs())}</div>
      <div class="bar"><i id="timerBar" style="width:${total ? (remainingMs() / total * 100) : 0}%"></i></div></div>`;
  }
  let timerTotal = 0;
  setInterval(() => {
    const t = $('#timer'); if (!t) return;
    const ms = remainingMs();
    t.textContent = fmt(ms);
    t.classList.toggle('low', ms < 30000);
    const b = $('#timerBar'); if (b && timerTotal) b.style.width = (ms / timerTotal * 100) + '%';
  }, 250);

  function pluralPoints(n) {
    const a = n % 10, b = n % 100;
    if (a === 1 && b !== 11) return 'очко';
    if (a >= 2 && a <= 4 && (b < 12 || b > 14)) return 'очки';
    return 'очок';
  }

  // ---------- views ----------
  function joinView() {
    const name = store.get('insider.name') || '';
    return `<div class="stack" style="margin-top:${isHostPage ? 2 : 10}vh">
      ${isHostPage ? inviteCard() : ''}
      <h1>${isHostPage ? 'Твоє ім\'я в грі' : 'Заходь у гру'}</h1>
      <p class="muted">Майстер загадав слово. Вгадайте його питаннями «так/ні» — і знайдіть Інсайдера, який знав відповідь від початку.</p>
      <input id="name" type="text" maxlength="20" placeholder="Твоє ім'я" value="${esc(name)}" autocomplete="nickname">
      <button class="big" data-act="join" ${joining ? 'disabled' : ''}>Грати</button>
    </div>`;
  }

  function inviteCard() {
    const urls = (st && st.urls) || [];
    if (!urls.length) {
      return `<div class="card center stack"><h2>Немає Wi-Fi</h2>
        <p class="muted">Підключи телефон до Wi-Fi або увімкни точку доступу — гравці мають бути в тій самій мережі.</p></div>`;
    }
    const u = urls[0].url;
    return `<div class="card center stack">
      <h2>Скануйте, щоб приєднатися</h2>
      <img class="qr" src="/qr.svg?t=${encodeURIComponent(u)}" alt="QR">
      <div class="url">${esc(u)}</div>
      <p class="muted small">${urls[0].hotspot ? 'Гравці підключаються до точки доступу цього телефону.' : 'Гравці мають бути в тій самій Wi-Fi мережі.'}</p>
      ${urls.length > 1 ? `<div class="muted small">Інші адреси: ${urls.slice(1).map(x => esc(x.url)).join(', ')}</div>` : ''}
    </div>`;
  }

  function scoreboard({ kick = false } = {}) {
    const pts = (st.result && st.result.points) || {};
    const ps = [...st.players].sort((a, b) => b.score - a.score);
    return `<div class="card">${ps.map(p => `
      <div class="score">
        <span class="dot ${p.connected ? '' : 'off'}"></span>
        <span class="name">${esc(p.name)}${me() && me().id === p.id ? ' <span class="muted small">(ти)</span>' : ''}
          ${st.master === p.id && st.phase !== 'LOBBY' ? ' <span class="small c-MASTER">майстер</span>' : ''}</span>
        ${pts[p.id] ? `<span class="plus">+${pts[p.id]}</span>` : ''}
        <span class="pts">${p.score}</span>
        ${kick && !(me() && me().id === p.id) ? `<button class="mini ghost" data-act="kick" data-pid="${p.id}">✕</button>` : ''}
      </div>`).join('') || '<div class="muted">Поки нікого</div>'}
    </div>`;
  }

  function lobbyView() {
    const n = st.players.length;
    const enough = n >= st.minPlayers;
    let h = `<div class="stack">`;
    if (st.notice) h += `<div class="notice">${esc(st.notice)}</div>`;
    if (st.isHost) {
      h += inviteCard();
      const mins = [3, 4, 5, 7, 10];
      h += `<div class="card stack">
        <b>Час на вгадування</b>
        <div class="chips">${mins.map(m => `<button class="chip ${st.questionSeconds === m * 60 ? 'on' : ''}" data-act="seconds" data-v="${m * 60}">${m} хв</button>`).join('')}</div>
        <div class="muted small">Класика — 5 хв. Обговорення триває стільки ж, скільки йшло вгадування.</div>
      </div>`;
    } else {
      h += `<h1>Привіт, ${esc(me().name)}!</h1>
        <div class="card flat center"><span class="spinner"></span> <span class="muted">Чекаємо, поки хост почне гру</span></div>`;
    }
    h += `<h2>Гравці · ${n}</h2>${scoreboard({ kick: st.isHost })}`;
    if (st.isHost) {
      h += `<button class="big" data-act="start" ${enough ? '' : 'disabled'}>${st.round ? 'Наступний раунд' : 'Почати гру'}</button>`;
      if (!enough) h += `<div class="muted small center">Потрібно щонайменше ${st.minPlayers} гравці (зараз ${n}).</div>`;
    } else {
      h += `<button class="ghost" data-act="leave">Вийти з гри</button>`;
    }
    return h + `</div>`;
  }

  function roleCard(compact = false) {
    const r = myRole();
    if (!r) return `<div class="card flat center muted">Ти приєднався під час раунду — зіграєш у наступному.</div>`;
    const info = ROLE[r];
    if (!revealed) {
      return `<div class="role hidden" data-act="flip">
        <div style="font-size:40px">🂠</div>
        <div class="name">Твоя роль</div>
        <div class="what">Торкнись, щоб подивитися. Сховай екран від інших.</div>
      </div>`;
    }
    return `<div class="role ${r} pop" data-act="flip">
      <div class="name c-${r}">${info.name}</div>
      ${st.word && r !== 'COMMON' ? `<div class="muted small">Слово</div><div class="word">${esc(st.word)}</div>` : ''}
      ${compact ? '' : `<div class="what">${info.what}</div>`}
      <div class="muted small">Торкнись, щоб сховати</div>
    </div>`;
  }

  function rolesView() {
    const inRound = st.players.filter(p => p.inRound);
    const readyN = inRound.filter(p => p.ready).length;
    const meReady = me() && (st.players.find(p => p.id === me().id) || {}).ready;
    let h = `<div class="stack">
      <div class="muted center">Раунд ${st.round} · Майстер — <b class="c-MASTER">${nameOf(st.master)}</b></div>
      ${roleCard()}`;
    if (myRole() && !meReady) h += `<button class="soft big" data-act="ready" ${revealed ? '' : 'disabled'}>Запам'ятав(ла) ✓</button>`;
    h += `<div class="chips">${inRound.map(p => `<span class="chip ${p.ready ? 'ok' : ''}">${p.ready ? '✓ ' : ''}${esc(p.name)}</span>`).join('')}</div>
      <div class="muted small">Готові ${readyN} з ${inRound.length}</div>`;
    if (canRun()) {
      h += `<div class="row">
        ${isMaster() ? `<button class="ghost grow" data-act="swapWord">Інше слово</button>` : ''}
        <button class="grow" data-act="begin">Старт ⏱</button>
      </div>`;
      if (isMaster()) h += `<div class="muted small">Слово складне? Заміни його до старту — тоді Інсайдеру треба буде подивитися ще раз.</div>`;
    } else {
      h += `<div class="muted small center">Майстер ${nameOf(st.master)} запустить таймер, коли всі будуть готові.</div>`;
    }
    return h + `</div>`;
  }

  function questionsView() {
    timerTotal = st.questionSeconds * 1000;
    let h = `<div class="stack">
      <div class="center"><span class="tag">вгадуйте слово</span></div>
      ${timerHtml(timerTotal)}`;
    if (isMaster()) {
      h += `<div class="card center"><div class="muted small">Твоє слово</div><div class="word">${esc(st.word || '')}</div>
        <div class="muted small">Відповідай: так · ні · не знаю</div></div>
        <h2>Хто назвав слово?</h2>
        <div class="grid2">${st.players.filter(p => p.inRound && p.id !== st.master).map(p =>
          `<button class="pick ${pickedGuesser === p.id ? 'on' : ''}" data-act="pickGuesser" data-pid="${p.id}">${esc(p.name)}</button>`).join('')}</div>
        <button class="big" data-act="guessed" ${pickedGuesser ? '' : 'disabled'}>${pickedGuesser ? `${nameOf(pickedGuesser)} вгадав(ла)!` : 'Обери, хто вгадав'}</button>`;
    } else {
      h += `<div class="card center">Ставте питання Майстру <b class="c-MASTER">${nameOf(st.master)}</b>.<br><span class="muted small">Відповіді лише «так», «ні» або «не знаю».</span></div>`;
      h += roleCard(true);
      if (st.isHost) h += hostGuessPicker();
    }
    return h + `</div>`;
  }

  // Host fallback if the Master's phone is offline.
  function hostGuessPicker() {
    const master = st.players.find(p => p.id === st.master);
    if (master && master.connected) return '';
    return `<div class="card stack"><b>Майстер не на зв'язку — познач, хто вгадав</b>
      <div class="grid2">${st.players.filter(p => p.inRound && p.id !== st.master).map(p =>
        `<button class="pick ${pickedGuesser === p.id ? 'on' : ''}" data-act="pickGuesser" data-pid="${p.id}">${esc(p.name)}</button>`).join('')}</div>
      <button data-act="guessed" ${pickedGuesser ? '' : 'disabled'}>Вгадали</button></div>`;
  }

  function discussionView() {
    timerTotal = Math.max(st.elapsed, 30000);
    let h = `<div class="stack">
      <div class="card center stack">
        <div><b>${nameOf(st.guesser)}</b> вгадав(ла) слово</div>
        <div class="word">${esc(st.word || '')}</div>
        <div class="muted small">за ${fmt(st.elapsed)}</div>
      </div>
      <div class="center"><span class="tag">обговорення</span></div>
      ${timerHtml(timerTotal)}
      <div class="muted center">Хто з вас знав слово з самого початку? Обговоріть, хто міг вести всіх до відповіді.</div>`;
    if (canRun()) h += `<button class="big soft" data-act="endDiscussion">До голосування</button>`;
    h += roleCard(true);
    return h + `</div>`;
  }

  function votedChips() {
    const voters = new Set(st.voters || []);
    return `<div class="chips">${st.players.filter(p => voters.has(p.id)).map(p =>
      `<span class="chip ${p.voted ? 'ok' : ''}">${p.voted ? '✓ ' : ''}${esc(p.name)}</span>`).join('')}</div>`;
  }

  function vote1View() {
    const g = st.guesser;
    const iVote = me() && (st.voters || []).includes(me().id);
    let h = `<div class="stack">
      <div class="center"><span class="tag">голосування 1 з 2</span></div>
      <h1 class="center">${nameOf(g)} — Інсайдер?</h1>
      <p class="muted center">Якщо більшість скаже «так» — розкриваємо. Якщо «ні» — шукатимемо серед усіх.</p>`;
    if (me() && me().id === g) {
      h += `<div class="card flat center">Ти вгадав(ла) слово — зараз голосують про тебе. Переконай їх 😉</div>`;
    } else if (iVote) {
      const v = st.myVote;
      h += `<div class="grid2">
        <button class="yes ${v === true ? 'pop' : ''}" data-act="vote1" data-yes="1" ${v === true ? 'style="outline:3px solid #fff"' : ''}>Так, це Інсайдер</button>
        <button class="no ${v === false ? 'pop' : ''}" data-act="vote1" data-yes="0" ${v === false ? 'style="outline:3px solid #fff"' : ''}>Ні, не він(вона)</button>
      </div>
      ${v !== undefined ? '<div class="muted small center">Голос прийнято. Можна змінити, поки не проголосували всі.</div>' : ''}`;
    }
    h += votedChips();
    if (st.isHost) h += `<button class="ghost" data-act="closeVote1">Завершити голосування</button>`;
    return h + `</div>`;
  }

  function vote2View() {
    const cands = st.candidates || [];
    let h = `<div class="stack">
      <div class="center"><span class="tag">голосування 2 з 2</span></div>
      <h1 class="center">Хто Інсайдер?</h1>
      <p class="muted center">Кожен вказує на одного. За рівних голосів вирішує вибір Майстра.</p>`;
    if (me() && (st.voters || []).includes(me().id)) {
      h += `<div class="grid2">${cands.filter(id => id !== me().id).map(id =>
        `<button class="pick ${st.myVote === id ? 'on' : ''}" data-act="vote2" data-pid="${id}">${nameOf(id)}${id === st.guesser ? ' <span class="muted small">вгадав</span>' : ''}</button>`).join('')}</div>`;
      if (st.myVote) h += `<div class="muted small center">Голос прийнято. Можна змінити, поки не проголосували всі.</div>`;
    }
    h += votedChips();
    if (st.isHost) h += `<button class="ghost" data-act="closeVote2">Завершити голосування</button>`;
    return h + `</div>`;
  }

  function resultView() {
    const r = st.result || {};
    const role = myRole();
    let cls = 'meh', title = 'Ніхто не переміг';
    if (r.outcome === 'CAUGHT') { title = 'Інсайдера спіймано!'; cls = role === 'INSIDER' ? 'bad' : 'good'; }
    if (r.outcome === 'INSIDER_WINS') { title = 'Інсайдер переміг'; cls = role === 'INSIDER' ? 'good' : 'bad'; }
    if (!role) cls = 'meh';
    let h = `<div class="stack">
      <div class="banner ${cls} pop">${eye()}<h1>${title}</h1><div>${esc(r.note || '')}</div></div>
      <div class="card center stack">
        <div>Слово: <span class="word small-word">${esc(st.word || '')}</span></div>
        <div>Інсайдер: <b class="c-INSIDER">${nameOf(st.insider)}</b> · Майстер: <b class="c-MASTER">${nameOf(st.master)}</b></div>
        ${voteSummary(r)}
      </div>
      <h2>Рахунок</h2>${scoreboard({ kick: st.isHost })}
      <div class="muted small">Перемога гравців: +1 кожному (і Майстру). Перемога Інсайдера: +3 йому. Не вгадали слово — нікому.</div>`;
    if (st.isHost) h += `<button class="big" data-act="next" ${st.players.length >= st.minPlayers ? '' : 'disabled'}>Наступний раунд</button>`;
    else h += `<div class="muted center small">Хост запустить наступний раунд.</div>`;
    return h + `</div>`;
  }

  function voteSummary(r) {
    if (!r || r.outcome === 'NOT_GUESSED') return '';
    const v1 = r.vote1 || {};
    let s = `<div class="muted small">Голосування 1: «так» ${v1.yes || 0}, «ні» ${v1.no || 0}</div>`;
    if (r.decidedBy === 'vote2') {
      const t = r.vote2 || {};
      const parts = Object.keys(t).sort((a, b) => t[b] - t[a]).map(id => `${nameOf(id)} — ${t[id]}`);
      s += `<div class="muted small">Голосування 2: ${parts.join(', ') || 'ніхто не проголосував'}</div>`;
    }
    return s;
  }

  function menuView() {
    if (!st || !st.isHost) return '';
    const inGame = st.phase !== 'LOBBY';
    const conf = (act, label, cls = 'soft') => armed === act
      ? `<button class="danger" data-act="${act}">Точно? ${label}</button>`
      : `<button class="${cls}" data-act="arm" data-v="${act}">${label}</button>`;
    return `${inGame ? `<button class="soft" data-act="invite">${showInvite ? 'Сховати QR' : 'Показати QR для нових гравців'}</button>` : ''}
      ${inGame ? conf('abort', 'Перервати раунд') : ''}
      ${conf('newGame', 'Нова гра (обнулити рахунок)')}
      <div class="muted small">Щоб повністю зупинити сервер — «Завершити гру» у сповіщенні.</div>`;
  }

  function render() {
    const menu = $('#menu');
    menu.hidden = !(menuOpen && st && st.isHost);
    if (!menu.hidden) menu.innerHTML = menuView();

    if (!pid || !me()) {
      if (pid && !st) return; // waiting for state after resume
      keepInput(joinView());
      return;
    }
    let h = '';
    if (showInvite && st.isHost) h += inviteCard() + '<div style="height:14px"></div>';
    switch (st.phase) {
      case 'LOBBY': h += lobbyView(); break;
      case 'ROLES': h += rolesView(); break;
      case 'QUESTIONS': h += questionsView(); break;
      case 'DISCUSSION': h += discussionView(); break;
      case 'VOTE1': h += vote1View(); break;
      case 'VOTE2': h += vote2View(); break;
      case 'RESULT': h += resultView(); break;
    }
    keepInput(h);
  }

  /** Re-render without losing what the user is typing. */
  function keepInput(html) {
    const a = document.activeElement;
    const saved = {};
    app.querySelectorAll('input[id]').forEach(i => { saved[i.id] = i.value; });
    const focusId = a && a.id && a.tagName === 'INPUT' ? a.id : null;
    app.innerHTML = html;
    for (const id in saved) { const el = document.getElementById(id); if (el) el.value = saved[id]; }
    if (focusId) { const el = document.getElementById(focusId); if (el) el.focus(); }
  }

  // ---------- actions ----------
  function join() {
    const name = ($('#name') || {}).value || '';
    if (!name.trim()) { $('#name').focus(); return; }
    store.set('insider.name', name.trim());
    joining = true;
    send({ t: 'join', name: name.trim() });
    render();
  }

  document.addEventListener('keydown', e => { if (e.key === 'Enter' && e.target.id === 'name') join(); });

  $('#menuBtn').addEventListener('click', () => { menuOpen = !menuOpen; armed = null; render(); });

  document.addEventListener('click', e => {
    const el = e.target.closest('[data-act]');
    if (!el || el.disabled) return;
    const act = el.dataset.act;
    switch (act) {
      case 'join': return join();
      case 'flip': revealed = !revealed; return render();
      case 'leave': send({ t: 'leave' }); pid = null; store.set(K + 'pid', null); return render();
      case 'kick': return send({ t: 'kick', pid: el.dataset.pid });
      case 'seconds': return send({ t: 'seconds', seconds: +el.dataset.v });
      case 'start': case 'next': revealed = false; return send({ t: 'start' });
      case 'ready': return send({ t: 'ready' });
      case 'swapWord': revealed = true; return send({ t: 'swapWord' });
      case 'begin': return send({ t: 'begin' });
      case 'pickGuesser': pickedGuesser = el.dataset.pid; return render();
      case 'guessed': return send({ t: 'guessed', pid: pickedGuesser });
      case 'endDiscussion': return send({ t: 'endDiscussion' });
      case 'vote1': return send({ t: 'vote1', yes: el.dataset.yes === '1' });
      case 'vote2': return send({ t: 'vote2', target: el.dataset.pid });
      case 'closeVote1': return send({ t: 'closeVote1' });
      case 'closeVote2': return send({ t: 'closeVote2' });
      case 'invite': showInvite = !showInvite; menuOpen = false; return render();
      case 'arm': armed = el.dataset.v; return render();
      case 'abort': case 'newGame': armed = null; menuOpen = false; send({ t: act }); return render();
    }
  });
})();
