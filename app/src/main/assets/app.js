/* ===== DDTV Android — UI 逻辑 v0.7.33 =====
   结构:工具 → 图标 → 状态 → 主题 → 弹层 → 布局 → 各视图渲染 → 原生回调 → 操作 → 初始化
   模板规则:禁止内联样式(动态数据除外),一律用 app.css 里的类;图标用 ic() */
'use strict';

/* ========== 工具 ========== */
const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmtSize = b => { if(!b) return '0 B'; const u=['B','KB','MB','GB','TB']; let i=0; while(b>=1024&&i<u.length-1){b/=1024;i++;} return b.toFixed(i?1:0)+' '+u[i]; };
const fmtSpeed = b => b ? fmtSize(b)+'/s' : '';
const fmtTime = t => { const d=new Date(t); if(isNaN(d.getTime())) return '--:--:--'; return ('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2)+':'+('0'+d.getSeconds()).slice(-2); };
const fmtDur = ms => { if(!ms||ms<0) return '00:00:00'; const s=Math.floor(ms/1000); return ('0'+Math.floor(s/3600)).slice(-2)+':'+('0'+Math.floor(s%3600/60)).slice(-2)+':'+('0'+s%60).slice(-2); };
const fmtDate = t => { const d=new Date(t); return d.getFullYear()+'-'+('0'+(d.getMonth()+1)).slice(-2)+'-'+('0'+d.getDate()).slice(-2); };
const qnLabels = { 30000:'杜比',20000:'4K',10000:'原画',400:'蓝光',250:'超清',150:'高清',80:'流畅' };

/** B站图片 URL 统一转 https（WebView 拦截 http 混合内容，参照 BBDownAndroid face.replace(/^http:\/\//,'https://')） */
const imgUrl = u => (u||'').replace(/^http:\/\//, 'https://');
/** 头像 HTML：face 为空/加载失败时显示首字头像（粉底白字），不再空白 */
function avatarHtml(face, name) {
  const ch = esc((name||'?').trim().charAt(0) || '?');
  return `<div class="avatar-box"><div class="avatar-ph avatar-ph-text">${ch}</div>` +
    (face ? `<img class="avatar-img" src="${esc(imgUrl(face))}" referrerpolicy="no-referrer" onerror="this.style.display='none'">` : '') +
    '</div>';
}

/* ========== 图标(统一 SVG 集,禁止 emoji) ========== */
const ICONS = {
  plus:'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  play:'M8 5v14l11-7z',
  stop:'M6 6h12v12H6z',
  refresh:'M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
  trash:'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
  film:'M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z',
  share:'M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z',
  eye:'M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z',
  record:'M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z',
  music:'M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z',
  home:'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
  user:'M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0 2c-5 0-9 2.5-9 6v2h18v-2c0-3.5-4-6-9-6z',
  monitor:'M20 3H4c-1.1 0-2 .9-2 2v11c0 1.1.9 2 2 2h6v2H8v2h8v-2h-2v-2h6c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 13H4V5h16v11z',
  close:'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  send:'M2.01 21L23 12 2.01 3 2 10l15 2-15 2z',
  check:'M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
  search:'M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
  file:'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z',
  folder:'M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z',
  bolt:'M11 21h-1l1-7H7.5c-.58 0-.57-.32-.38-.66.19-.34.05-.08.07-.12C8.48 10.94 10.42 7.54 13 3h1l-1 7h3.5c.49 0 .56.33.47.51l-.07.15C12.96 17.55 11 21 11 21z',
  wrench:'M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0l2.3-2.3c.5-.4.5-1.1.1-1.4z',
  heart:'M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z',
  back:'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
  globe:'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93C7.05 19.44 4 16.08 4 12c0-.61.08-1.21.21-1.78L9 15v1c0 1.1.9 2 2 2v1.93zM11 2.07v2.02c-1.87.4-3.5 1.44-4.6 2.83L8 9l-4 1.5c-.07.33-.11.67-.11 1L8 9.5 10 12l-2 4-2.5-1.5c-.25.5-.5 1-.5 1.5 0 1.66 1.34 3 3 3s3-1.34 3-3c0-.46-.1-.89-.26-1.28L12 13l3 2-2 4c1.2-.38 2.3-1.04 3.15-1.9l-1.15-2.6L17 12l3 3c.55-1.14.87-2.43.87-3.8C20.87 6.1 17.2 2.2 12.5 2.07H12z',
  tv:'M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h7v2H8v2h8v-2h-2v-2h7c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z',
  clock:'M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z',
  save:'M17 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7l-4-4zm-5 16a3 3 0 1 1 0-6 3 3 0 0 1 0 6zm3-10H5V5h10v4z',
  alert:'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z'
};
function ic(name, size=16) {
  const d = ICONS[name] || '';
  return `<svg viewBox="0 0 24 24" width="${size}" height="${size}" aria-hidden="true"><path fill="currentColor" d="${d}"/></svg>`;
}

/* ========== 状态 ========== */
const state = { view:'explorer', rooms:[], settings:null, account:null,
  currentRoom:null, danmakuRoom:null, followGroups:[], followUsers:[],
  followGroupId:null, files:[], qrImage:null, history:[], stats:null,
  theme:'system', listen:{active:false,playing:false,roomId:0,name:''}, _loginView:'main', _authTarget:null };

/* 二级页返回头（移植 BBDownAndroid subHeader，复用 tabs 栏） */
let _subnavKey = '';
function subHeader(title, onclickExpr) {
  const key = title + '|' + onclickExpr;
  if (key !== _subnavKey) {
    _subnavKey = key;
    const bar = $('#tabs');
    if (bar) bar.innerHTML = `<button class="sn-back" onclick="${onclickExpr}"><svg viewBox="0 0 24 24" width="14" height="14"><path fill="currentColor" d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg><span>返回</span></button><span class="sn-title">${esc(title)}</span>`;
  }
  return '';  // 模板插值占位:副作用写入 #tabs,必须返回空串,否则页面顶部出现字面 "undefined"
}
function clearSubnav() {
  if (_subnavKey !== '') {
    _subnavKey = '';
    const bar = $('#tabs');
    if (bar) bar.innerHTML = '';
  }
}

/* 加载动画（移植 BBDownAndroid spinIcon） */
function spinIcon() {
  return '<svg class="spin" viewBox="0 0 24 24" width="16" height="16"><path fill="#FB7299" d="M12 2a10 10 0 1 0 10 10h-3a7 7 0 1 1-7-7V2z"/></svg>';
}
function qrLoadingHtml() {
  return `<div class="loading-pulse">${spinIcon()}<br><span class="lp-tip">生成二维码中…</span></div>`;
}

/* ========== 主题 ========== */
function applyTheme(theme) {
  state.theme = theme;
  const dark = theme === 'dark' || (theme === 'system' && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
  const icon = $('#btnTheme svg');
  if (icon) {
    icon.innerHTML = theme === 'system'
      ? '<path fill="currentColor" d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 2v16a8 8 0 0 1 0-16z"/>'
      : (dark ? '<path fill="currentColor" d="M12 3a9 9 0 1 0 9 9c0-.46-.04-.92-.1-1.36a5.389 5.389 0 0 1-4.4 2.26 5.403 5.403 0 0 1-3.14-9.8c-.44-.06-.9-.1-1.36-.1z"/>'
        : '<path fill="currentColor" d="M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zM1 13h2.5a1 1 0 0 0 0-2H1a1 1 0 0 0 0 2zm19.5 0H23a1 1 0 0 0 0-2h-2.5a1 1 0 0 0 0 2zM11 1v2.5a1 1 0 0 0 2 0V1a1 1 0 0 0-2 0zm0 19.5V23a1 1 0 0 0 2 0v-2.5a1 1 0 0 0-2 0zM5.28 3.87a1 1 0 0 0-1.41 1.41l1.24 1.24a1 1 0 0 0 1.41-1.41L5.28 3.87zm11.6 11.6a1 1 0 0 0-1.41 1.41l1.24 1.24a1 1 0 0 0 1.41-1.41l-1.24-1.24zm3.25-11.6l-1.24 1.24a1 1 0 0 0 1.41 1.41l1.24-1.24a1 1 0 0 0-1.41-1.41zM6.52 15.47l-1.24 1.24a1 1 0 0 0 1.41 1.41l1.24-1.24a1 1 0 0 0-1.41-1.41z"/>');
  }
}
function cycleTheme() {
  const order = ['dark','light','system'];
  const next = order[(order.indexOf(state.theme)+1) % order.length];
  try { AndroidBridge.setTheme(next); } catch(e){}
  try { localStorage.setItem('theme', next); } catch(e){}
  applyTheme(next);
  toast(next==='dark'?'深色模式':next==='light'?'浅色模式':'跟随系统','ok');
}

/* ========== Toast ========== */
let _toastTimer = null;
function toast(msg, type) {
  const el = $('#toast'); el.textContent = msg; el.className = 'toast show ' + (type||'ok');
  clearTimeout(_toastTimer); _toastTimer = setTimeout(() => el.className = 'toast hidden', 2400);
}

/* ========== 模态框(自绘,替代原生 prompt/confirm) ========== */
let _modalCb = null;
function showModal(opts) {
  $('#modalTitle').textContent = opts.title || '提示';
  $('#modalMsg').textContent = opts.msg || '';
  const inp = $('#modalInput');
  if (opts.input) {
    inp.classList.remove('hidden-input');
    inp.placeholder = opts.placeholder || '';
    inp.value = opts.defaultValue || '';
  } else {
    inp.classList.add('hidden-input');
    inp.value = '';
  }
  $('#modalOk').textContent = opts.okText || '确定';
  _modalCb = opts.onOk || null;
  $('#modal').classList.remove('hidden');
  if (opts.input) setTimeout(() => inp.focus(), 120);
}
function closeModal() {
  $('#modal').classList.add('hidden');
  _modalCb = null;
}
(function initModal() {
  $('#modalOk').addEventListener('click', () => {
    const cb = _modalCb, val = $('#modalInput').value.trim();
    closeModal(); if (cb) cb(val);
  });
  $('#modalCancel').addEventListener('click', closeModal);
  $('#modal').addEventListener('click', e => { if (e.target === $('#modal')) closeModal(); });
  $('#modalInput').addEventListener('keydown', e => { if (e.key === 'Enter') $('#modalOk').click(); });
})();

/* ========== 自定义下拉(替代原生 select) ========== */
// 全局唯一的外部点击关闭监听（委托，避免每个下拉累积 document 监听器泄漏）
document.addEventListener('click', e => {
  document.querySelectorAll('.cselect.open').forEach(c => {
    if (!c.contains(e.target)) c.classList.remove('open');
  });
});
function renderCSelect(el, options, value, onChange) {
  el.classList.add('cselect');
  el.innerHTML = `<button type="button" class="cs-btn"><span class="cs-label"></span>
    <svg class="cs-chev" viewBox="0 0 24 24"><path fill="currentColor" d="M7 10l5 5 5-5z"/></svg></button>
    <div class="cs-menu"></div>`;
  const btn = el.querySelector('.cs-btn');
  const menu = el.querySelector('.cs-menu');
  const label = el.querySelector('.cs-label');
  const update = v => {
    const opt = options.find(o => String(o.v) === String(v));
    el.dataset.value = opt ? String(opt.v) : '';
    label.textContent = opt ? opt.label : '';
    menu.querySelectorAll('.cs-opt').forEach(o =>
      o.classList.toggle('selected', String(o.dataset.v) === String(el.dataset.value)));
  };
  menu.innerHTML = options.map(o =>
    `<div class="cs-opt" data-v="${esc(String(o.v))}">${esc(o.label)}</div>`).join('');
  btn.addEventListener('click', e => {
    e.stopPropagation();
    const willOpen = !el.classList.contains('open');
    document.querySelectorAll('.cselect.open').forEach(c => c.classList.remove('open'));
    el.classList.toggle('open', willOpen);
    if (willOpen) {
      // 打开后检查菜单是否超出视口（触发器靠右时左对齐会右溢），超出则改为右对齐
      requestAnimationFrame(() => {
        if (!el.classList.contains('open')) return;
        const m = el.querySelector('.cs-menu');
        const r = m.getBoundingClientRect();
        if (r.right > window.innerWidth) { m.style.left = 'auto'; m.style.right = '0'; }
        else if (r.left < 0) { m.style.left = '0'; m.style.right = 'auto'; }
      });
    }
  });
  menu.querySelectorAll('.cs-opt').forEach(o => {
    o.addEventListener('click', e => {
      e.stopPropagation();
      el.classList.remove('open');
      update(o.dataset.v);
      if (onChange) onChange(o.dataset.v);
    });
  });
  update(value);
}

/* ========== 布局 ========== */
function updateLayout() {
  document.getElementById('app').classList.toggle('layout-narrow', window.innerWidth <= 991);
}
window.addEventListener('resize', updateLayout);

const VIEW_TITLES = { explorer:'直播监控', danmaku:'弹幕', files:'录制文件', data:'数据统计', history:'录制历史', tools:'修复工具', account:'账号管理', settings:'设置', log:'运行日志' };

function switchView(view) {
  if (view === state.view) {
    // 点击当前活动图标：重新渲染当前视图（等价手动刷新，给用户反馈）
    renderSidebar(); renderEditor();
    return;
  }
  state.view = view;
  try { localStorage.setItem('ddtv_view', view); } catch(e){}
  state._roomsRendered = false;  // 主动切视图：列表重新播放入场动画
  state._filesRendered = false;
  document.querySelectorAll('.ab-btn').forEach(b => b.classList.toggle('active', b.dataset.view === view));
  $('#sidebarTitle').textContent = VIEW_TITLES[view] || '';
  $('#btnAddRoom').style.display = view === 'explorer' ? '' : 'none';
  $('#btnSearchRoom').style.display = view === 'explorer' ? '' : 'none';
  renderSidebar(); renderEditor();
}

/* ========== 列表构建（宽屏侧边栏与窄屏编辑器共用） ========== */
function buildRoomListHtml() {
  const live = state.rooms.filter(r => r.liveStatus===1||r.liveStatus===2);
  const off = state.rooms.filter(r => !(r.liveStatus===1||r.liveStatus===2));
  if (!state.rooms.length) return '<div class="sb-empty">暂无监控房间<br>点击右上角 ＋ 添加</div>';
  return [...live,...off].map((r,i) => {
    const isLive = r.liveStatus===1||r.liveStatus===2;
    const rec = r.recState==='recording';
    const cover = r.cover || (!isLive ? r.face : '');
    const hasCover = cover && cover.length>0;
    const sel = state.currentRoom===r.roomId ? ' selected':'';
    const dotCls = r.liveStatus===1?'live':r.liveStatus===2?'round':'offline';
    const status = r.liveStatus===1?'直播中':r.liveStatus===2?'轮播中':'未开播';
    const pop = isLive ? ` · 人气${r.livePopularity||r.popularity||0}` : '';
    const speed = rec && r.recSpeed ? ` · ${fmtSpeed(r.recSpeed)}` : '';
    return `<div class="task-item${sel}${hasCover?' has-cover':''}" data-rid="${r.roomId}">
      ${hasCover?`<img class="ti-bg" src="${esc(imgUrl(cover))}" referrerpolicy="no-referrer" onerror="this.parentElement.classList.remove('has-cover');this.remove()">`:''}
      <div class="ti-shade"></div>
      <div class="ti-index">#${i+1}</div>
      ${rec?'<span class="ti-rec">REC</span>':''}
      <div class="ti-body">
        <div class="ti-title">${esc(r.name||'房间 '+r.roomId)}</div>
        <div class="ti-sub"><span class="live-dot ${dotCls}"></span>${status}${pop}${speed}</div>
        <div class="ti-sub2">${esc(r.title||'')}</div>
      </div></div>`;
  }).join('');
}
function bindRoomList(container) {
  container.querySelectorAll('.task-item').forEach(el => {
    const rid = Number(el.dataset.rid);
    el.onclick = () => {
      // 长按刚触发过：吞掉随后浏览器派发的 click，避免误进详情
      if (state._longPressFired) { state._longPressFired = false; return; }
      hideRoomMenu();
      state.currentRoom = rid; state._detailOpen = true; renderSidebar(); renderEditor();
    };
    // 长按 → 管理菜单（触摸与鼠标双支持）
    el.addEventListener('touchstart', e => longPressStart(el, rid, e.touches[0].clientX, e.touches[0].clientY), { passive: true });
    el.addEventListener('touchmove', e => longPressMove(e.touches[0].clientX, e.touches[0].clientY), { passive: true });
    el.addEventListener('touchend', () => longPressCancel(false), { passive: true });
    el.addEventListener('touchcancel', () => longPressCancel(true), { passive: true });
    el.addEventListener('mousedown', e => { if (e.button === 0) longPressStart(el, rid, e.clientX, e.clientY); });
    el.addEventListener('mouseup', () => longPressCancel(false));
    el.addEventListener('mouseleave', () => longPressCancel(true));
    // 拦截系统长按菜单（Android WebView 默认 contextmenu，避免与我们自己的菜单叠加）
    el.addEventListener('contextmenu', e => e.preventDefault());
  });
}

/* ========== 长按管理菜单（直播间卡片） ========== */
let _lpTimer = null, _lpEl = null, _lpX0 = 0, _lpY0 = 0;
/* 长按位移阈值：手指轻微抖动(<12px)不算移动，避免长按菜单“有时候出不来” */
const LP_MOVE_PX = 12;
/* 历史卡片长按 → 管理菜单 */
function bindHistoryLongPress(el, h, idx, isMonitored, x, y) {
  longPressCancel(true);
  state._longPressFired = false;
  _lpEl = el;
  _lpX0 = x; _lpY0 = y;
  _lpTimer = setTimeout(() => {
    _lpTimer = null;
    state._longPressFired = true;
    el.classList.add('long-pressed');
    showHistoryMenu(h, idx, isMonitored, x, y);
  }, 550);
}
function showHistoryMenu(h, idx, isMonitored, x, y) {
  let m = $('#ctxMenu');
  if (!m) {
    m = document.createElement('div');
    m.id = 'ctxMenu';
    m.className = 'ctx-menu hidden';
    document.body.appendChild(m);
  }
  m.innerHTML =
    `<div class="ctx-title">${esc(h.name||'房间 '+h.roomId)}</div>` +
    (isMonitored ? '' : `<div class="ctx-item" data-act="add">加入监控</div>`) +
    `<div class="ctx-item" data-act="live">查看直播</div>` +
    `<div class="ctx-item ctx-danger" data-act="del">删除记录</div>`;
  m.querySelectorAll('.ctx-item').forEach(it => it.onclick = () => {
    hideRoomMenu();
    state._longPressFired = false;
    const act = it.dataset.act;
    if (act === 'live') openHistoryRoom(h.roomId);
    else if (act === 'add') { try { AndroidBridge.addRoom(String(h.roomId)); toast('已加入监控'); } catch(e){ toast('添加失败: '+e,'err'); } }
    else if (act === 'del') {
      showModal({ title:'删除记录', msg:'确认删除这条录制历史吗？\n只删除记录，不影响已录制的文件。',
        okText:'删除', onOk:()=>{ const r=JSON.parse(AndroidBridge.deleteHistory(idx)); toast(r.msg||'已删除', r.code<0?'err':'ok'); loadHistory(); renderHistoryPanel(); } });
    }
  });
  m.classList.remove('hidden');
  const mw = m.offsetWidth || 172, mh = m.offsetHeight || 200;
  m.style.left = Math.max(8, Math.min(x, innerWidth - mw - 8)) + 'px';
  m.style.top = Math.max(8, Math.min(y, innerHeight - mh - 8)) + 'px';
}
function longPressStart(el, rid, x, y) {
  longPressCancel(true);
  state._longPressFired = false;
  _lpEl = el;
  _lpX0 = x; _lpY0 = y;
  _lpTimer = setTimeout(() => {
    _lpTimer = null;
    state._longPressFired = true;
    el.classList.add('long-pressed');
    showRoomMenu(rid, x, y);
  }, 550);
}
/* touchmove 带位移判断：超过阈值才算“移动”取消长按；轻微抖动保留 */
function longPressMove(x, y) {
  if (!_lpEl) return;
  if (Math.abs(x - _lpX0) > LP_MOVE_PX || Math.abs(y - _lpY0) > LP_MOVE_PX) longPressCancel(true);
}
function longPressCancel(moved) {
  if (_lpTimer) { clearTimeout(_lpTimer); _lpTimer = null; }
  if (_lpEl) { _lpEl.classList.remove('long-pressed'); _lpEl = null; }
}
function showRoomMenu(rid, x, y) {
  const r = (state.rooms||[]).find(xx => xx.roomId === rid);
  if (!r) return;
  let m = $('#ctxMenu');
  if (!m) {
    m = document.createElement('div');
    m.id = 'ctxMenu';
    m.className = 'ctx-menu hidden';
    document.body.appendChild(m);
  }
  const rec = r.recState === 'recording';
  m.innerHTML =
    `<div class="ctx-title">${esc(r.name||'房间 '+rid)}</div>` +
    `<div class="ctx-item" data-act="record">${rec?'停止录制':'立即录制'}</div>` +
    `<div class="ctx-item" data-act="audio">${r.audioOnly?'✓ 仅录音频（开）':'仅录音频（关）'}</div>` +
    `<div class="ctx-item" data-act="live">观看直播</div>` +
    `<div class="ctx-item" data-act="listen">${isListening(rid)?'停止收听':'听直播'}</div>` +
    `<div class="ctx-item" data-act="open">打开详情</div>` +
    `<div class="ctx-item ctx-danger" data-act="remove">移除房间</div>`;
  m.querySelectorAll('.ctx-item').forEach(it => it.onclick = () => {
    hideRoomMenu();
    state._longPressFired = false;  // 菜单操作消费了长按语义，恢复后续单击
    const act = it.dataset.act;
    if (act === 'record') (rec ? stopRec : startRec)(rid);
    else if (act === 'audio') {
      const on = !r.audioOnly;
      try { AndroidBridge.setAudioOnly(rid, on); } catch(e){}
      r.audioOnly = on;
      toast(on ? '已开启仅录音频（下次录制生效）' : '已关闭仅录音频', 'ok');
      refreshRooms();
    }
    else if (act === 'live') openLive(rid);
    else if (act === 'listen') toggleListen(rid);
    else if (act === 'open') { state.currentRoom = rid; state._detailOpen = true; renderSidebar(); renderEditor(); }
    else if (act === 'remove') removeRoom(rid);
  });
  m.classList.remove('hidden');
  // 定位：优先跟随触发点，并限制在视口内
  const mw = m.offsetWidth || 172, mh = m.offsetHeight || 200;
  m.style.left = Math.max(8, Math.min(x, innerWidth - mw - 8)) + 'px';
  m.style.top = Math.max(8, Math.min(y, innerHeight - mh - 8)) + 'px';
}
function hideRoomMenu() {
  const m = $('#ctxMenu');
  if (m && !m.classList.contains('hidden')) m.classList.add('hidden');
}
// 点菜单外任意处关闭（capture 阶段先于菜单项处理）
document.addEventListener('click', () => hideRoomMenu(), true);
/* 房间封面映射：录制文件按 uploader 找监控房间的封面/头像，缺省回退占位 */
function roomCoverOf(uploader) {
  if (!uploader || !state.rooms) return '';
  const r = state.rooms.find(x => x.name === uploader);
  return r ? (r.cover || r.face || '') : '';
}
/* 变体归并：原始/转封装/修复/转码视为同一录制，取最优可播版本 */
function bestFileVariants(list) {
  const rank = f => f.name.endsWith('_transcoded.mp4') ? 3 : f.name.endsWith('_repaired.mp4') ? 2 : f.name.endsWith('.mp4') ? 1 : 0;
  const key = f => f.name.replace(/\.(flv|mp4)$/i, '').replace(/_(repaired|transcoded)$/i, '');
  const groups = new Map();
  list.forEach(f => { const k = key(f); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(f); });
  return [...groups.values()].map(g => {
    const best = g.reduce((a, b) => (rank(b) > rank(a) ? b : a));
    // 徽标只标注“相对同组变体”的加工状态：单文件不推断来源
    best._badge = rank(best) === 3 ? '已转码' : rank(best) === 2 ? '已修复'
      : (g.length > 1 && best.name.endsWith('.mp4')) ? '已转封装' : '';
    return best;
  });
}
function filesSignature(list) { return list.map(f => f.name + '|' + f.size + '|' + f.mtime).join(';'); }
/* 文件卡片 HTML（列表/侧边栏/工具共用；封面失败回退占位） */
function fileCardHtml(f, metaExtra) {
  // 优先本地封面（录制时保存的 _cover.jpg，content:// URI）；其次监控房间封面；再回退占位
  const cover = f.coverPath || roomCoverOf(f.uploader);
  const fmt = f.isFlv ? 'FLV' : f.isAudio ? '音频' : 'MP4';
  return `<div class="vc-item" data-path="${esc(f.path)}">
    <div class="vc-cover-wrap${cover ? ' has-img' : ''}">
      ${cover ? `<img class="vc-cover" src="${esc(cover)}" referrerpolicy="no-referrer" alt="" onerror="this.parentElement.classList.remove('has-img')">` : ''}
      <div class="vc-cover vc-cover-ph">${ic(f.isAudio ? 'music' : 'film', 20)}</div>
      <span class="vc-fmt">${fmt}</span>
    </div>
    <div class="vc-body">
      <div class="vc-title">${esc(f.name)}</div>
      <div class="vc-meta"><span>${fmtSize(f.size)}</span><span>${fmtDate(f.mtime)}</span>${metaExtra || ''}${f._badge ? `<span class="vc-badge">${f._badge}</span>` : ''}</div>
    </div></div>`;
}
function buildFileListHtml() {
  const list = state.filesBest || state.files;
  if (!list.length) return '<div class="sb-empty">暂无录制文件</div>';
  const groups = {}; list.forEach(f => (groups[f.uploader] = groups[f.uploader]||[]).push(f));
  return Object.entries(groups).map(([name,fs]) =>
    `<div class="group-label">${esc(name)} (${fs.length})</div>`+
    fs.sort((a,b)=>b.mtime-a.mtime).map(fileCardHtml).join('')
  ).join('');
}
function bindFileList(container) {
  container.querySelectorAll('.vc-item').forEach(el => {
    // 路径直接取 data-path（Kotlin 返回原始路径，未做 URL 编码，不能 decodeURIComponent）
    el.onclick = () => {
      if (state._fileManage) {
        // 管理模式：点击卡片 = 勾选/取消勾选，不进详情
        toggleFileSel(el.dataset.path, el);
        return;
      }
      state.selectedFile = el.dataset.path; renderSidebar(); renderEditor();
    };
  });
}

/* ========== 侧边栏（仅宽屏显示；窄屏列表由编辑器渲染） ========== */
function renderSidebar() {
  const body = $('#sidebarBody'), v = state.view;
  // 当前视图挂到 #app 上：CSS 据此在无列表的视图（设置/账号等）收起侧边栏
  document.getElementById('app').dataset.view = v;
  if (window.innerWidth <= 991) return;
  if (v === 'explorer') {
    $('#sidebarCount').textContent = state.rooms.length;
    // 轮询刷新时静默更新（无入场动画）；首次/切视图时保留动画
    body.classList.toggle('no-anim', !!state._roomsRendered);
    const st = body.scrollTop;  // 保持滚动位置（房间多时用户可能在翻列表）
    body.innerHTML = buildRoomListHtml();
    body.scrollTop = st;
    state._roomsRendered = true;
    bindRoomList(body);
  } else if (v === 'danmaku') {
    $('#sidebarCount').textContent = '';
    body.innerHTML = state.rooms.map(r => `
      <div class="room-item ${state.danmakuRoom===r.roomId?'selected':''}" data-rid="${r.roomId}">
        <span class="live-dot ${r.liveStatus===1?'live':'offline'}"></span>
        <div class="ri-body"><div class="ri-top"><span class="ri-name">${esc(r.name)}</span></div>
        <div class="ri-title">${r.liveStatus===1?'在线':'离线'}</div></div></div>`).join('') || '<div class="sb-empty">请先添加房间</div>';
    body.querySelectorAll('.room-item').forEach(el => {
      el.onclick = () => { state.danmakuRoom = Number(el.dataset.rid); renderSidebar(); renderEditor(); };
    });
  } else if (v === 'files') {
    loadFiles();
    $('#sidebarCount').textContent = (state.filesBest||state.files).length;
    body.classList.toggle('no-anim', !!state._filesRendered);
    const st = body.scrollTop;
    body.innerHTML = buildFileListHtml();
    body.scrollTop = st;
    state._filesRendered = true;
    bindFileList(body);
  } else {
    $('#sidebarCount').textContent = '';
    body.innerHTML = `<div class="sb-empty">${VIEW_TITLES[v]||''}</div>`;
  }
}

/* ========== 编辑器（两级导航：列表页 → 点击卡片 → 详情页，带返回） ========== */
const IS_NARROW = () => window.innerWidth <= 991;
function renderEditor() {
  const body = $('#editorBody'), tabs = $('#tabs'), v = state.view;
  _subnavKey = '';  // 主视图重置二级页返回头状态
  if (v === 'explorer') {
    tabs.innerHTML = '<div class="tab active">直播监控</div>';
    if (state._roomSearch) {
      renderRoomSearch();
      return;
    } else if (!state.rooms.length) { body.innerHTML = `<div class="detail-empty"><div class="empty-icon">${ic('plus',28)}</div>
      <div class="empty-hint-title">暂无房间</div><div class="empty-hint-sub">点击右上角 ＋ 添加</div></div>`; return; }
    if (state._detailOpen && state.currentRoom && state.rooms.find(r=>r.roomId===state.currentRoom)) {
      // 详情页：返回按钮回列表
      subHeader('直播监控', "state._detailOpen=false;renderEditor()");
      renderRoomDetail(state.currentRoom);
    } else {
      renderRoomListPage();
    }
  } else if (v === 'danmaku') {
    tabs.innerHTML = '<div class="tab active">弹幕</div>';
    if (!state.danmakuRoom) state.danmakuRoom = state.rooms[0]?.roomId||null;
    if (!state.danmakuRoom) { body.innerHTML = '<div class="detail-empty"><div class="empty-icon">'+ic('send',28)+'</div><div class="empty-hint-title">请先添加房间</div><div class="empty-hint-sub">在监控页添加房间后即可查看弹幕</div></div>'; return; }
    renderDanmakuPanel();
  } else if (v === 'files') {
    loadFiles();
    tabs.innerHTML = '<div class="tab active">录制文件</div>';
    if (state.selectedFile) {
      // 详情管理页：返回按钮回列表
      subHeader('录制文件', "state.selectedFile=null;state._fileManage=false;_fileSel.clear();renderEditor()");
      renderFileDetail(state.selectedFile);
    } else {
      renderFileListPage();
    }
  } else if (v === 'data') { tabs.innerHTML = '<div class="tab active">数据统计</div>'; renderDataPanel();
  } else if (v === 'history') { tabs.innerHTML = '<div class="tab active">录制历史</div>'; renderHistoryPanel();
  } else if (v === 'tools') { tabs.innerHTML = '<div class="tab active">修复工具</div>'; loadFiles(); renderToolsPanel();
  } else if (v === 'account') { tabs.innerHTML = '<div class="tab active">账号管理</div>'; renderAccount(); }
  else if (v === 'settings') { tabs.innerHTML = '<div class="tab active">设置</div>'; renderSettings();
  } else if (v === 'log') { tabs.innerHTML = '<div class="tab active">运行日志</div>' +
      `<button class="btn btn-sec btn-sm" style="margin-right:8px;align-self:center" onclick="renderLogFiles()">${ic('clock',12)} 历史文件</button>
       <button class="btn btn-sec btn-sm" style="margin-left:auto;margin-right:8px;align-self:center" onclick="saveLogsToFile()">${ic('save',12)} 保存到文件</button>`;
    body.innerHTML = `<div class="log-toolbar">
      <span class="toolbar-text">运行日志（自动落盘保留7天；崩溃日志在设置-调试里）</span></div>
      <div class="log-panel" id="logPanel"></div>`;
    loadLogs();
  }
}

/* 编辑器内房间列表页（宽屏窄屏统一；点击卡片进入详情） */
function renderRoomListPage() {
  const body = $('#editorBody');
  body.innerHTML = `<div class="page-list">${buildRoomListHtml()}</div>`;
  bindRoomList(body.querySelector('.page-list'));
}
/* 编辑器内文件列表页（分组 + 管理入口） */
function renderFileListPage() {
  const body = $('#editorBody');
  body.innerHTML = `<div class="file-page-head">
      <span class="toolbar-text" id="fileCountText">共 ${(state.filesBest||state.files).length} 个文件</span>
      <button class="btn ${state._fileManage?'btn-primary':'btn-sec'} btn-sm" onclick="toggleFileManage()">${ic('check',12)} ${state._fileManage?'完成':'管理'}</button></div>
    <div class="page-list">${buildFileListHtml()}</div>`;
  bindFileList(body.querySelector('.page-list'));
  if (state._fileManage) updateFileManageUI();  // 管理模式：给卡片加勾选框 + 底部操作栏
}
/* ========== 录制文件批量管理 ========== */
/* 选中集合以文件路径为 key：勾选框只是视觉呈现，真正状态存这里。
 * 列表静默刷新会重建 DOM（录制中文件 size/mtime 变化触发），DOM 勾选必然丢失，
 * 靠这个集合在重建后恢复勾选；计数与删除也从它取值，杜绝“显示 4/7 却提示未选择”。 */
let _fileSel = new Set();
/* 勾选/取消勾选（卡片点击与勾选框共用入口） */
function toggleFileSel(path, card) {
  if (_fileSel.has(path)) _fileSel.delete(path); else _fileSel.add(path);
  if (card) card.classList.toggle('vc-checked', _fileSel.has(path));
  const box = card && card.querySelector('.fc-box');
  if (box) box.checked = _fileSel.has(path);
  updateFileManageUI();
}
function toggleFileManage() {
  state._fileManage = !state._fileManage;
  if (!state._fileManage) _fileSel.clear();
  renderEditor();
}
function updateFileManageUI() {
  const body = $('#editorBody');
  if (!state._fileManage) return;
  // 编辑器卡片：补勾选框 + 选中态与 _fileSel 同步（重建后的卡片也能恢复选中）
  body.querySelectorAll('.page-list .vc-item').forEach(c => {
    const sel = _fileSel.has(c.dataset.path);
    c.classList.toggle('vc-checked', sel);
    let box = c.querySelector('.fc-box');
    if (!box) {
      box = document.createElement('input');
      box.type = 'checkbox';
      box.className = 'checkbox fc-box';
      box.dataset.path = c.dataset.path;
      box.onchange = () => toggleFileSel(box.dataset.path, box.closest('.vc-item'));
      // 勾选框点击不冒泡（卡片 onclick 会切换勾选，避免双重切换）
      box.addEventListener('click', e => e.stopPropagation());
      c.prepend(box);
    }
    box.checked = sel;
  });
  // 侧边栏卡片同步选中态（宽屏时侧边栏同样可点选）
  document.querySelectorAll('#sidebarBody .vc-item').forEach(c => c.classList.toggle('vc-checked', _fileSel.has(c.dataset.path)));
  let bar = body.querySelector('.file-manage-bar');
  if (!bar) {
    bar = document.createElement('div');
    bar.className = 'file-manage-bar';
    body.appendChild(bar);
  }
  const total = body.querySelectorAll('.page-list .vc-item').length;
  const checked = _fileSel.size;
  bar.innerHTML = `<button class="btn btn-sec btn-sm" onclick="selectAllFiles()">${ic('check',12)} 全选</button>
    <span class="fm-count${checked ? ' has' : ''}">已选 <b>${checked}</b>/${total}</span>
    <div class="fm-actions">
      <button class="btn btn-danger btn-sm" ${checked?'':'disabled'} onclick="deleteSelectedFiles()">${ic('trash',12)} 删除所选</button>
      <button class="btn btn-primary btn-sm" onclick="toggleFileManage()">完成</button>
    </div>`;
}
function selectAllFiles() {
  const cards = document.querySelectorAll('#editorBody .page-list .vc-item');
  const selectAll = _fileSel.size !== cards.length;  // 已全选时再次点击 = 取消全选
  _fileSel.clear();
  if (selectAll) cards.forEach(c => _fileSel.add(c.dataset.path));
  cards.forEach(c => {
    const sel = _fileSel.has(c.dataset.path);
    c.classList.toggle('vc-checked', sel);
    const box = c.querySelector('.fc-box');
    if (box) box.checked = sel;
  });
  updateFileManageUI();
}
function deleteSelectedFiles() {
  const paths = [..._fileSel];
  if (!paths.length) { toast('未选择文件','warn'); return; }
  showModal({ title:'批量删除', msg:`确认删除选中的 ${paths.length} 个文件吗？\n此操作不可恢复。`,
    okText:'删除', onOk:()=>{
      paths.forEach(p => { try { JSON.parse(AndroidBridge.deleteRecordFile(p)); } catch(e){} });
      state._fileManage = false;
      state.selectedFile = null;
      _fileSel.clear();
      toast('已删除','ok');
      loadFiles(); renderEditor();
    } });
}

/* ========== 房间详情 ========== */
function renderRoomDetail(rid, container) {
  const r = state.rooms.find(x=>x.roomId===rid); if(!r) return;
  const body = container || $('#editorBody');
  const isLive = r.liveStatus===1||r.liveStatus===2, rec = r.recState==='recording';
  body.innerHTML = `<div class="view">
    <div class="head-row">
      ${r.cover?`<div class="head-cover-wrap"><img src="${esc(imgUrl(r.cover))}" class="head-cover" referrerpolicy="no-referrer" onerror="this.parentElement.classList.add('no-img')"></div>`:''}
      <div class="head-info">
        <div class="detail-title">${esc(r.name||'房间 '+r.roomId)}</div>
        <div class="detail-meta"><span class="state-pill ${isLive?'state-running':'state-offline'}">${isLive ? (r.liveStatus===2?'● 轮播':'● LIVE') : '● OFFLINE'}</span>
          ${esc(r.title||'未开播')}</div>
        <div class="room-meta-line">
          <span>${ic('home',13)} ${r.roomId}${r.shortId?' (短号 '+r.shortId+')':''}</span>
          <span>${ic('user',13)} UID ${r.uid||'-'}</span>
          <span>${ic('monitor',13)} ${esc(r.areaName||'-')}</span>
        </div>
      </div></div>
    <div class="btn-row tight detail-actions">
      ${rec?`<button class="btn btn-danger" onclick="stopRec(${r.roomId})">${ic('stop',14)} 停止录制</button>`
        :`<button class="btn btn-primary" onclick="startRec(${r.roomId})">${ic('record',14)} 立即录制</button>`}
      <button class="btn btn-sec" onclick="openLive(${r.roomId})">${ic('eye',14)} 观看直播</button>
      <button class="btn btn-sec" onclick="toggleListen(${r.roomId})">${ic('music',14)} ${isListening(r.roomId)?'停止收听':'听直播'}</button>
      <button class="btn btn-sec" onclick="refreshRoom(${r.roomId})">${ic('refresh',14)} 刷新</button>
      <button class="btn btn-danger-sec" onclick="removeRoom(${r.roomId})">${ic('trash',14)} 移除</button></div>
    <h2>录制状态</h2>
    <div class="stat-grid">
      <div class="stat-card"><div class="sc-label">已录制</div><div class="sc-value">${fmtSize(r.recSize)}</div></div>
      <div class="stat-card"><div class="sc-label">速度</div><div class="sc-value">${fmtSpeed(r.recSpeed)||'-'}</div></div>
      <div class="stat-card"><div class="sc-label">时长</div><div class="sc-value">${r.recState==='recording'&&r.recStartTime>0?fmtDur(Date.now()-r.recStartTime):'-'}</div></div>
      <div class="stat-card"><div class="sc-label">弹幕</div><div class="sc-value">${r.danmakuCount||0}</div></div></div>
    ${r.recFile?`<div class="mono-block rec-file">${esc(r.recFile)}</div>`:''}
    ${r.lastError?`<div class="error-line">${esc(r.lastError)}</div>`:''}
    <h2>房间设置</h2>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">开播自动录制</div><div class="sw-desc">检测到开播后自动开始录制</div></div>
      <label class="switch"><input type="checkbox" ${r.autoRecord?'checked':''} onchange="setAutoRecord(${r.roomId},this.checked)"><span class="slider"></span></label></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">弹幕监听</div><div class="sw-desc">连接弹幕服务器，实时显示</div></div>
      <label class="switch"><input type="checkbox" ${r.danmakuOpen?'checked':''} onchange="setDanmaku(${r.roomId},this.checked)"><span class="slider"></span></label></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">仅录音频</div><div class="sw-desc">只拉取音频流录制，省流量（下次录制生效）</div></div>
      <label class="switch"><input type="checkbox" ${r.audioOnly?'checked':''} onchange="setAudioOnlyRoom(${r.roomId},this.checked)"><span class="slider"></span></label></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">开播提醒</div><div class="sw-desc">开播/下播时发送系统通知</div></div>
      <label class="switch"><input type="checkbox" ${r.remind?'checked':''} onchange="setRemind(${r.roomId},this.checked)"><span class="slider"></span></label></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">录制清晰度</div></div>
      <div class="sw-right"><div id="qualitySel"></div></div></div></div>`;
  // 自定义下拉(替代原生 select)
  const qnOpts = Object.entries(qnLabels).map(([k,v])=>({v:k,label:v}));
  renderCSelect($('#qualitySel'), qnOpts, String(r.quality), v => setQuality(r.roomId, Number(v)));
}

/* ========== 弹幕面板 ========== */
/* 弹幕去重缓冲：5 秒窗口内相同 user+content 视为重复（最多保留 200 条） */
const _dmDedup = [];
function isDupDanmaku(user, content) {
  const now = Date.now();
  while (_dmDedup.length && now - _dmDedup[0].t > 5000) _dmDedup.shift();
  const key = user + '|' + content;
  if (_dmDedup.some(x => x.key === key)) return true;
  _dmDedup.push({ key: key, t: now });
  if (_dmDedup.length > 200) _dmDedup.shift();
  return false;
}
function renderDanmakuPanel() {
  const body = $('#editorBody');
  body.innerHTML = `<div class="danmaku-panel">
    <div class="dm-header">
      <div id="dmRoomSel" class="max-w170"></div>
      <span class="dm-conn" id="dmConn">连接中…</span>
      <span class="spacer"></span>
      <button class="btn btn-sec btn-sm" onclick="clearDanmaku()">清空</button></div>
    <div class="dm-stream" id="dmStream"><div class="dm-empty">连接弹幕中…</div></div>
    <div class="dm-input-row">
      <input id="dmInput" placeholder="${state.account&&state.account.logged?'发送弹幕…':'登录后可发送'}" ${state.account&&state.account.logged?'':'disabled'}>
      <button class="btn btn-primary btn-sm" onclick="sendDm()" ${state.account&&state.account.logged?'':'disabled'}>${ic('send',12)} 发送</button></div></div>`;
  // 自定义下拉(替代原生 select)
  renderCSelect($('#dmRoomSel'),
    state.rooms.map(r=>({v:String(r.roomId), label:r.name||('房间 '+r.roomId)})),
    String(state.danmakuRoom), v => switchDanmakuRoom(Number(v)));
    $('#dmInput').addEventListener('keydown', e => { if(e.key==='Enter') sendDm(); });
  loadDanmakuHistory();
  refreshDanmakuStatus();  // 主动拉取连接状态（一次性事件可能已错过）
}

/* 更新弹幕连接状态显示（事件与主动查询共用） */
function updateDmConn(connected, msg) {
  const el = $('#dmConn');
  if (!el) return;
  if (connected) {
    el.textContent = '已连接';
    el.classList.remove('dm-conn-fail');
    el.onclick = null;
  } else if (msg && msg.indexOf('点击重试') >= 0) {
    el.textContent = '连接失败，点击重试';
    el.classList.add('dm-conn-fail');
    el.onclick = () => { el.textContent = '重连中…'; el.onclick = null; try { AndroidBridge.retryDanmaku(state.danmakuRoom); } catch(e){} };
  } else {
    el.textContent = msg || '未连接';
    el.classList.remove('dm-conn-fail');
  }
}
function refreshDanmakuStatus() {
  try {
    const r = JSON.parse(AndroidBridge.getDanmakuStatus(state.danmakuRoom));
    updateDmConn(!!r.connected, r.msg);
  } catch(e){}
}

function switchDanmakuRoom(rid) { state.danmakuRoom = Number(rid); try { localStorage.setItem('ddtv_dmroom', String(rid)); } catch(e){} renderEditor(); }
function clearDanmaku() { const s=$('#dmStream'); if(s) s.innerHTML=''; }

function sendDm() {
  const input=$('#dmInput'), text=(input.value||'').trim(); if(!text) return;
  const r=JSON.parse(AndroidBridge.sendDanmaku(state.danmakuRoom,text));
  toast(r.msg, r.code<0?'err':'ok'); if(r.code>0) input.value='';
}

function loadDanmakuHistory() {
  // 拉全量内存缓冲（上限 500 条），避免“点进来只显示最近 100 条”内容偏少；历史加载不去重
  try { const list=JSON.parse(AndroidBridge.getRecentDanmaku(state.danmakuRoom,500));
    const stream=$('#dmStream'); if(!stream||!list.length) return;
    stream.innerHTML=''; list.forEach(dm=>appendDanmaku(dm,true,true)); } catch(e){}
}

function appendDanmaku(dm, noScroll, skipDedup) {
  const stream=$('#dmStream'); if(!dm||!stream||dm.roomId!==state.danmakuRoom) return;
  // 普通弹幕去重：5 秒窗口内相同用户+内容只显示一次（防重连/协议重发刷屏）
  // 历史加载(skipDedup)不去重：避免刷屏弹幕被大量滤掉导致“显示少、只剩礼物”
  if (dm.type==='DANMU_MSG' && !skipDedup && isDupDanmaku(dm.user, dm.content)) return;
  if(stream.querySelector('.dm-empty')) stream.innerHTML='';
  const cls=dm.type==='SEND_GIFT'?'gift':dm.type==='SUPER_CHAT_MESSAGE'?'sc':(dm.type==='GUARD_BUY'||dm.type==='GUARD_RENEW')?'guard':'';
  const color=dm.type==='DANMU_MSG'&&dm.color?`style="color:#${('000000'+dm.color.toString(16)).slice(-6)}"`:'';
  const el=document.createElement('div'); el.className='dm-item '+cls;
  el.innerHTML=`<span class="dm-time">${fmtTime(dm.time)}</span><span class="dm-user">${esc(dm.user)}</span>
    <span class="dm-content" ${color}>${esc(dm.content)}${dm.extra?' <span class="txt-warn">'+esc(dm.extra)+'</span>':''}</span>`;
  stream.appendChild(el); while(stream.children.length>500) stream.removeChild(stream.firstChild);
  if(!noScroll) stream.scrollTop=stream.scrollHeight;
}

/* ========== 关注列表（并入账号页下方） ========== */
function loadFollowGroups() {
  // 异步加载（桥在线程池执行，避免同步网络请求冻结 JS 线程）；收到 follow_groups 事件后渲染
  try { AndroidBridge.loadFollowGroups(); } catch(e){ state.followGroups = state.followGroups || []; }
}
let _followReqSeq = 0;  // 关注异步加载请求序号（防乱序覆盖）
function loadFollowUsers() {
  // 异步加载（桥在线程池执行，避免账号页 JS 线程卡顿）；收到 follows_loaded 事件后渲染
  // 递增请求序号：丢弃过期响应（同分组快速连点/重进时的乱序竞态）
  const seq = ++_followReqSeq;
  const list = $('#followList');
  if (list) list.innerHTML = '<div class="sb-empty">加载中…</div>';
  AndroidBridge.loadFollows(state.followGroupId ?? 0, seq);
}
/** 渲染分组标签（账号页，第一项固定为「全部」） */
function renderFollowGroups() {
  const el = $('#followGroups'); if (!el) return;
  if (!state.followGroups.length) { el.innerHTML = ''; return; }
  const pills = [`<button class="fg-pill ${state.followGroupId===-1?'active':''}" data-tagid="-1">全部</button>`].concat(
    state.followGroups.map(g =>
      `<button class="fg-pill ${state.followGroupId===g.tagid?'active':''}" data-tagid="${g.tagid}">${esc(g.name)}</button>`));
  el.innerHTML = pills.join('');
  el.querySelectorAll('.fg-pill').forEach(b => b.onclick = () => {
    state.followGroupId = Number(b.dataset.tagid); state.followUsers = [];
    renderFollowGroups(); loadFollowUsers();
  });
}
/** 渲染关注用户列表（账号页；直播中的 UP 单独一栏横向滑动） */
function renderFollowUsers() {
  const list = $('#followList'); if (!list) return;
  const count = $('#followCountText');
  const liveUsers = state.followUsers.filter(u=>u.liveStatus===1);
  if (count) count.textContent = `共 ${state.followUsers.length} 人，${liveUsers.length} 人在播`;
  const liveRow = liveUsers.length ? `<div class="live-up-label">正在直播</div>` +
    `<div class="live-up-row">${liveUsers.map(u => `
      <div class="live-up-card" data-mid="${u.mid}" onclick="toggleLiveUp(this)">
        <div class="luc-avatar">${avatarHtml(u.face, u.uname)}</div>
        <div class="luc-name">${esc(u.uname)}</div>
        <div class="luc-badge"><i class="live-dot live"></i>直播中</div>
      </div>`).join('')}</div>` : '';
  list.innerHTML = liveRow + state.followUsers.map(u=>`
      <div class="follow-item">
        <input type="checkbox" class="checkbox" data-mid="${u.mid}">
        <div class="fi-avatar">${avatarHtml(u.face, u.uname)}</div>
        <div class="fi-body"><div class="fi-name">${esc(u.uname)}</div>
        <div class="fi-status">${u.liveStatus===1?'● 直播中':'未开播'}</div></div></div>`).join('') ||
    '<div class="sb-empty">该分组暂无关注用户</div>';
  // 横滑卡与列表勾选同步（初始都不选中，点「选在播」或手动勾选后才高亮）
  list.querySelectorAll('.live-up-card').forEach(c => {
    const cb = list.querySelector(`.checkbox[data-mid="${c.dataset.mid}"]`);
    c.classList.toggle('selected', !!(cb && cb.checked));
  });
}
/** 点击横滑卡片：切换该 UP 的勾选（与列表 checkbox 联动） */
function toggleLiveUp(el) {
  const cb = document.querySelector(`#followList .checkbox[data-mid="${el.dataset.mid}"]`);
  if (cb) { cb.checked = !cb.checked; el.classList.toggle('selected', cb.checked); }
}
function selectLiveFollow() {
  document.querySelectorAll('#followList .checkbox').forEach(cb => {
    cb.checked = state.followUsers.find(u=>u.mid==cb.dataset.mid)?.liveStatus===1;
  });
  // 横滑卡选中态同步
  document.querySelectorAll('#followList .live-up-card').forEach(c => {
    const cb = document.querySelector(`#followList .checkbox[data-mid="${c.dataset.mid}"]`);
    c.classList.toggle('selected', !!(cb && cb.checked));
  });
}
function importFollows() {
  const mids=[...document.querySelectorAll('#followList .checkbox:checked')].map(cb=>cb.dataset.mid);
  if(!mids.length) { toast('请先勾选要导入的用户','warn'); return; }
  // 带 roomId 一起传（直播中的 UP 拉取时已带房间号，直接使用避免 uid 转换失败）
  const users = state.followUsers
    .filter(u => mids.includes(String(u.mid)))
    .map(u => ({mid:u.mid, roomId:u.roomId||0}));
  const r=JSON.parse(AndroidBridge.importFollows(JSON.stringify(users)));
  toast(r.msg, r.code<0?'err':'ok');
  if (r.code >= 0) {
    // 导入后切到直播监控列表页，直观看到新房间
    setTimeout(()=>{ switchView('explorer'); }, 800);
  }
}

function updateFileListPageSilent() {
  const body = $('#editorBody');
  const list = body.querySelector('.page-list');
  if (!list || state.selectedFile) return;
  const cnt = $('#fileCountText');
  const shown = state.filesBest || state.files;
  if (cnt) cnt.textContent = `共 ${shown.length} 个文件`;
  // 内容无变化时跳过重建：避免闪烁、封面图重载、滚动抖动
  const sig = filesSignature(shown);
  if (state._filesSig === sig) return;
  state._filesSig = sig;
  list.classList.add('no-anim');
  list.innerHTML = buildFileListHtml();
  bindFileList(list);
  // 静默重建会清掉卡片上的勾选框：管理模式恢复勾选（选中集合是权威状态）
  if (state._fileManage) {
    const alive = new Set([...list.querySelectorAll('.vc-item')].map(c => c.dataset.path));
    for (const p of [..._fileSel]) if (!alive.has(p)) _fileSel.delete(p);
    updateFileManageUI();
  }
}

/* ========== 数据统计(对应原版 DefaultPage) ========== */
function loadStats() { try{state.stats=JSON.parse(AndroidBridge.getStats());}catch(e){state.stats=null;} }
function renderDataPanel() {
  const body=$('#editorBody');
  loadStats();
  const s=state.stats||{};
  body.innerHTML=`<div class="view">
    <h1>数据统计</h1>
    <p class="lead">DDTV 运行状态一览</p>
    <div class="stat-grid">
      <div class="stat-card"><div class="sc-label">监控房间</div><div class="sc-value">${s.monitoring||0}</div></div>
      <div class="stat-card"><div class="sc-label">直播中</div><div class="sc-value txt-accent">${s.live||0}</div></div>
      <div class="stat-card"><div class="sc-label">录制中</div><div class="sc-value txt-error">${s.recording||0}</div></div>
      <div class="stat-card"><div class="sc-label">录制历史</div><div class="sc-value">${s.historyCount||0}</div></div>
      <div class="stat-card"><div class="sc-label">今日文件</div><div class="sc-value">${s.todayFiles||0}<small> 个</small></div></div>
      <div class="stat-card"><div class="sc-label">今日大小</div><div class="sc-value">${fmtSize(s.todayBytes||0)}</div></div>
      <div class="stat-card"><div class="sc-label">存储占用</div><div class="sc-value">${fmtSize(s.totalBytes||0)}</div></div>
      <div class="stat-card"><div class="sc-label">弹幕屏蔽词</div><div class="sc-value">${state.settings&&state.settings.blockBarrage?(state.settings.blockBarrage.split('|').length):0}<small> 个</small></div></div>
    </div>
    <div class="btn-row">
      <button class="btn btn-sec" onclick="refreshStats()">${ic('refresh',14)} 刷新</button></div>
  </div>`;
}
function refreshStats() { loadStats(); renderDataPanel(); }
/** 数据页增量刷新（轮询用，不重建 DOM 避免闪烁） */
function updateStatsPanel() {
  const s = state.stats||{};
  const vals = document.querySelectorAll('#editorBody .stat-card .sc-value');
  if (vals.length < 8) { renderDataPanel(); return; }
  vals[0].textContent = s.monitoring||0;
  vals[1].textContent = s.live||0;
  vals[2].textContent = s.recording||0;
  vals[3].textContent = s.historyCount||0;
  vals[4].innerHTML = `${s.todayFiles||0}<small> 个</small>`;
  vals[5].textContent = fmtSize(s.todayBytes||0);
  vals[6].textContent = fmtSize(s.totalBytes||0);
  vals[7].innerHTML = `${state.settings&&state.settings.blockBarrage?(state.settings.blockBarrage.split('|').length):0}<small> 个</small>`;
}

/* ========== 录制历史(对应原版 HistoryPage) ========== */
function loadHistory() { try{state.history=JSON.parse(AndroidBridge.getHistories());}catch(e){state.history=[];} }
function renderHistoryPanel() {
  const body=$('#editorBody');
  loadHistory();
  if(!state.history.length) {
    _histManage=false; _histSel.clear();
    body.innerHTML='<div class="detail-empty"><div class="empty-icon">'+ic('film',28)+'</div><div class="empty-hint-title">暂无录制历史</div><div class="empty-hint-sub">直播录制结束后会记录在这里</div></div>';
    return;
  }
  body.innerHTML=`<div class="view">
    <h1>录制历史</h1>
    <p class="lead">共 ${state.history.length} 条录制记录</p>
    <div class="btn-row tight">
      ${_histManage
        ? `<button class="btn btn-sec btn-sm" onclick="toggleHistSelectAll()">${ic('check',12)} 全选</button>
           <button class="btn btn-danger btn-sm" data-act="histDel" onclick="deleteSelectedHist()">${ic('trash',12)} 删除(${_histSel.size})</button>
           <button class="btn btn-sec btn-sm" onclick="exitHistManage()">退出</button>`
        : `<button class="btn btn-sec btn-sm" onclick="enterHistManage()">${ic('check',12)} 批量管理</button>`}
    </div>
    <div class="vc-list">
    ${state.history.map((h,idx)=>{
      const cover = h.coverPath || roomCoverOf(h.name);
      return `<div class="vc-item${_histManage&&_histSel.has(idx)?' vc-checked':''}" data-idx="${idx}">
        <div class="vc-cover-wrap${cover ? ' has-img' : ''}">
          ${cover ? `<img class="vc-cover" src="${esc(cover)}" referrerpolicy="no-referrer" alt="" onerror="this.parentElement.classList.remove('has-img')">` : ''}
          <div class="vc-cover vc-cover-ph">${ic('film',20)}</div>
          <span class="vc-fmt">回放</span>
          ${_histManage?`<input type="checkbox" class="hist-chk" ${_histSel.has(idx)?'checked':''} onclick="event.stopPropagation()">`:''}
        </div>
        <div class="vc-body">
          <div class="vc-title">${esc(h.name)}</div>
          <div class="vc-meta"><span>${esc(h.title||'未记录标题')}</span><span>${h.fileCount||0} 个文件</span></div>
          <div class="vc-date">${esc(h.time)}</div>
        </div>
      </div>`;
    }).join('')}
    </div>
  </div>`;
  // 单击查看直播；长按弹出管理菜单；管理模式下单击切换选中
  body.querySelectorAll('.vc-item').forEach(el => {
    const idx = Number(el.dataset.idx);
    const h = state.history[idx];
    if (!h) return;
    if (_histManage) {
      el.onclick = () => toggleHistSel(idx, el);
      return;
    }
    el.onclick = () => openHistoryRoom(h.roomId);
    const isMonitored = (state.rooms||[]).some(r => r.roomId === h.roomId);
    el.addEventListener('contextmenu', e => e.preventDefault());
    el.addEventListener('touchstart', e => bindHistoryLongPress(el, h, idx, isMonitored, e.touches[0].clientX, e.touches[0].clientY), { passive: true });
    el.addEventListener('touchmove', e => longPressMove(e.touches[0].clientX, e.touches[0].clientY), { passive: true });
    el.addEventListener('touchend', () => longPressCancel(false), { passive: true });
    el.addEventListener('touchcancel', () => longPressCancel(true), { passive: true });
    el.addEventListener('mousedown', e => { if (e.button === 0) bindHistoryLongPress(el, h, idx, isMonitored, e.clientX, e.clientY); });
    el.addEventListener('mouseup', () => longPressCancel(false));
    el.addEventListener('mouseleave', () => longPressCancel(true));
  });
}
/* ========== 录制历史批量管理 ========== */
let _histManage=false, _histSel=new Set();
function enterHistManage(){ _histManage=true; _histSel.clear(); renderHistoryPanel(); }
function exitHistManage(){ _histManage=false; _histSel.clear(); renderHistoryPanel(); }
function toggleHistSel(idx, el) {
  if (_histSel.has(idx)) _histSel.delete(idx); else _histSel.add(idx);
  el.classList.toggle('vc-checked', _histSel.has(idx));
  const chk = el.querySelector('.hist-chk');
  if (chk) chk.checked = _histSel.has(idx);
  const btn = document.querySelector('[data-act=histDel]');
  if (btn) btn.textContent = '删除(' + _histSel.size + ')';
}
function toggleHistSelectAll(){
  if (_histSel.size === state.history.length) _histSel.clear();
  else state.history.forEach((h,i)=>_histSel.add(i));
  renderHistoryPanel();
}
function deleteSelectedHist() {
  if (!_histSel.size) { toast('请先选择记录','warn'); return; }
  const n = _histSel.size;
  showModal({ title:'删除 '+n+' 条录制历史?', msg:'删除后不可恢复', okText:'删除',
    onOk: () => {
      const r = JSON.parse(AndroidBridge.deleteHistories(JSON.stringify([..._histSel])));
      toast(r.msg||'已删除', r.code<0?'err':'ok');
      _histSel.clear(); _histManage=false; loadHistory(); renderHistoryPanel();
    } });
}
function openHistoryRoom(rid) { state.currentRoom=rid; switchView('explorer'); }

/* ========== 修复工具(对应原版 ToolsPage) ========== */
function renderToolsPanel() {
  const body=$('#editorBody');
  body.innerHTML=`<div class="view">
    <h1>修复工具</h1>
    <p class="lead">使用 FFmpeg 修复/转码录制文件</p>
    <h2>选择文件</h2>
    <div class="btn-row tight">
      <button class="btn btn-primary" onclick="pickToolFile()">${ic('folder',14)} 从文件管理器选择</button>
      <button class="btn btn-sec" onclick="loadFiles();renderToolsPanel()">${ic('refresh',14)} 刷新列表</button></div>
    <div id="toolsFileList" class="tools-file-list">
      ${state.files.length?state.files.slice().sort((a,b)=>b.mtime-a.mtime).map(f=>`
        <div class="vc-item" data-path="${esc(f.path)}" onclick="selectToolFile(this)">
          <div class="vc-cover-wrap${roomCoverOf(f.uploader) ? ' has-img' : ''}">
            ${roomCoverOf(f.uploader) ? `<img class="vc-cover" src="${esc(imgUrl(roomCoverOf(f.uploader)))}" referrerpolicy="no-referrer" alt="" onerror="this.parentElement.classList.remove('has-img')">` : ''}
            <div class="vc-cover vc-cover-ph">${ic(f.isAudio?'music':'film',20)}</div>
            <span class="vc-fmt">${f.isFlv?'FLV':f.isAudio?'音频':'MP4'}</span>
          </div>
          <div class="vc-body">
            <div class="vc-title">${esc(f.name)}</div>
            <div class="vc-meta"><span>${fmtSize(f.size)}</span><span>录制目录</span></div>
          </div></div>`).join('')
      :'<div class="sb-empty">录制目录暂无文件<br>可点击上方按钮从文件管理器选择</div>'}
    </div>
    <div id="toolSel" class="mono-block toolSel">未选择文件</div>
    <div class="btn-row">
      <button class="btn btn-sec" onclick="submitRepair('remux')">${ic('bolt',14)} 快速转封装</button>
      <button class="btn btn-sec" onclick="submitRepair('repair')">${ic('wrench',14)} 修复损坏</button>
      <button class="btn btn-sec" onclick="submitRepair('transcode')">${ic('film',14)} 完整转码</button>
      <button class="btn btn-sec" onclick="exportDanmakuSrt('srt')">${ic('save',14)} 导出字幕(.srt)</button>
      <button class="btn btn-sec" onclick="exportDanmakuSrt('ass')">${ic('save',14)} 导出字幕(.ass)</button>
      <button class="btn btn-sec" onclick="exportDanmakuSrt('assdm')">${ic('save',14)} 导出弹幕(.ass)</button></div>
    <div class="note">
      · 快速转封装：flv→mp4，-c copy 不重编码，最快<br>
      · 修复损坏：忽略错误重封装，处理录制中断的文件<br>
      · 完整转码：H.264 重编码,修复时间轴/编码问题，较慢<br>
      · 音频文件(m4a)同样可修复损坏（-c copy 重封装/重编码兜底）<br>
      · 导出字幕：按录像起点对齐同目录弹幕，生成 .srt / .ass 字幕 或 .ass 弹幕(滚动)，可被播放器/剪辑加载<br>
      · 任务按顺序排队执行，可取消/重试/删除</div>
    <h2>任务列表</h2>
    <div class="repair-tasks" id="repairTasks"><div class="sb-empty">暂无任务</div></div>
  </div>`;
  refreshRepairTasks();
}
let _toolPath='';
const REPAIR_MODE_LABEL = { remux:'快速转封装', repair:'修复损坏', transcode:'完整转码' };
const REPAIR_STATE_LABEL = { pending:'等待中', running:'进行中', done:'完成', failed:'失败', cancelled:'已取消' };
function selectToolFile(el) {
  document.querySelectorAll('#toolsFileList .vc-item').forEach(x=>x.classList.remove('vc-checked'));
  el.classList.add('vc-checked');
  _toolPath=el.dataset.path;
  $('#toolSel').textContent='已选择: '+_toolPath.split('/').pop();
}
function pickToolFile() {
  try { AndroidBridge.pickFile('*/*'); } catch(e){ toast('无法打开文件选择器：'+e,'err'); }
}
function onFilePicked(path) {
  _toolPath=path;
  $('#toolSel').textContent='已选择: '+path.split('/').pop()+'（文件管理器）';
  toast('文件已选择','ok');
}
function submitRepair(mode) {
  if(!_toolPath) { toast('请先选择文件','warn'); return; }
  const r=JSON.parse(AndroidBridge.repairFile(_toolPath,mode));
  if(r.code<0){ toast(r.msg,'err'); return; }
  toast('已加入队列: '+REPAIR_MODE_LABEL[mode],'ok');
  refreshRepairTasks();
}
function exportDanmakuSrt(fmt) {
  if(!_toolPath) { toast('请先选择录像文件','warn'); return; }
  const f=fmt||'srt';
  const FMT_LABEL={srt:'SRT 字幕',ass:'ASS 字幕',assdm:'ASS 弹幕'};
  try {
    const r=JSON.parse(AndroidBridge.exportDanmakuSrt(_toolPath,f));
    if(r.code<0){ toast(r.msg,'err'); return; }
    showModal({ title:'字幕已生成 ('+(FMT_LABEL[f]||f.toUpperCase())+')', msg:r.path+'\n\n共 '+r.count+' 条弹幕，已存为字幕(与录像同名)',
      okText:'分享', cancelText:'关闭',
      onOk: () => { try { const s=JSON.parse(AndroidBridge.shareLogFile(r.path)); toast(s.msg, s.code<0?'err':'ok'); } catch(e2){ toast('分享失败：'+e2,'err'); } } });
  } catch(e) { toast('生成字幕失败：'+e,'err'); }
}

/* ========== 修复任务列表 ========== */
let _tasksSig = '';
function refreshRepairTasks() {
  try { const list=JSON.parse(AndroidBridge.getRepairTasks()); renderRepairTasks(list); }
  catch(e){}
}
function renderRepairTasks(tasks) {
  const el = $('#repairTasks');
  if (!el) return;
  const sig = tasks.map(t=>t.id+'|'+t.state+'|'+t.output+'|'+t.error).join(';');
  if (sig === _tasksSig) return;  // 无变化跳过重建
  _tasksSig = sig;
  if (!tasks.length) { el.innerHTML = '<div class="sb-empty">暂无任务</div>'; return; }
  el.innerHTML = tasks.map(t => {
    const dur = t.finishTime ? Math.max(1, Math.round((t.finishTime - t.submitTime) / 1000)) : 0;
    const durTxt = dur ? ' · 用时 ' + (dur >= 60 ? Math.floor(dur/60) + ' 分 ' + (dur%60) + ' 秒' : dur + ' 秒') : '';
    const btns = t.state==='running' ? `<button class="btn btn-sec btn-sm" data-act="cancel">取消</button>`
      : t.state==='pending' ? `<button class="btn btn-sec btn-sm" data-act="cancel">取消</button>`
      : t.state==='failed' ? `<button class="btn btn-sec btn-sm" data-act="retry">重试</button><button class="btn btn-sec btn-sm" data-act="remove">删除</button>`
      : t.state==='cancelled' ? `<button class="btn btn-sec btn-sm" data-act="retry">重试</button><button class="btn btn-sec btn-sm" data-act="remove">删除</button>`
      : `<button class="btn btn-sec btn-sm" data-act="remove">删除</button>`;
    return `<div class="rt-item st-${t.state}" data-id="${t.id}">
      <div class="rt-main">
        <div class="rt-name">${esc(t.name)}</div>
        <div class="rt-sub">${REPAIR_MODE_LABEL[t.mode]||t.mode}${durTxt}</div>
        ${t.state==='done'&&t.output?`<div class="rt-out">${esc(t.output.split('/').pop())}</div>`:''}
        ${t.state==='failed'&&t.error?`<div class="rt-err">${esc(t.error.slice(0,200))}</div>`:''}
      </div>
      <div class="rt-right">
        <span class="rt-status">${REPAIR_STATE_LABEL[t.state]||t.state}</span>
        <span class="rt-actions">${btns}</span>
      </div>
    </div>`;
  }).join('');
  el.querySelectorAll('.rt-item').forEach(item => {
    const id = Number(item.dataset.id);
    item.querySelectorAll('[data-act]').forEach(b => b.onclick = e => {
      e.stopPropagation();
      const act = b.dataset.act;
      if (act==='cancel') { try{ AndroidBridge.cancelRepairTask(id); }catch(e2){} refreshRepairTasks(); }
      else if (act==='retry') { try{ AndroidBridge.retryRepairTask(id); }catch(e2){} refreshRepairTasks(); }
      else if (act==='remove') { try{ AndroidBridge.removeRepairTask(id); }catch(e2){ toast('删除失败: '+e2,'err'); } refreshRepairTasks(); }
    });
  });
}

/* ========== 录制文件 ========== */
function loadFiles() {
  try{state.files=JSON.parse(AndroidBridge.getRecordFiles());}catch(e){state.files=[];}
  state.filesBest = bestFileVariants(state.files);
  // 注意：不重置 _filesSig —— 它记录“已渲染内容”的签名，由 updateFileListPageSilent 对比后更新
}
function renderFileDetail(path, container) {
  const body = container || $('#editorBody');
  try { const files=JSON.parse(AndroidBridge.getRecordFiles()), f=files.find(x=>x.path===path);
    if(!f){ state.selectedFile=null; renderEditor(); return; }  // 文件已删除：回列表
    body.innerHTML=`<div class="view">
      <div class="detail-title">${esc(f.name)}</div>
      <div class="detail-meta">${esc(f.uploader)} · ${f.isAudio?'音频':(f.isFlv?'FLV':'MP4')}</div>
      <div class="stat-grid">
        <div class="stat-card"><div class="sc-label">大小</div><div class="sc-value">${fmtSize(f.size)}</div></div>
        <div class="stat-card"><div class="sc-label">时间</div><div class="sc-value">${fmtDate(f.mtime)}</div></div></div>
      <div class="mono-block mb16">${esc(f.path)}</div>
      <div class="btn-row">
        <button class="btn btn-primary" data-act="play">${ic('play',14)} 播放</button>
        <button class="btn btn-sec" data-act="share">${ic('share',14)} 分享</button>
        ${f.isFlv?`<button class="btn btn-sec" data-act="remux">${ic('film',14)} 转封装</button>`:''}
        <button class="btn btn-sec" data-act="rename">${ic('check',14)} 重命名</button>
        <button class="btn btn-sec" data-act="copy">${ic('file',14)} 复制路径</button>
        <button class="btn btn-danger" data-act="delete">${ic('trash',14)} 删除</button></div></div>`;
    // 按钮用闭包绑定（路径不进 JS 字符串，避免文件名注入）
    body.querySelectorAll('.btn-row [data-act]').forEach(b => b.onclick = () => {
      if (b.dataset.act==='play') playFile(f.path);
      else if (b.dataset.act==='share') shareFile(f.path);
      else if (b.dataset.act==='remux') remuxFile(f.path);
      else if (b.dataset.act==='rename') renameFile(f);
      else if (b.dataset.act==='copy') copyPath(f.path);
      else deleteFile(f.path);
    });
  } catch(e){}
}
/** 重命名文件（不含扩展名） */
function renameFile(f) {
  showModal({ title:'重命名', msg:'输入新的文件名（不含扩展名）', input:true,
    placeholder:'新文件名', defaultValue: f.name.replace(/\.[^.]+$/, ''), okText:'重命名',
    onOk:(val)=>{ if(!val) return;
      const r=JSON.parse(AndroidBridge.renameFile(f.path, val.trim()));
      toast(r.msg, r.code<0?'err':'ok');
      if(r.code>0){ if(r.path) state.selectedFile = r.path; loadFiles(); renderEditor(); }
    } });
}
/** 复制文件路径到剪贴板 */
function copyPath(path) {
  try { AndroidBridge.copyText(path); toast('已复制路径','ok'); } catch(e){ toast('复制失败: '+e,'err'); }
}

/* ========== 账号管理 ========== */
function loadAccount() {
  try{state.account=JSON.parse(AndroidBridge.getAccount());}catch(e){state.account=null;}
  updateStatusbar();
  if(state.view==='account') renderEditor();
}

/* ========== 账号管理（扫码登录，与原版 DDTV 一致） ==========
 * _loginView: 'main' = 主视图(二维码/用户卡片), 'auth' = 授权视图(二维码), 'manage' = 授权管理(取消授权按钮)
 * 进入未登录主视图/auth 视图自动开扫；已登录主视图点头像进 manage */
function renderAccount() {
  const body=$('#editorBody'), acc=state.account||{logged:false};
  const anyLogin = !!(acc.logged);
  const view = state._loginView || 'main';
  const qrDesc = m => `使用 <b class="hl">哔哩哔哩手机APP</b> 扫码登录网页版`;

  // ===== auth 视图：点"去授权"进入，自动开扫 =====
  if(view === 'auth'){
    // 已登录则回主视图
    if(acc.web?.logged){ state._loginView='main'; state._authTarget=null; renderEditor(); return; }
    body.innerHTML=`<div class="view">
      ${subHeader('网页版登录', "state._loginView='main';state._authTarget=null;renderEditor()")}
      <h1>网页版登录</h1>
      <div class="login-section"><div class="qr-login-box">
        <div class="qr-desc">${qrDesc('web')}</div>
        <div class="qr-wrapper" id="qrWrapper">${qrLoadingHtml()}</div>
        <div class="qr-status" id="qrStatus">等待生成二维码…</div>
        <button class="btn btn-primary qr-refresh" onclick="openBiliApp()">${ic('check',15)} 跳转B站确认</button>
      </div></div>
    </div>`;
    startQr();
    return;
  }

  // ===== manage 视图：授权管理（点头像进入） =====
  if(view === 'manage' && anyLogin){
    const webOn = !!acc.web?.logged;
    body.innerHTML=`<div class="view">
      ${subHeader('授权管理', "state._loginView='main';state._authTarget=null;renderEditor()")}
      <h1>授权管理</h1>
      <div class="auth-manage-list">
        <div class="auth-manage-item ${webOn?'auth-active':''}">
          <div class="am-icon">${ic('globe',20)}</div>
          <div class="am-body"><div class="am-title">网页版登录</div>
          <div class="am-status ${webOn?'ok':''}">${webOn?'已登录':'未登录'}</div></div>
          ${webOn?`<button class="btn btn-danger am-btn" onclick="logoutWeb()">取消授权</button>`
            :`<button class="btn btn-sec am-btn" onclick="state._authTarget='web';state._loginView='auth';renderEditor()">去授权</button>`}
        </div>
      </div>
    </div>`;
    return;
  }

  // ===== 主视图：未登录 =====
  if(!anyLogin){
    clearSubnav();
    body.innerHTML=`<div class="view">
      <h1>账号管理</h1>
      <p class="lead">登录后可发送弹幕、导入关注列表、<br>录制付费直播、小心心挂机</p>
      <div class="login-section"><div class="qr-login-box">
        <div class="qr-desc">${qrDesc('web')}</div>
        <div class="qr-wrapper" id="qrWrapper">${qrLoadingHtml()}</div>
        <div class="qr-status" id="qrStatus">等待生成二维码…</div>
        <button class="btn btn-primary qr-refresh" onclick="openBiliApp()">${ic('check',15)} 跳转B站确认</button>
      </div></div>
    </div>`;
    startQr();
    return;
  }

  // ===== 主视图：已登录（点头像进 manage，下方显示关注列表） =====
  clearSubnav();
  body.innerHTML=`<div class="view">
    <h1>账号管理</h1>
    <div class="login-section">
      <div class="user-card compact-uc">
        <div class="uc-avatar-wrap" title="点击管理授权" onclick="state._loginView='manage';renderEditor()">
          ${avatarHtml(acc.face, acc.uname)}
        </div>
        <div class="uc-body">
          <div class="uc-name">${esc(acc.uname||'已登录')}</div>
          <div class="uc-mid">UID: ${acc.uid||'-'}</div>
          <div class="ls-pills">
            <span class="ls-pill ${acc.web?.logged?'on':''}"><span class="ls-pill-dot"></span>网页版</span>
          </div>
        </div>
      </div>
    </div>
    <h2>我的关注</h2>
    <div id="followSection">
      <div class="follow-groups" id="followGroups"></div>
      <div class="follow-toolbar">
        <span class="toolbar-text" id="followCountText">加载中…</span>
        <div class="inline-btns">
          <button class="btn btn-sec btn-sm" onclick="selectLiveFollow()">${ic('check',12)} 选在播</button>
          <button class="btn btn-primary btn-sm" onclick="importFollows()">${ic('plus',12)} 导入监控</button>
        </div></div>
      <div id="followList"><div class="sb-empty">加载中…</div></div>
    </div>
  </div>`;
  // 关注列表（账号页下方，登录后展示；默认「全部」）
  if (state.followGroupId==null) state.followGroupId = -1;
  renderFollowGroups();
  if (!state.followGroups.length) loadFollowGroups(); else loadFollowUsers();
}

function startQr() {
  state._qrActive = true;
  const wrap=$('#qrWrapper');
  if(wrap&&!wrap.querySelector('img')) wrap.innerHTML=qrLoadingHtml();
  AndroidBridge.startQrcodeLogin();
}
function refreshQr() {
  AndroidBridge.cancelQrcodeLogin();
  state.qrImage=null;
  const wrap=$('#qrWrapper');
  if(wrap) wrap.innerHTML=qrLoadingHtml();
  setTimeout(()=>startQr(),300);
}
/** 跳转B站确认（移植 BBDownAndroid openBiliApp，走 DDTV openAuthBrowser 桥） */
function openBiliApp() {
  debugLog('openBiliApp 开始');
  try {
    const r=JSON.parse(AndroidBridge.openAuthBrowser());
    debugLog('openBiliApp 返回: code=' + r.code + ' msg=' + r.msg);
    toast(r.msg, r.code<0?'err':'ok');
  } catch(e) {
    debugLog('openBiliApp JS异常: ' + e);
    toast('跳转失败：' + e, 'err');
  }
}
/** 取消网页版授权 */
function logoutWeb() {
  showModal({ title:'取消授权', msg:'确认取消网页版登录吗？',
    okText:'取消授权', onOk:()=>{ AndroidBridge.logout(); state.account=null; state.qrImage=null; state._qrActive=false; state._loginView='main'; state._authTarget=null; loadAccount(); } });
}
function logout() {
  showModal({ title:'退出登录', msg:'确认退出当前账号吗？\n退出后将无法发送弹幕和导入关注。',
    okText:'退出', onOk:()=>{ AndroidBridge.logout(); state.account=null; state.qrImage=null; state._qrActive=false; state._loginView='main'; state._authTarget=null; loadAccount(); } });
}

/* ========== 调试工具（统一入口：运行日志视图） ========== */
function debugLog(msg) {
  // 统一写入运行日志（经桥进 Kotlin Logger，再回推到日志视图），页面不再有独立调试区
  try { AndroidBridge.logDebug(msg); } catch(e){}
  console.log('[DDTV]', msg);
}

function renderQrcode(evt) {
  const wrap=$('#qrWrapper'), status=$('#qrStatus');
  if(!wrap||state.view!=='account') return;
  if(evt.image) {
    state.qrImage=evt.image;
    wrap.innerHTML=`<img src="${esc(evt.image)}">`;
    if(status){status.textContent=evt.message;status.className='qr-status scanning';}
    debugLog('二维码已生成');
  } else if(evt.message) {
    if(status) status.textContent=evt.message;
    debugLog('QR状态: '+evt.message);
    if(evt.message.includes('登录成功')) {
      state.qrImage=null; state._qrActive=false; state._loginView='main'; state._authTarget=null;
      if(status) status.className='qr-status success';
      debugLog('登录成功!加载账号信息...');
      setTimeout(()=>loadAccount(),500);
    }
    if(evt.message.includes('过期')) {
      state.qrImage=null;
      if(status) status.className='qr-status expired';
      wrap.innerHTML=`<div class="qr-overlay"><div class="qr-ov-text">二维码已过期</div>
        <button class="btn btn-primary qr-refresh" onclick="refreshQr()">刷新二维码</button></div>`;
      debugLog('二维码已过期');
    }
  }
}

/* ========== 设置 ========== */
function renderSettings() {
  const s=state.settings, body=$('#editorBody');
  body.innerHTML=`<div class="view">
    <div class="btn-row tight"><button class="btn btn-sec btn-sm" onclick="renderFaq()">${ic('alert',12)} 常见问题(保活/字幕/日志)</button></div>
    <h2>监控</h2>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">轮询间隔</div><div class="sw-desc">检测开播状态的频率</div></div>
      <div class="sw-right"><div id="setPoll"></div></div></div>
    <h2>录制</h2>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">默认自动录制</div><div class="sw-desc">新添加房间默认开播自动录制</div></div>
      <label class="switch"><input type="checkbox" id="setAuto" onchange="saveSettings()"><span class="slider"></span></label></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">录制模式</div><div class="sw-desc">FLV 直录(时间轴天然正确);HLS=分片续传更稳</div></div>
      <div class="sw-right"><div id="setMode"></div></div></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">FLV 断流续录</div><div class="sw-desc">断流重连后继续写同一文件;关=断流切新文件</div></div>
      <label class="switch"><input type="checkbox" id="setAppend" onchange="saveSettings()"><span class="slider"></span></label></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">默认清晰度</div></div>
      <div class="sw-right"><div id="setQn"></div></div></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">标题变化分割</div><div class="sw-desc">直播间改标题时切分文件</div></div>
      <label class="switch"><input type="checkbox" id="setSplitTitle" onchange="saveSettings()"><span class="slider"></span></label></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">按时长分割</div><div class="sw-desc">0 = 不分割（小时）</div></div>
      <input type="number" id="setSplitH" class="sw-input-num" min="0" max="24" value="0" onchange="saveSettings()"></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">按大小分割</div><div class="sw-desc">0 = 不分割（GB）</div></div>
      <input type="number" id="setSplitG" class="sw-input-num" min="0" max="100" value="0" onchange="saveSettings()"></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">自动转封装 MP4</div><div class="sw-desc">结束后自动 ffmpeg 转封装</div></div>
      <label class="switch"><input type="checkbox" id="setRemux" onchange="saveSettings()"><span class="slider"></span></label></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">结束后自动生成字幕</div><div class="sw-desc">录制结束把弹幕/礼物/SC 转成字幕(与弹幕 json 同名)</div></div>
      <label class="switch"><input type="checkbox" id="setDanmakuSrt" onchange="saveSettings()"><span class="slider"></span></label></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">字幕格式</div><div class="sw-desc">SRT 字幕;ASS 静态字幕;ASS 弹幕(滚动飞过,最像原版)</div></div>
      <div class="sw-right"><div id="setSubFormat"></div></div></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">修复后删除源文件</div><div class="sw-desc">修复/转码成功后删除原始文件</div></div>
      <label class="switch"><input type="checkbox" id="setRepDel" onchange="saveSettings()"><span class="slider"></span></label></div>
    <h2>账号</h2>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">小心心挂机</div><div class="sw-desc">上报观看时长，需登录</div></div>
      <label class="switch"><input type="checkbox" id="setHeart" onchange="saveSettings()"><span class="slider"></span></label></div>
    <h2>通知</h2>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">开播/下播提醒</div><div class="sw-desc">全局开关，房间详情可单独关闭</div></div>
      <label class="switch"><input type="checkbox" id="setRemind" onchange="saveSettings()"><span class="slider"></span></label></div>
    <h2>弹幕</h2>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">弹幕屏蔽词</div><div class="sw-desc">用 | 分隔，如：广告|代购</div></div>
      <input type="text" id="setBlock" class="sw-input-txt" placeholder="广告|代购" onchange="saveSettings()"></div>
    <h2>录制文件</h2>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">文件名格式</div><div class="sw-desc">空=默认;关键字 {ROOMID} {NAME} {TITLE} {DATE} {TIME} {YYYY} {MM} {DD} {HH} {mm} {SS} {FFF}</div></div>
      <input type="text" id="setFmt" class="sw-input-txt" placeholder="{DATE}_{TIME}_{TITLE}" onchange="saveSettings()"></div>
    <h2>录制目录</h2>
    <div class="switch-row">
      <div class="sw-text"><div class="sw-label">录制目录</div><div class="sw-desc">录制的保存位置（绝对路径）</div></div>
      <input type="text" id="setOutputDir" class="sw-input-txt" placeholder="/storage/emulated/0/DDTV/rec" onchange="saveOutputDir()"></div>
    <div class="mono-block" id="outputDirText">${esc(s?s.outputDir:'')}</div>
    <h2>权限</h2>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">通知权限</div><div class="sw-desc">开播/下播提醒推送</div></div>
      <div class="sw-right"><span class="perm-state" id="permNotification">…</span>
      <button class="btn btn-sec btn-sm" onclick="requestPerm('notification')">授权</button></div></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">存储权限</div><div class="sw-desc">所有文件访问(自定义录制目录需要)</div></div>
      <div class="sw-right"><span class="perm-state" id="permStorage">…</span>
      <button class="btn btn-sec btn-sm" onclick="requestPerm('storage')">授权</button></div></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">电池优化白名单</div><div class="sw-desc">后台持续录制不被系统休眠</div></div>
      <div class="sw-right"><span class="perm-state" id="permBattery">…</span>
      <button class="btn btn-sec btn-sm" onclick="requestPerm('battery')">授权</button></div></div>
    <h2>保活</h2>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">开机自启</div><div class="sw-desc">手机重启后自动恢复直播监控后台服务</div></div>
      <label class="switch"><input type="checkbox" id="setAutoStart" onchange="saveSettings()"><span class="slider"></span></label></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">屏幕常亮</div><div class="sw-desc">保持屏幕常亮,方便查看录制/弹幕状态</div></div>
      <label class="switch"><input type="checkbox" id="setKeepScreen" onchange="saveSettings()"><span class="slider"></span></label></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">后台保活设置</div><div class="sw-desc">系统应用信息页允许自启动/后台运行,详见「常见问题」</div></div>
      <div class="sw-right"><button class="btn btn-sec btn-sm" onclick="openAppBackgroundSettings()">去设置</button></div></div>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">后台任务锁定</div><div class="sw-desc">在最近任务中锁定本应用，防止系统清理后台时终止录制</div></div></div>
    <h2>调试</h2>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">调试服务器</div><div class="sw-desc">局域网可查看运行状态与日志，默认关闭</div></div>
      <label class="switch"><input type="checkbox" id="setDebug" onchange="toggleDebugServer()"><span class="slider"></span></label></div>
    <div class="btn-row tight"><button class="btn btn-sec btn-sm" onclick="renderCrashLogs()">${ic('alert',12)} 崩溃日志</button></div>
    <h2>更新</h2>
    <div class="switch-row"><div class="sw-text"><div class="sw-label">启动时检查更新</div><div class="sw-desc">启动后静默检查，发现新版本才提示</div></div>
      <label class="switch"><input type="checkbox" id="setAutoUpdate" onchange="saveSettings()"><span class="slider"></span></label></div>
    <div class="btn-row tight"><button class="btn btn-sec btn-sm" onclick="checkUpdateNow()">${ic('refresh',12)} 检查更新</button>
      <span class="toolbar-text">当前版本 ${esc(state.version||'')}</span></div></div>`;
  // 自定义下拉(替代原生 select)
  renderCSelect($('#setPoll'),
    [{v:'5',label:'5 秒'},{v:'10',label:'10 秒'},{v:'15',label:'15 秒'},{v:'30',label:'30 秒'},{v:'60',label:'60 秒'}],
    String(s?s.pollInterval:15), ()=>saveSettings());
  renderCSelect($('#setQn'),
    Object.entries(qnLabels).map(([k,v])=>({v:k,label:v})),
    String(s?s.quality:10000), ()=>saveSettings());
  renderCSelect($('#setMode'),
    [{v:'flv',label:'FLV 直录'},{v:'hls',label:'HLS'},{v:'auto',label:'自动（有 HLS 用 HLS）'}],
    String(s?s.recordMode||'flv':'flv'), ()=>saveSettings());
  renderCSelect($('#setSubFormat'),
    [{v:'srt',label:'SRT 字幕'},{v:'ass',label:'ASS 字幕'},{v:'assdm',label:'ASS 弹幕(滚动)'}],
    String(s?s.danmakuSubFormat||'srt':'srt'), ()=>saveSettings());
  if(s) { $('#setAuto').checked=s.autoRecord;
    $('#setAppend').checked=s.flvAppendOnReconnect!==false;
    $('#setSplitTitle').checked=s.splitByTitle; $('#setSplitH').value=s.splitSeconds?Math.round(s.splitSeconds/3600):0;
    $('#setSplitG').value=s.splitSizeMB?Math.round(s.splitSizeMB/1024):0; $('#setRemux').checked=s.remuxAfterLive; $('#setRepDel').checked=s.repairDeleteSource!==false; $('#setHeart').checked=s.watchHeartbeat;
    $('#setRemind').checked=s.remindLive; $('#setBlock').value=s.blockBarrage||''; $('#setFmt').value=s.fileNameFormat||'';
    $('#setAutoUpdate').checked=!!s.autoUpdate;
    $('#setDebug').checked=!!s.debugServer;
    $('#setKeepScreen').checked=!!s.keepScreenOn;
    $('#setAutoStart').checked=!!s.autoStart;
    $('#setDanmakuSrt').checked=!!s.danmakuSrt;
    $('#setOutputDir').value=s.outputDir||''; }
  refreshPermStatus();
}
function saveSettings() {
  const s={pollInterval:Number($('#setPoll').dataset.value),autoRecord:$('#setAuto').checked,quality:Number($('#setQn').dataset.value),
    recordMode:$('#setMode').dataset.value,
    splitByTitle:$('#setSplitTitle').checked,splitSeconds:Number($('#setSplitH').value)*3600,splitSizeMB:Number($('#setSplitG').value)*1024,
    remuxAfterLive:$('#setRemux').checked,watchHeartbeat:$('#setHeart').checked,remindLive:$('#setRemind').checked,
    repairDeleteSource:$('#setRepDel').checked,flvAppendOnReconnect:$('#setAppend').checked,
    blockBarrage:$('#setBlock').value.trim(),fileNameFormat:$('#setFmt').value.trim(),
    autoUpdate:$('#setAutoUpdate').checked,
    debugServer:$('#setDebug').checked,
    keepScreenOn:$('#setKeepScreen').checked,
    autoStart:$('#setAutoStart').checked,
    danmakuSrt:$('#setDanmakuSrt').checked,
    danmakuSubFormat:$('#setSubFormat').dataset.value||'srt'};
  const r=JSON.parse(AndroidBridge.setSettings(JSON.stringify(s)));
  toast(r.msg, r.code<0?'err':'ok'); if(r.code>0){state.settings=s;updateStatusbar();}
}

function pickOutputDir() {
  try { AndroidBridge.pickOutputDir(); } catch(e){ toast('无法打开目录选择器: '+e,'err'); }
}

function openAppBackgroundSettings() {
  try { const r = JSON.parse(AndroidBridge.openAppBackgroundSettings()); toast(r.msg, r.code<0?'err':'ok'); }
  catch(e) { toast('无法打开：'+e,'err'); }
}

/* ========== 常见问题(FAQ) ========== */
const FAQS = [
  {q:'如何让录制不被系统杀掉、稳定持续录制？', a:'坚持五步保活链：\n1. 通知栏常驻前台服务(录制/监控时不退出)\n2. 设置-权限-「电池优化白名单」点授权(忽略电池优化)\n3. 设置-保活-「开机自启」开启\n4. 设置-保活-「后台保活设置」进入系统应用信息页,允许自启动、后台运行、后台弹出界面\n5. 在「最近任务」长按 DDTV 卡片并锁定任务\n五步都做到,长直播基本不会再被系统杀掉。'},
  {q:'什么是「小锁」？怎么锁？', a:'Android 的「最近任务」里,每个应用卡片通常有个小锁图标(有的在卡片右下角或长按弹出)。在最近任务里长按 DDTV 卡片,选择「锁定/保持运行」,系统清理后台和省电时会跳过已锁定的应用。配合前台服务,即使屏幕熄灭、后台清理,DDTV 也会继续录制。'},
  {q:'国产手机(如小米/红米)杀后台特别狠,怎么办？', a:'以 MIUI/HyperOS 为例:设置-应用设置-应用管理 找到 DDTV→省电策略选「无限制」;「自启动」允许;「后台弹出界面」允许。再把 DDTV 在最近任务里上锁。部分机型还需在「电池-应用智能省电」中排除 DDTV。不同 ROM 入口略有差异,认准「允许自启动 + 后台无限制 + 上锁」三件套即可。'},
  {q:'录制的弹幕/礼物/字幕存在哪？', a:'都在录制目录下:<房名>/<日期>/ 目录:danmu_*.json(全量弹幕/礼物/SC/上舰)、danmu_*_弹幕.csv、danmu_*_礼物.csv、danmu_*_上舰.csv、danmu_*_SC.csv。若开启「结束后自动生成字幕」,还会生成 danmu_*.srt 或 .ass(格式在设置里选)。'},
  {q:'怎样把弹幕变成能挂到视频的字幕？', a:'两种方式:\n1. 自动:设置-录制-「结束后自动生成字幕」开启,字幕格式选 SRT/ASS,录制结束自动生成\n2. 手动:打开「修复工具」页,选中某个录像文件,点「导出字幕(.srt或.ass)」,生成与录像同名的字幕,可分享。'},
  {q:'录像中途断流后,文件变小或中间丢了一段？', a:'直播断流(网络波动/CDN 线路切换)时,App 会重连:设置-录制-「FLV 断流续录」开启则续写同一文件(推荐),关闭则切新文件。若已生成的片段有损伤,可用「修复工具-修复损坏」。若丢失明显,多为当时网络不稳导致,建议在稳定 WiFi 下录制。'},
  {q:'日志在哪看？怎么导出？', a:'「运行日志」页:顶部可切「历史文件」(按天,保留7天)、「崩溃日志」(闪退堆栈),还能「保存到文件」再分享。调试服务器(19864)功能也保留,作为备用。'},
  {q:'录制完没自动转 mp4？', a:'确认设置-录制-「自动转封装 MP4」已开启。开启后录制结束/下播自动用 ffmpeg 转封装。也可以打开「修复工具」选中录像→「快速转封装」。'},
  {q:'在线听直播是什么？会占用录制吗？', a:'「听直播」只播放声音,不占用录制通道、不写文件,可边录边听。它和录制各自独立取流,互不影响。'},
];
function renderFaq() {
  const body = $('#editorBody');
  subHeader('常见问题', "renderEditor()");
  body.innerHTML = `<div class="log-toolbar">
      <span class="toolbar-text">点击问题展开查看答案（共 ${FAQS.length} 条）</span></div>
    <div class="log-panel">${FAQS.map((f,i)=>`
      <div class="crash-item faq-item" onclick="this.classList.toggle('open')">
        <div class="crash-head"><span class="crash-name">${i+1}. ${esc(f.q)}</span></div>
        <div class="crash-body">${esc(f.a).replace(/\n/g,'<br>')}</div>
      </div>`).join('')}</div>`;
}

/* ========== 权限（设置页手动授权） ========== */
const PERM_LABELS = { notification:'通知', storage:'存储', battery:'电池' };
function requestPerm(type) {
  try {
    // 已授权时系统不会再弹窗（Android 行为），直接提示，避免点了没反应
    const st = JSON.parse(AndroidBridge.getPermissionStatus(type));
    if (st.granted) { toast((PERM_LABELS[type]||type)+'权限已开启','ok'); refreshPermStatus(); return; }
    AndroidBridge.requestPermission(type);
  } catch(e){ toast('无法请求权限: '+e,'err'); }
  setTimeout(refreshPermStatus, 1500);  // 从系统授权页回来后刷新状态
}
function refreshPermStatus() {
  ['notification','storage','battery'].forEach(t => {
    const el = $('#perm' + t[0].toUpperCase() + t.slice(1));
    if (!el) return;
    try {
      const r = JSON.parse(AndroidBridge.getPermissionStatus(t));
      el.textContent = r.granted ? '已开启' : '未开启';
      el.className = 'perm-state ' + (r.granted ? 'on' : 'off');
    } catch(e) { el.textContent = '未知'; }
  });
}

/* ========== 调试服务器开关 ========== */
function toggleDebugServer() {
  const on = $('#setDebug').checked;
  try {
    const r = JSON.parse(AndroidBridge.setDebugServer(on));
    if (state.settings) state.settings.debugServer = on;
    toast(r.msg||('已'+(on?'开启':'关闭')+'调试服务器'), r.code<0?'err':'ok');
  } catch(e) { $('#setDebug').checked = !on; toast('操作失败: '+e,'err'); }
}

/* ========== 自动更新（GitHub Releases） ========== */
let _updating = false;
/* 检查更新（BBDown 同款）：silent=true 由启动时静默调用，无新版本/失败不打扰用户 */
function checkUpdateNow(silent) {
  if (_updating) return;
  const repo = ((state.settings&&state.settings.updateRepo)||'').trim();
  if (!repo) { if (!silent) toast('更新仓库未配置，暂无法检查更新','warn'); return; }
  _updating = true;
  if (!silent) toast('正在检查更新…');
  try { AndroidBridge.checkUpdate(repo, !!silent); }
  catch(e){ _updating = false; if (!silent) toast('检查失败: '+e,'err'); }
}
/** 保存录制目录（文字输入，参照 BBDownAndroid） */
function saveOutputDir() {
  const v = $('#setOutputDir').value.trim();
  if (!v) { toast('请输入目录路径','warn'); return; }
  const r = JSON.parse(AndroidBridge.setOutputDir(v));
  if (r.code > 0) {
    if (state.settings) state.settings.outputDir = v;
    const t = $('#outputDirText'); if (t) t.textContent = v;
    toast(r.msg||'已保存','ok');
  } else {
    toast(r.msg||'目录无效','err');
    $('#setOutputDir').value = state.settings ? state.settings.outputDir : '';
    if (r.msg && r.msg.includes('权限')) {
      // 引导授权：Android 11+ 跳「所有文件访问」设置页
      showModal({ title:'需要存储权限', msg: r.msg+'\n点击「去授权」跳转设置页，授权后重新输入目录保存。',
        okText:'去授权', onOk:()=>{ try{ AndroidBridge.requestStoragePermission(); }catch(e){ toast('无法打开授权页','err'); } } });
    }
  }
}

/* ========== 日志 ========== */
function loadLogs() {
  try{const list=JSON.parse(AndroidBridge.getLogs(200)), panel=$('#logPanel'); if(!panel) return;
    panel.innerHTML=''; list.forEach(l=>appendLogLine(l)); scrollLogBottom(true);}catch(e){}
}
function scrollLogBottom(force) {
  // 滚动容器是 #editorBody（.log-panel 自身不产生滚动条，滚它无效）
  const sc=$('#editorBody'); if(!sc) return;
  if (force || sc.scrollHeight - sc.scrollTop - sc.clientHeight < 60) sc.scrollTop = sc.scrollHeight;
}
function appendLogLine(l) {
  const panel=$('#logPanel'); if(!panel) return;
  const r=state.rooms.find(x=>x.roomId===l.roomId);
  const el=document.createElement('div'); el.className='log-line '+(l.level||'');
  el.innerHTML=`<span class="lt">[${fmtTime(l.time)}]</span> <span class="lr">[${esc(r?r.name:'系统')}]</span> ${esc(l.msg)}`;
  panel.appendChild(el); while(panel.children.length>300) panel.removeChild(panel.firstChild);
  scrollLogBottom();
}
function appendLogDirect(roomId, level, msg) {
  const panel=$('#logPanel'); if(!panel) return;
  const r=state.rooms.find(x=>x.roomId===roomId);
  const el=document.createElement('div'); el.className='log-line '+(level||'');
  el.innerHTML=`<span class="lt">[${fmtTime(Date.now())}]</span> <span class="lr">[${esc(r?r.name:'系统')}]</span> ${esc(msg)}`;
  panel.appendChild(el); while(panel.children.length>300) panel.removeChild(panel.firstChild);
  scrollLogBottom();
}

/* ========== 日志保存/分享（BBDownAndroid 同款） ========== */
function saveLogsToFile() {
  try {
    const r = JSON.parse(AndroidBridge.saveLogsToFile());
    if (r.code > 0 && r.path) {
      toast('日志已保存','ok');
      showModal({ title:'日志已保存', msg:r.path, okText:'分享', cancelText:'关闭',
        onOk: () => { try { const s=JSON.parse(AndroidBridge.shareLogFile(r.path)); toast(s.msg, s.code<0?'err':'ok'); } catch(e2){ toast('分享失败：'+e2,'err'); } } });
    } else {
      toast(r.msg||'保存失败','err');
    }
  } catch(e) { toast('保存日志失败：'+e,'err'); }
}
function clearDebugLogs() {
  try {
    const r = JSON.parse(AndroidBridge.clearDebugLogs());
    toast(r.msg, r.code<0?'err':'ok');
    loadLogs();
  } catch(e) { toast('清除失败：'+e,'err'); }
}

/* ========== 崩溃日志（BBDownAndroid 同款） ========== */
function renderCrashLogs() {
  const body = $('#editorBody');
  subHeader('崩溃日志', "renderEditor()");
  body.innerHTML = `<div class="log-toolbar">
      <span class="toolbar-text">崩溃日志（点击展开/折叠）</span>
      <button class="btn btn-sec btn-sm" onclick="renderCrashLogs()">${ic('refresh',12)} 刷新</button>
      <button class="btn btn-danger-sec btn-sm" onclick="clearCrashLogs()">${ic('trash',12)} 清除全部</button></div>
    <div class="log-panel" id="crashPanel"><div class="loading-pulse">${spinIcon()} 加载中…</div></div>`;
  try {
    const logs = JSON.parse(AndroidBridge.getCrashLogs());
    const panel = $('#crashPanel');
    if (!logs || !logs.length) {
      panel.innerHTML = '<div class="detail-empty"><div class="empty-hint-title">暂无崩溃日志</div>' +
        '<div class="empty-hint-sub">应用正常运行，这里会保持干净</div></div>';
      return;
    }
    panel.innerHTML = logs.map(l => {
      const d = new Date(l.time), ts = fmtDate(l.time)+' '+('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2);
      return `<div class="crash-item" onclick="this.classList.toggle('open')">
        <div class="crash-head">
          <span class="crash-name">${esc(l.filename)}</span>
          <span class="crash-meta">${ts} · ${(l.size/1024).toFixed(1)}KB</span>
          <button class="btn btn-sec btn-sm" onclick="event.stopPropagation();shareCrashLog('${esc(l.path)}')">${ic('share',12)} 分享</button>
          <button class="btn btn-danger-sec btn-sm" onclick="event.stopPropagation();deleteCrashLog('${esc(l.filename)}')">${ic('trash',12)} 删除</button>
        </div>
        <pre class="crash-body">${esc(l.content)}</pre>
      </div>`;
    }).join('');
  } catch(e) {
    $('#crashPanel').innerHTML = '<div class="detail-empty"><div class="empty-hint-title">加载失败</div>' +
      '<div class="empty-hint-sub">'+esc(e.message||e)+'</div></div>';
  }
}
function shareCrashLog(path) {
  try { const r = JSON.parse(AndroidBridge.shareLogFile(path)); toast(r.msg, r.code<0?'err':'ok'); }
  catch(e) { toast('分享失败：'+e,'err'); }
}
function deleteCrashLog(name) {
  try { const r = JSON.parse(AndroidBridge.deleteCrashLog(name)); toast(r.msg, r.code<0?'err':'ok'); renderCrashLogs(); }
  catch(e) {}
}
function clearCrashLogs() {
  try { const r = JSON.parse(AndroidBridge.clearCrashLogs()); toast(r.msg, r.code<0?'err':'ok'); renderCrashLogs(); }
  catch(e) {}
}

/* ========== 历史日志文件（App 内管理：列出/查看/分享/删除按天日志） ========== */
function renderLogFiles() {
  const body = $('#editorBody');
  subHeader('历史日志文件', "renderEditor()");
  body.innerHTML = `<div class="log-toolbar">
      <span class="toolbar-text">按天自动落盘的日志文件（保留7天，点击可查看，可分享/删除）</span>
      <button class="btn btn-sec btn-sm" onclick="renderLogFiles()" style="margin-left:auto">${ic('refresh',12)} 刷新</button></div>
    <div class="log-panel" id="logFilePanel"><div class="loading-pulse">${spinIcon()} 加载中…</div></div>`;
  try {
    const files = JSON.parse(AndroidBridge.getLogFiles());
    const panel = $('#logFilePanel');
    if (!files || !files.length) {
      panel.innerHTML = '<div class="detail-empty"><div class="empty-hint-title">暂无历史日志文件</div>' +
        '<div class="empty-hint-sub">每天产生的日志会自动保存到“日志”目录</div></div>';
      return;
    }
    panel.innerHTML = files.map(l => {
      const d = new Date(l.time), ts = fmtDate(l.time)+' '+('0'+d.getHours()).slice(-2)+':'+('0'+d.getMinutes()).slice(-2);
      return `<div class="crash-item" onclick="viewLogFile('${esc(l.filename)}')">
        <div class="crash-head">
          <span class="crash-name">${esc(l.filename)}</span>
          <span class="crash-meta">${ts} · ${(l.size/1024).toFixed(1)}KB · 点击查看</span>
          <button class="btn btn-sec btn-sm" onclick="event.stopPropagation();shareLogFile('${esc(l.path)}')">${ic('share',12)} 分享</button>
          <button class="btn btn-danger-sec btn-sm" onclick="event.stopPropagation();deleteLogFile('${esc(l.filename)}')">${ic('trash',12)} 删除</button>
        </div>
      </div>`;
    }).join('');
  } catch(e) {
    $('#logFilePanel').innerHTML = '<div class="detail-empty"><div class="empty-hint-title">加载失败</div>' +
      '<div class="empty-hint-sub">'+esc(e.message||e)+'</div></div>';
  }
}
function viewLogFile(filename) {
  try {
    const r = JSON.parse(AndroidBridge.readLogFile(filename));
    if (r.code < 0) { toast(r.msg||'读取失败','err'); return; }
    const lines = r.content.split('\n').length;
    showModal({ title: r.filename+'（'+lines+' 行）', msg: (r.content||'(空)').slice(0,4000),
      okText:'分享', cancelText:'关闭',
      onOk: () => { try { const s=JSON.parse(AndroidBridge.shareLogFile(r.path)); toast(s.msg, s.code<0?'err':'ok'); } catch(e2){ toast('分享失败：'+e2,'err'); } } });
  } catch(e) { toast('查看失败：'+e,'err'); }
}
function shareLogFile(path) {
  try { const r = JSON.parse(AndroidBridge.shareLogFile(path)); toast(r.msg, r.code<0?'err':'ok'); }
  catch(e) { toast('分享失败：'+e,'err'); }
}
function deleteLogFile(name) {
  try {
    const r = JSON.parse(AndroidBridge.deleteLogFile(name));
    toast(r.msg, r.code<0?'err':'ok');
    if (r.code > 0) renderLogFiles();
  } catch(e) { toast('删除失败：'+e,'err'); }
}

/* ========== 状态栏 ========== */
function updateStatusbar() {
  const rec=state.rooms.filter(r=>r.recState==='recording').length;
  $('#sbRec').textContent=rec+' 录制中';
  $('#sbDot').className='sb-dot'+(rec>0?' run':'');
  $('#sbWatch').textContent='监控 '+state.rooms.length+' 房间';
  const s=state.settings; if(s){$('#sbPoll').textContent='轮询 '+s.pollInterval+'s';$('#sbHeart').style.display=s.watchHeartbeat?'':'none';}
  const acc=state.account; $('#sbRight').textContent=(acc&&acc.logged?acc.uname:'未登录')+' · v'+(state.version||'');
}

/* ========== 原生回调 ========== */
function onNativeEvent(evt) {
  // 单条脏数据不应中断整个事件处理
  try {
    state.lastEventTs = Date.now();  // 事件停滞检测用
    switch(evt.type) {
    case 'rooms_changed': refreshRooms(); if(state.view==='history') renderHistoryPanel(); break;
    case 'room_update': refreshRoomById(evt.roomId); break;
    case 'danmaku':
      // 批量推送(items 数组,300ms 窗口合并):批量渲染免逐条滚动回流
      if (evt.items) {
        const stream=$('#dmStream');
        for (let i=0;i<evt.items.length;i++) appendDanmaku(evt.items[i],true);
        if (stream && evt.items.length) stream.scrollTop=stream.scrollHeight;
      } else if (evt.item) appendDanmaku(evt.item);
      break;
    case 'danmaku_status':
      // 弹幕连接状态：失败时可点击重连
      updateDmConn(!!evt.connected, evt.msg);
      break;
    case 'log': if(state.view==='log') appendLogDirect(evt.roomId,evt.level,evt.msg); break;
    case 'account_changed': loadAccount(); break;
    case 'qrcode': renderQrcode(evt); break;
    case 'files_changed': loadFiles(); if(state.view==='tools') renderToolsPanel(); else if(state.view==='files'){ updateFileListPageSilent(); renderSidebar(); } break;
    case 'file_picked': if(state.view==='tools') onFilePicked(evt.path); break;
    case 'follow_groups':
      // 异步分组加载完成；只有集合变化才重渲染，避免空分组时 renderEditor→renderAccount 无限递归
      {
        const groups = evt.groups || [];
        const changed = JSON.stringify(groups) !== JSON.stringify(state.followGroups);
        state.followGroups = groups;
        if (changed && state.view === 'account') renderEditor();
      }
      break;
    case 'follows_loaded':
      // 丢弃过期响应（请求序号不匹配最新一次），防止乱序覆盖
      if (evt.reqId !== _followReqSeq) break;
      // 异步关注加载完成（tagid 匹配当前选中分组才应用，防止切分组竞态）
      if (evt.tagid === (state.followGroupId ?? 0)) {
        state.followUsers = evt.users || [];
        if (state.view === 'account') renderFollowUsers();
      }
      break;
    case 'output_dir_changed':
      // 录制目录切换成功：更新设置页显示
      if (state.settings) state.settings.outputDir = evt.path;
      {
        const odt = $('#outputDirText');
        if (odt && state.view === 'settings') odt.textContent = evt.path;
      }
      toast('录制目录已切换','ok');
      break;
    case 'listen_status':
      // 在线听直播状态：更新本地状态 + 提示 + 刷新详情按钮文案
      {
        const prevLabel = state._listenLabel;
        state.listen = { active:!!evt.active, playing:!!evt.playing, roomId:evt.roomId||0, name:evt.name||'' };
        const label = evt.label || (evt.active ? (evt.playing ? '正在收听' : '已暂停') : '');
        if (label && label !== prevLabel) {
          state._listenLabel = label;
          if (label === '缓冲中…') break;   // 缓冲频繁，不打扰
          toast(label, /失败|出错|结束/.test(label) ? 'err' : 'ok');
        }
        if (state._detailOpen && state.currentRoom === (evt.roomId||0)) updateRoomDetail(state.currentRoom);
        if (evt.active === false) state._listenLabel = '';
        syncListenCtl();
      }
      break;
    case 'repair_task_update':
      // 修复任务列表变化（全量刷新，签名对比防闪烁）
      renderRepairTasks(evt.tasks || []);
      break;
    case 'update_result':
      // 自动更新检查结果（silent=启动静默检查：无新版本/失败不打扰）
      _updating = false;
      if (!evt.ok) { if (!evt.silent) toast('检查更新失败: '+(evt.msg||'网络错误'),'err'); break; }
      if (!evt.hasUpdate) { if (!evt.silent) toast('当前已是最新版本 ('+evt.current+')','ok'); break; }
      showModal({ title:'发现新版本 '+evt.latest, msg: '当前版本: '+evt.current+'\n\n'+(evt.note||'').slice(0,400),
        okText:'前往下载', onOk: () => { try{ AndroidBridge.openUrl(evt.url); }catch(e){ toast('无法打开浏览器','err'); } } });
      break;
  }
  } catch(e) { console.error('[DDTV] onNativeEvent', e); }
}

/**
 * 轮询刷新：只更新数据与动态文本，不重建编辑器 DOM（避免整页闪烁/弹幕面板重置）。
 * 列表首次渲染保留入场动画，后续轮询静默更新（no-anim）。
 */
function refreshRooms() {
  try{state.rooms=JSON.parse(AndroidBridge.getRooms());}catch(e){state.rooms=[];}
  updateStatusbar();
  if (state.view !== 'explorer') return;  // 其余视图不重建，避免闪烁
  if (state._roomSearch) return;  // 搜索UP页：不参与列表静默刷新，避免重渲染清空输入
  if (state._detailOpen && state.currentRoom && state.rooms.find(r=>r.roomId===state.currentRoom)) {
    updateRoomDetail(state.currentRoom);  // 详情页增量更新
  } else {
    updateRoomListPage();  // 列表页静默刷新
  }
  if (window.innerWidth > 991) renderSidebar();
}
/** 列表页静默刷新（不重播动画、保持滚动） */
function updateRoomListPage() {
  if (state._roomSearch) return;  // 搜索UP页守卫
  const body = $('#editorBody');
  const list = body.querySelector('.page-list');
  if (!list) { renderEditor(); return; }
  list.classList.add('no-anim');
  const st = list.scrollTop;
  list.innerHTML = buildRoomListHtml();
  list.scrollTop = st;
  bindRoomList(list);
}
function refreshRoomById(rid) {
  try{state.rooms=JSON.parse(AndroidBridge.getRooms());}catch(e){return;}
  updateStatusbar();
  if (state.view === 'explorer') {
    if (window.innerWidth > 991) renderSidebar();
    if (state._detailOpen && state.currentRoom === rid) updateRoomDetail(rid);
    else if (!state._detailOpen) updateRoomListPage();
  } else if (state.view === 'danmaku' && window.innerWidth > 991) renderSidebar();
}

/**
 * 详情页增量更新（轮询用）：只改动态文本/类名，不重建 DOM。
 * 当前编辑器不是详情页（空态等）时回退重建。
 */
function updateRoomDetail(rid) {
  const r = state.rooms.find(x=>x.roomId===rid);
  const container = $('#editorBody');
  if (!container) return;
  if (!r || !container.querySelector('.head-row')) { renderEditor(); return; }
  const isLive = r.liveStatus===1||r.liveStatus===2, rec = r.recState==='recording';
  // 状态药丸
  const pill = container.querySelector('.detail-meta .state-pill');
  if (pill) { pill.className = 'state-pill '+(isLive?'state-running':'state-offline'); pill.textContent = isLive?'● LIVE':'● OFFLINE'; }
  // 标题
  const title = container.querySelector('.detail-title');
  if (title) title.textContent = r.name||('房间 '+r.roomId);
  // 直播标题
  const meta = container.querySelector('.detail-meta');
  if (meta && meta.lastChild) meta.lastChild.textContent = r.title||'未开播';
  // 统计卡数值（已录制/速度/时长/弹幕）
  const values = container.querySelectorAll('.stat-card .sc-value');
  if (values.length >= 4) {
    values[0].textContent = fmtSize(r.recSize);
    values[1].textContent = fmtSpeed(r.recSpeed)||'-';
    values[2].textContent = r.recState==='recording'&&r.recStartTime>0 ? fmtDur(Date.now()-r.recStartTime) : '-';
    values[3].textContent = r.danmakuCount||0;
  }
  // 录制按钮行（只替换第一个按钮：停止 ⇄ 立即录制，其余按钮保留）
  const btnRow = container.querySelector('.btn-row.tight');
  if (btnRow) {
    const first = btnRow.querySelector('.btn:first-child');
    const isStop = first ? first.textContent.includes('停止') : false;
    if (rec !== isStop) {
      const neu = document.createElement('button');
      neu.className = rec ? 'btn btn-danger' : 'btn btn-primary';
      neu.innerHTML = rec ? `${ic('stop',14)} 停止录制` : `${ic('record',14)} 立即录制`;
      neu.onclick = () => rec ? stopRec(r.roomId) : startRec(r.roomId);
      btnRow.replaceChild(neu, first);
    }
  }
  // 录制文件/错误
  const recFile = container.querySelector('.rec-file');
  if (r.recFile) {
    if (recFile) recFile.textContent = r.recFile;
    else {
      const div = document.createElement('div');
      div.className = 'mono-block rec-file';
      div.textContent = r.recFile;
      btnRow && btnRow.parentNode && btnRow.parentNode.insertBefore(div, btnRow.nextSibling);
    }
  } else if (recFile) recFile.remove();
  const errLine = container.querySelector('.error-line');
  if (r.lastError) {
    if (errLine) errLine.textContent = r.lastError;
    else {
      const div = document.createElement('div');
      div.className = 'error-line';
      div.textContent = r.lastError;
      const rf = container.querySelector('.rec-file');
      (rf ? rf.parentNode : container.querySelector('.stat-grid')).appendChild(div);
    }
  } else if (errLine) errLine.remove();
}

/* ========== 操作 ========== */
function startRec(rid) {
  // 未开播时按钮不再 disabled，点击给出明确提示
  const r0 = state.rooms.find(x=>x.roomId===rid);
  if (r0 && !(r0.liveStatus===1||r0.liveStatus===2)) { toast('房间未开播，无法立即录制','warn'); return; }
  const r=JSON.parse(AndroidBridge.startRecordNow(rid));
  toast(r.msg, r.code<0?'err':'ok');
}
function stopRec(rid) { AndroidBridge.stopRecordNow(rid); toast('已请求停止录制'); }
function refreshRoom(rid) { AndroidBridge.refreshRoom(rid); toast('已刷新'); }
function removeRoom(rid) {
  const r = state.rooms.find(x => x.roomId === rid);
  hideRoomMenu();  // 兜底：确认弹窗前先收起长按菜单
  showModal({ title:'移除房间', msg:`确认移除「${r?r.name:'房间 '+rid}」吗？\n移除后将停止录制和弹幕监听。`,
    okText:'移除', onOk:()=>{ AndroidBridge.removeRoom(rid); state.currentRoom=null; refreshRooms(); toast('已移除'); } });
}
function setAutoRecord(rid,on) { AndroidBridge.setAutoRecord(rid,on); }
function setRemind(rid,on) { AndroidBridge.setRemind(rid,on); }
function openLive(rid) { const r=JSON.parse(AndroidBridge.openLiveRoom(rid)); toast(r.msg, r.code<0?'err':'ok'); }
function isListening(rid){ return state.listen && state.listen.active && state.listen.roomId === rid; }
function toggleListen(rid){
  const r = state.rooms.find(x=>x.roomId===rid); if(!r) return;
  if (isListening(rid)) {
    try{ AndroidBridge.stopListen(); }catch(e){}
    state.listen = {active:false,playing:false,roomId:0,name:''};
    state._listenLabel = '';
    toast('已停止收听','ok');
    if (state._detailOpen && state.currentRoom === rid) updateRoomDetail(rid);
    syncListenCtl();  // 立即隐藏 tabs 控制器
    return;
  }
  let res = { code:-1, msg:'启动失败' };
  try { res = JSON.parse(AndroidBridge.startListen(rid)); } catch(e){ res = { code:-1, msg:'启动失败: '+e }; }
  toast(res.msg, res.code<0?'err':(res.code===0?'warn':'ok'));
  if (res.code < 0) return;
  state.listen = {active:true, playing:false, roomId:rid, name:r.name||''};
  if (state._detailOpen && state.currentRoom === rid) updateRoomDetail(rid);
}
function initListen(){
  try {
    const s = JSON.parse(AndroidBridge.getListenStatus());
    state.listen = {active:!!s.active, playing:!!s.playing, roomId:s.roomId||0, name:s.name||''};
    state._listenLabel = state.listen.active ? (state.listen.playing?'正在收听':'已暂停') : '';
  } catch(e){ state.listen = {active:false,playing:false,roomId:0,name:''}; }
}
/* tabs 栏小控制器：只显示播放状态（播放中/已暂停），点击切换，极简不占空间 */
function syncListenCtl() {
  const bar = $('#tabs');
  if (!bar) return;
  let ctl = bar.querySelector('.listen-ctl');
  const active = state.listen && state.listen.active && state.listen.roomId;
  if (!active) { if (ctl) ctl.remove(); return; }
  if (!ctl) {
    ctl = document.createElement('div'); ctl.className = 'listen-ctl';
    ctl.onclick = () => togglePause();
    bar.appendChild(ctl);
  }
  const playing = state.listen.playing;
  ctl.innerHTML = `<span class="lc-dot${playing?' on':''}"></span>${playing?'播放中':'已暂停'}`;
  ctl.title = playing ? '点击暂停' : '点击继续';
}
function togglePause() {
  try { AndroidBridge.toggleListenPlay(); } catch(e){}
}
function setDanmaku(rid,on) { AndroidBridge.setDanmakuOpen(rid,on); }
function setAudioOnlyRoom(rid,on) {
  try { AndroidBridge.setAudioOnly(rid,on); } catch(e){}
  const r = (state.rooms||[]).find(x=>x.roomId===rid);
  if (r) r.audioOnly = on;
  toast(on?'已开启仅录音频（下次录制生效）':'已关闭仅录音频','ok');
  if (state._detailOpen && state.currentRoom === rid) updateRoomDetail(rid);
}
function setQuality(rid,qn) { AndroidBridge.setQuality(rid,Number(qn)); }
function playFile(path) { const r=JSON.parse(AndroidBridge.playFile(path)); if(r.code<0) toast(r.msg,'err'); }
function shareFile(path) { const r=JSON.parse(AndroidBridge.shareFile(path)); if(r.code<0) toast(r.msg,'err'); }
function deleteFile(path) {
  showModal({ title:'删除文件', msg:`确认删除「${path.split('/').pop()}」吗？\n此操作不可恢复。`,
    okText:'删除', onOk:()=>{ const r=JSON.parse(AndroidBridge.deleteRecordFile(path)); toast(r.msg||'已删除',r.code<0?'err':'ok'); if(state.selectedFile===path) state.selectedFile=null; loadFiles(); renderSidebar(); renderEditor(); } });
}
function remuxFile(path) { const r=JSON.parse(AndroidBridge.remuxFile(path)); toast(r.msg,r.code<0?'err':'ok'); }

/* ========== 搜索 UP 添加直播间 ========== */
function openRoomSearch() {
  state._roomSearch = true;
  state._detailOpen = false;
  state.currentRoom = null;
  switchView('explorer');
}
function renderRoomSearch() {
  const body = $('#editorBody');
  subHeader('搜索UP', "state._roomSearch=false;renderEditor()");
  body.innerHTML = `<div class="view">
    <div class="dm-input-row sr-input-row">
      <input id="roomSearchInput" class="dm-input" placeholder="输入UP主昵称 / 直播间名" maxlength="40">
      <button class="btn btn-primary" id="roomSearchBtn" onclick="doRoomSearch()">${ic('search',14)} 搜索</button>
    </div>
    <div id="roomSearchResult" class="search-result"><div class="sb-empty">搜索 UP 名后点击结果添加直播间</div></div>
  </div>`;
  const inp = $('#roomSearchInput');
  inp.value = state._searchKeyword || '';
  inp.focus();
  inp.oninput = e => { state._searchKeyword = e.target.value; };
  inp.onkeydown = e => { if (e.key === 'Enter') doRoomSearch(); };
}
function doRoomSearch() {
  const kw = $('#roomSearchInput').value.trim();
  if (!kw) { toast('请输入UP主昵称','warn'); return; }
  const btn = $('#roomSearchBtn');
  const box = $('#roomSearchResult');
  if (!box) return;
  if (btn) btn.disabled = true;
  box.innerHTML = '<div class="sb-empty">搜索中…</div>';
  let items = [];
  try { items = JSON.parse(AndroidBridge.searchLiveUsers(kw)); } catch(e) {}
  if (btn) btn.disabled = false;
  state._searchResults = items;
  if (!items.length) { box.innerHTML = '<div class="sb-empty">没有找到相关UP主，试试更精确的名字</div>'; return; }
  box.innerHTML = items.map(u => `
    <div class="vc-item" data-rid="${u.roomId}">
      <div class="sr-avatar">${avatarHtml(u.face, u.uname)}</div>
      <div class="vc-body">
        <div class="vc-title">${esc(u.uname)}</div>
        <div class="vc-meta"><span class="${u.liveStatus===1?'txt-accent':''}">${u.liveStatus===1?'● 直播中':(u.liveStatus===2?'● 轮播中':'未开播')}</span>${u.title?`<span>${esc(u.title)}</span>`:''}</div>
      </div>
      <button class="btn btn-primary btn-sm" onclick="addRoomBySearch(this)">添加</button>
    </div>`).join('');
}
function addRoomBySearch(btn) {
  const rid = Number(btn.closest('.vc-item').dataset.rid);
  const u = (state._searchResults||[]).find(x => x.roomId === rid);
  if (!u) return;
  let r = null;
  try { r = JSON.parse(AndroidBridge.addRoomFromSearch(JSON.stringify(u))); } catch(e) { r = { code:-1, msg:'添加失败: '+e }; }
  toast(r.msg, r.code<0?'err':'ok');
  if (r.code >= 0) {
    state._roomSearch = false;
    state._detailOpen = false;
    refreshRooms();  // 新房间立即进列表（否则 state.rooms 还是旧数据）
    renderEditor();
  }
}

function promptAddRoom() {  showModal({ title:'添加房间', msg:'输入直播间房间号 / 短号 / UID', input:true,
    placeholder:'如：123456 或 房间短号', okText:'添加',
    onOk:(val)=>{ if(!val) return; addRoomInput(val.trim()); } });
}
function addRoomInput(input) {
  const r=JSON.parse(AndroidBridge.addRoom(input));
  toast(r.msg, r.code<0?'err':'ok');
  if(r.code>0){refreshRooms(); const rooms=JSON.parse(AndroidBridge.getRooms()); state.currentRoom=rooms.length?rooms[rooms.length-1].roomId:null; renderSidebar(); renderEditor();}
}

/* ========== 初始化 ========== */
function init() {
  $('#btnAddRoom').onclick = promptAddRoom;
  $('#btnSearchRoom').onclick = openRoomSearch;
  $('#btnTheme').onclick = cycleTheme;
  document.querySelectorAll('.ab-btn').forEach(b => { b.onclick = () => switchView(b.dataset.view); });
  try{state.settings=JSON.parse(AndroidBridge.getSettings());}catch(e){}
  state.version = state.settings ? (state.settings.version || '') : '';
  // reload(前端卡死自愈)后恢复上次视图与弹幕房间
  try { const v = localStorage.getItem('ddtv_view'); if (v) state.view = v; } catch(e){}
  try { const r = localStorage.getItem('ddtv_dmroom'); if (r) state.danmakuRoom = Number(r) || null; } catch(e){}
  state.lastEventTs = Date.now();
  try{state.account=JSON.parse(AndroidBridge.getAccount());}catch(e){}
  // 初始主题(system 时跟随系统)
  try { state.theme = AndroidBridge.getThemeSync() || 'system'; } catch(e){ try { state.theme = localStorage.getItem('theme') || 'system'; } catch(e2){} }
  applyTheme(state.theme);
  if (window.matchMedia) window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', e => { if(state.theme==='system') applyTheme('system'); });
  initListen();
  // tabs 栏播放控制器：tabs 内容(切视图/二级页)变化时自动重插，持续显示播放状态
  try {
    const tb = $('#tabs');
    if (tb) new MutationObserver(() => syncListenCtl()).observe(tb, { childList: true });
  } catch(e){}
  syncListenCtl();
  refreshRooms(); updateLayout();
  // 恢复的视图非 explorer 时渲染对应页面(JS 卡死 reload 自愈后不白屏)
  if (state.view !== 'explorer') switchView(state.view);
  AndroidBridge.setPolling(true);
  setInterval(()=>{ if(state.view==='explorer') refreshRooms(); else if(state.view==='files'){ loadFiles(); updateFileListPageSilent(); } else if(state.view==='data'){ loadStats(); updateStatsPanel(); } }, 5000);
  // 事件停滞兜底:有活跃房间(录制中/弹幕开)但 30s 无任何推送事件 → 主动拉全量刷新
  setInterval(()=>{
    const now = Date.now();
    if (!state.lastEventTs || now - state.lastEventTs <= 30000) return;
    const busy = state.rooms.some(r => r.recState==='recording' || (r.danmakuOpen && (r.liveStatus===1||r.liveStatus===2)));
    if (!busy) return;
    state.lastEventTs = now;  // 防连续触发
    if (state.view === 'danmaku') loadDanmakuHistory();
    else refreshRooms();
  }, 10000);
}
init();

/* 系统返回键（由 MainActivity 调用）：按优先级处理应用内导航，
 * 弹层 → 长按菜单 → 管理模式 → 二级页返回；全部处理完（主界面）返回 false，
 * Kotlin 侧据此 finish() 退出应用 */
window.__back = function() {
  // 1. 模态框：等同点取消/遮罩关闭
  const modal = $('#modal');
  if (modal && !modal.classList.contains('hidden')) { closeModal(); return true; }
  // 2. 长按管理菜单
  const m = $('#ctxMenu');
  if (m && !m.classList.contains('hidden')) { hideRoomMenu(); return true; }
  // 3. 自定义下拉展开
  const cs = document.querySelector('.cselect.open');
  if (cs) { cs.classList.remove('open'); return true; }
  // 4. 文件批量管理模式 → 退出管理
  if (state._fileManage) { toggleFileManage(); return true; }
  // 5. 录制历史批量管理 → 退出管理
  if (_histManage) { exitHistManage(); return true; }
  // 6. 二级页（房间详情/文件详情/登录子页等）→ 触发返回头按钮
  const back = document.querySelector('#tabs .sn-back');
  if (back) { back.click(); return true; }
  return false;
};
