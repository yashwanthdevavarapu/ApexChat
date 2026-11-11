/*
 * ApexChat browser client.
 *
 * One STOMP-over-WebSocket connection carries everything:
 *   /topic/rooms               new channels
 *   /topic/rooms/{slug}        channel messages
 *   /topic/rooms/{slug}/typing channel typing hints
 *   /topic/presence            who is online (full roster each time)
 *   /app/presence              one-off roster snapshot on subscribe
 *   /user/queue/dm             direct messages (to and from me)
 *   /user/queue/typing         direct-message typing hints
 *   /user/queue/errors         errors for commands I sent
 * History is loaded over REST and merged with live frames by message id.
 */

const app = document.getElementById("app");
const ME = app.dataset.user;
const CSRF_HEADER = app.dataset.csrfHeader;
const CSRF_TOKEN = app.dataset.csrfToken;

const GROUP_WINDOW_MS = 5 * 60 * 1000;
const TYPING_IDLE_MS = 2500;
const TYPING_EXPIRY_MS = 6000;
const PAGE_SIZE = 50;

const $ = (id) => document.getElementById(id);
const els = {
  conn: $("connection-status"),
  roomList: $("room-list"),
  peopleList: $("people-list"),
  peopleEmpty: $("people-empty"),
  onlineCount: $("online-count"),
  title: $("conv-title"),
  subtitle: $("conv-subtitle"),
  messages: $("messages"),
  messageList: $("message-list"),
  emptyState: $("empty-state"),
  loadOlder: $("load-older"),
  typing: $("typing-indicator"),
  composer: $("composer"),
  input: $("message-input"),
  send: $("send-btn"),
  toast: $("toast"),
  dialog: $("new-room-dialog"),
  roomForm: $("new-room-form"),
  roomName: $("room-name"),
  roomDescription: $("room-description"),
  roomError: $("room-error"),
};

const state = {
  rooms: new Map(), // slug -> RoomDto
  online: [], // usernames, including me
  presenceVersion: -1,
  dmPartners: new Set(), // people I have exchanged DMs with this session
  active: null, // { kind: "room" | "dm", id }
  messages: [], // MessageDto[] for the active conversation, oldest first
  seenIds: new Set(),
  hasOlder: false,
  unread: new Map(), // "room:general" | "dm:alice" -> count
  typing: new Map(), // username -> expiry timer, for the active conversation
  connected: false,
  everConnected: false,
  historyToken: 0,
  pendingRoom: null,
  leaving: false, // set while navigating away (e.g. sign-out) so we do not race the redirect
};

/* ---------------------------------------------------------------- utils */

const convKey = (kind, id) => `${kind}:${id}`;
const activeKey = () => (state.active ? convKey(state.active.kind, state.active.id) : null);

function hue(name) {
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.codePointAt(0)) % 360;
  return h;
}

function avatar(name, { small = false, online = null } = {}) {
  const el = document.createElement("span");
  el.className = small ? "avatar sm" : "avatar";
  el.style.setProperty("--hue", hue(name));
  el.textContent = name.charAt(0);
  el.setAttribute("aria-hidden", "true");
  if (online !== null) {
    const dot = document.createElement("span");
    dot.className = online ? "presence-dot online" : "presence-dot";
    el.appendChild(dot);
  }
  return el;
}

/** Renders text with clickable links without ever touching innerHTML. */
function linkify(text) {
  const frag = document.createDocumentFragment();
  const re = /(https?:\/\/[^\s<>"']+)/g;
  let last = 0;
  for (const match of text.matchAll(re)) {
    frag.append(text.slice(last, match.index));
    const a = document.createElement("a");
    a.href = match[0];
    a.textContent = match[0];
    a.target = "_blank";
    a.rel = "noopener noreferrer";
    frag.append(a);
    last = match.index + match[0].length;
  }
  frag.append(text.slice(last));
  return frag;
}

const timeFmt = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" });
const dayFmt = new Intl.DateTimeFormat(undefined, { weekday: "long", month: "long", day: "numeric" });

function dayLabel(date) {
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (date.toDateString() === today.toDateString()) return "Today";
  if (date.toDateString() === yesterday.toDateString()) return "Yesterday";
  return dayFmt.format(date);
}

let toastTimer;
function toast(message, kind = "error") {
  els.toast.textContent = message;
  els.toast.className = `toast ${kind === "info" ? "info" : ""}`;
  els.toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (els.toast.hidden = true), 4000);
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    credentials: "same-origin",
    ...options,
    headers: {
      Accept: "application/json",
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      [CSRF_HEADER]: CSRF_TOKEN,
      ...(options.headers || {}),
    },
  });
  if (res.status === 401) {
    if (!state.leaving) window.location.href = "/login";
    throw new Error("Session expired");
  }
  const body = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) {
    throw new Error((body && body.detail) || `Request failed (${res.status})`);
  }
  return body;
}

/* ---------------------------------------------------------- connection */

const client = new StompJs.Client({
  brokerURL: `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`,
  connectHeaders: { [CSRF_HEADER]: CSRF_TOKEN },
  reconnectDelay: 2000,
  heartbeatIncoming: 10000,
  heartbeatOutgoing: 10000,
  onConnect,
  onWebSocketClose,
  onStompError: (frame) => toast(frame.headers.message || "Connection error"),
});

const roomSubscriptions = new Map(); // slug -> StompSubscription[]

function setConnection(next) {
  els.conn.dataset.state = next;
  els.conn.querySelector(".conn-label").textContent =
    { online: "Live", connecting: "Connecting", offline: "Reconnecting" }[next];
  state.connected = next === "online";
  if (!state.connected) typingActive = false; // the "stopped typing" frame could not be sent
  updateComposer();
}

function json(frame) {
  return JSON.parse(frame.body);
}

function onConnect() {
  roomSubscriptions.clear();
  client.subscribe("/topic/rooms", (f) => onRoomCreated(json(f)));
  client.subscribe("/topic/presence", (f) => onPresence(json(f)));
  client.subscribe("/app/presence", (f) => onPresence(json(f)));
  client.subscribe("/user/queue/dm", (f) => onDirectMessage(json(f)));
  client.subscribe("/user/queue/typing", (f) => onDirectTyping(json(f)));
  client.subscribe("/user/queue/errors", (f) => onCommandError(json(f)));
  for (const slug of state.rooms.keys()) subscribeRoom(slug);

  setConnection("online");
  // Anything sent while we were disconnected is in the history API
  if (state.everConnected && state.active) loadHistory();
  state.everConnected = true;
}

async function onWebSocketClose() {
  if (state.leaving) return;
  setConnection(state.everConnected ? "offline" : "connecting");
  // A closed socket can mean the HTTP session expired; api() redirects to /login on 401
  try {
    await api("/api/presence");
  } catch {
    /* handled by api() or retried by the reconnect loop */
  }
}

function subscribeRoom(slug) {
  if (!client.connected) return;
  if (roomSubscriptions.has(slug)) return;
  roomSubscriptions.set(slug, [
    client.subscribe(`/topic/rooms/${slug}`, (f) => onRoomMessage(json(f))),
    client.subscribe(`/topic/rooms/${slug}/typing`, (f) => onRoomTyping(json(f))),
  ]);
}

/* --------------------------------------------------------- live events */

/** Rejected sends give the text back instead of losing it. */
function onCommandError(err) {
  toast(err.message);
  if (["RATE_LIMITED", "MESSAGE_TOO_LONG"].includes(err.code) && lastSentDraft && !els.input.value) {
    els.input.value = lastSentDraft;
    autosize();
    updateComposer();
  }
}

function onRoomMessage(msg) {
  if (state.active?.kind === "room" && state.active.id === msg.room) {
    appendMessage(msg);
  } else if (msg.sender !== ME) {
    bumpUnread(convKey("room", msg.room));
  }
}

function onDirectMessage(msg) {
  const partner = msg.sender === ME ? msg.recipient : msg.sender;
  const isNewPartner = !state.dmPartners.has(partner);
  state.dmPartners.add(partner);
  if (state.active?.kind === "dm" && state.active.id === partner) {
    appendMessage(msg);
  } else if (msg.sender !== ME) {
    bumpUnread(convKey("dm", partner));
  } else if (isNewPartner) {
    renderPeople();
  }
}

function onRoomTyping(evt) {
  if (evt.username === ME) return;
  if (state.active?.kind === "room" && state.active.id === evt.room) setTyping(evt.username, evt.typing);
}

function onDirectTyping(evt) {
  if (state.active?.kind === "dm" && state.active.id === evt.username) setTyping(evt.username, evt.typing);
}

function onPresence(evt) {
  // Frames can overtake each other; the server-assigned version says which roster is newest
  if (evt.version < state.presenceVersion) return;
  state.presenceVersion = evt.version;
  state.online = evt.online;
  renderPeople();
  if (state.active?.kind === "dm") renderHeader();
}

function onRoomCreated(room) {
  state.rooms.set(room.slug, room);
  subscribeRoom(room.slug);
  renderRooms();
  if (state.pendingRoom === room.slug) {
    state.pendingRoom = null;
    navigate("room", room.slug);
  }
}

/* -------------------------------------------------------------- unread */

function bumpUnread(key) {
  state.unread.set(key, (state.unread.get(key) || 0) + 1);
  renderRooms();
  renderPeople();
  renderDocumentTitle();
}

function clearUnread(key) {
  if (state.unread.delete(key)) {
    renderRooms();
    renderPeople();
    renderDocumentTitle();
  }
}

function renderDocumentTitle() {
  const total = [...state.unread.values()].reduce((a, b) => a + b, 0);
  document.title = total > 0 ? `(${total}) ApexChat` : "Chat · ApexChat";
}

function badge(count) {
  const b = document.createElement("span");
  b.className = "badge";
  b.textContent = count > 99 ? "99+" : String(count);
  return b;
}

/* ------------------------------------------------------------- sidebar */

function renderRooms() {
  els.roomList.replaceChildren(
    ...[...state.rooms.values()].map((room) => {
      const key = convKey("room", room.slug);
      const unread = state.unread.get(key) || 0;
      const li = document.createElement("li");
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "nav-item";
      btn.classList.toggle("active", activeKey() === key);
      btn.classList.toggle("unread", unread > 0);
      btn.setAttribute("data-testid", `room-${room.slug}`);
      const hash = document.createElement("span");
      hash.className = "hash";
      hash.textContent = "#";
      const label = document.createElement("span");
      label.className = "label";
      label.textContent = room.slug;
      btn.append(hash, label);
      if (unread > 0) btn.append(badge(unread));
      btn.addEventListener("click", () => navigate("room", room.slug));
      li.append(btn);
      return li;
    }),
  );
}

function renderPeople() {
  const online = new Set(state.online);
  const others = new Set([...state.online, ...state.dmPartners]);
  others.delete(ME);
  const people = [...others].sort(
    (a, b) => Number(online.has(b)) - Number(online.has(a)) || a.localeCompare(b),
  );

  els.peopleList.replaceChildren(
    ...people.map((name) => {
      const key = convKey("dm", name);
      const unread = state.unread.get(key) || 0;
      const li = document.createElement("li");
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "nav-item";
      btn.classList.toggle("active", activeKey() === key);
      btn.classList.toggle("unread", unread > 0);
      btn.setAttribute("data-testid", `person-${name}`);
      btn.dataset.online = String(online.has(name));
      const label = document.createElement("span");
      label.className = "label";
      label.textContent = name;
      btn.append(avatar(name, { small: true, online: online.has(name) }), label);
      if (unread > 0) btn.append(badge(unread));
      btn.addEventListener("click", () => navigate("dm", name));
      li.append(btn);
      return li;
    }),
  );
  els.peopleEmpty.hidden = people.length > 0;
  els.onlineCount.textContent = `${state.online.length} online`;
}

/* -------------------------------------------------------- conversation */

function navigate(kind, id) {
  const hash = `#${kind}/${encodeURIComponent(id)}`;
  if (location.hash === hash) openConversation(kind, id);
  else location.hash = hash; // triggers hashchange -> openConversation
}

function routeFromHash() {
  const [, kind, id] = location.hash.match(/^#(room|dm)\/(.+)$/) || [];
  if (kind === "room" && state.rooms.has(decodeURIComponent(id))) return { kind, id: decodeURIComponent(id) };
  if (kind === "dm" && decodeURIComponent(id) !== ME) return { kind, id: decodeURIComponent(id) };
  return { kind: "room", id: state.rooms.has("general") ? "general" : state.rooms.keys().next().value };
}

function openConversation(kind, id) {
  if (state.active && (state.active.kind !== kind || state.active.id !== id)) sendTyping(false);
  state.active = { kind, id };
  if (kind === "dm") state.dmPartners.add(id);
  clearTyping();
  clearUnread(convKey(kind, id));
  renderRooms();
  renderPeople();
  renderHeader();
  els.input.placeholder = kind === "room" ? `Message #${id}` : `Message ${id}`;
  app.classList.remove("sidebar-open");
  updateComposer();
  loadHistory();
  els.input.focus();
}

function renderHeader() {
  if (!state.active) return;
  const { kind, id } = state.active;
  if (kind === "room") {
    els.title.textContent = `# ${id}`;
    els.subtitle.textContent = state.rooms.get(id)?.description || "";
  } else {
    els.title.textContent = id;
    els.subtitle.textContent = state.online.includes(id) ? "Online now" : "Offline";
  }
}

function historyUrl(before) {
  const { kind, id } = state.active;
  const base = kind === "room" ? `/api/rooms/${encodeURIComponent(id)}/messages` : `/api/dm/${encodeURIComponent(id)}/messages`;
  const params = new URLSearchParams({ limit: PAGE_SIZE });
  if (before) params.set("before", before);
  return `${base}?${params}`;
}

async function loadHistory() {
  const token = ++state.historyToken;
  try {
    const page = await api(historyUrl());
    if (token !== state.historyToken) return; // user switched conversations meanwhile
    state.messages = page;
    state.seenIds = new Set(page.map((m) => m.id));
    state.hasOlder = page.length === PAGE_SIZE;
    renderAllMessages();
    scrollToBottom(true);
  } catch (e) {
    if (token === state.historyToken) toast(e.message);
  }
}

async function loadOlder() {
  if (!state.messages.length) return;
  const token = state.historyToken;
  const prevHeight = els.messages.scrollHeight;
  const page = await api(historyUrl(state.messages[0].id));
  if (token !== state.historyToken) return;
  const fresh = page.filter((m) => !state.seenIds.has(m.id));
  fresh.forEach((m) => state.seenIds.add(m.id));
  state.messages = [...fresh, ...state.messages];
  state.hasOlder = page.length === PAGE_SIZE;
  renderAllMessages();
  els.messages.scrollTop = els.messages.scrollHeight - prevHeight;
}

/* ------------------------------------------------------------ messages */

let lastGroup = null; // { sender, at: Date, body: HTMLElement }

function renderAllMessages() {
  lastGroup = null;
  els.messageList.replaceChildren();
  for (const m of state.messages) renderMessage(m, false);
  els.loadOlder.hidden = !state.hasOlder;
  els.emptyState.hidden = state.messages.length > 0;
}

function appendMessage(msg) {
  if (state.seenIds.has(msg.id)) return;
  state.seenIds.add(msg.id);
  const stick = isNearBottom() || msg.sender === ME;
  els.emptyState.hidden = true;
  const last = state.messages[state.messages.length - 1];
  state.messages.push(msg);
  if (last && last.id > msg.id) {
    // Frames can overtake each other on the server's outbound thread pool; ids are
    // authoritative (assigned in receive order), so re-sort and redraw. See ADR-0007.
    state.messages.sort((a, b) => a.id - b.id);
    renderAllMessages();
  } else {
    renderMessage(msg, true);
  }
  if (msg.sender !== ME) setTyping(msg.sender, false);
  if (stick) scrollToBottom();
}

function renderMessage(msg, fresh) {
  const at = new Date(msg.sentAt);
  const sameDay = lastGroup && lastGroup.at.toDateString() === at.toDateString();
  if (!sameDay) {
    const divider = document.createElement("div");
    divider.className = "day-divider";
    divider.textContent = dayLabel(at);
    els.messageList.append(divider);
  }

  const text = document.createElement("div");
  text.className = fresh ? "msg-text fresh" : "msg-text";
  text.dataset.messageId = msg.id;
  text.setAttribute("data-testid", "message-text");
  text.append(linkify(msg.content));

  const continues = sameDay && lastGroup.sender === msg.sender && at - lastGroup.at < GROUP_WINDOW_MS;
  if (continues) {
    lastGroup.body.append(text);
    lastGroup.at = at;
    return;
  }

  const group = document.createElement("article");
  group.className = msg.sender === ME ? "msg-group mine" : "msg-group";
  group.setAttribute("data-testid", "message");
  group.dataset.sender = msg.sender;

  const body = document.createElement("div");
  body.className = "msg-body";
  const meta = document.createElement("div");
  meta.className = "msg-meta";
  const author = document.createElement("span");
  author.className = "msg-author";
  author.textContent = msg.sender;
  const time = document.createElement("time");
  time.className = "msg-time";
  time.dateTime = msg.sentAt;
  time.title = at.toLocaleString();
  time.textContent = timeFmt.format(at);
  meta.append(author, time);
  body.append(meta, text);
  group.append(avatar(msg.sender), body);
  els.messageList.append(group);
  lastGroup = { sender: msg.sender, at, body };
}

function isNearBottom() {
  const m = els.messages;
  return m.scrollHeight - m.scrollTop - m.clientHeight < 140;
}

function scrollToBottom(instant = false) {
  els.messages.scrollTo({ top: els.messages.scrollHeight, behavior: instant ? "auto" : "smooth" });
}

/* -------------------------------------------------------------- typing */

function setTyping(username, typing) {
  clearTimeout(state.typing.get(username));
  if (typing) {
    state.typing.set(username, setTimeout(() => setTyping(username, false), TYPING_EXPIRY_MS));
  } else {
    state.typing.delete(username);
  }
  renderTyping();
}

function clearTyping() {
  state.typing.forEach((t) => clearTimeout(t));
  state.typing.clear();
  renderTyping();
}

function renderTyping() {
  const names = [...state.typing.keys()];
  els.typing.textContent =
    names.length === 0 ? ""
      : names.length === 1 ? `${names[0]} is typing…`
      : names.length === 2 ? `${names[0]} and ${names[1]} are typing…`
      : "Several people are typing…";
}

let typingActive = false;
let lastSentDraft = "";
let typingTimer;

function destinationBase() {
  const { kind, id } = state.active;
  return kind === "room" ? `/app/rooms/${id}` : `/app/dm/${id}`;
}

function sendTyping(typing) {
  clearTimeout(typingTimer);
  if (!state.connected || !state.active || typingActive === typing) return;
  typingActive = typing;
  client.publish({ destination: `${destinationBase()}/typing`, body: JSON.stringify({ typing }) });
}

/* ------------------------------------------------------------ composer */

function updateComposer() {
  const enabled = state.connected && !!state.active;
  els.input.disabled = !enabled;
  els.send.disabled = !enabled || els.input.value.trim() === "";
}

function autosize() {
  els.input.style.height = "auto";
  els.input.style.height = `${Math.min(els.input.scrollHeight, 180)}px`;
}

function sendMessage() {
  const content = els.input.value.trim();
  if (!content || !state.connected || !state.active) return;
  client.publish({ destination: `${destinationBase()}/send`, body: JSON.stringify({ content }) });
  lastSentDraft = content;
  sendTyping(false);
  els.input.value = "";
  autosize();
  updateComposer();
}

els.composer.addEventListener("submit", (e) => {
  e.preventDefault();
  sendMessage();
});

els.input.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    sendMessage();
  }
});

els.input.addEventListener("input", () => {
  autosize();
  updateComposer();
  if (els.input.value.trim() === "") {
    sendTyping(false);
    return;
  }
  sendTyping(true);
  typingTimer = setTimeout(() => sendTyping(false), TYPING_IDLE_MS);
});

els.loadOlder.addEventListener("click", () => loadOlder().catch((e) => toast(e.message)));

/* ------------------------------------------------------ create channel */

$("new-room-btn").addEventListener("click", () => {
  els.roomForm.reset();
  els.roomError.textContent = "";
  els.dialog.showModal();
  els.roomName.focus();
});

$("room-cancel").addEventListener("click", () => els.dialog.close());

els.roomForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  els.roomError.textContent = "";
  try {
    const room = await api("/api/rooms", {
      method: "POST",
      body: JSON.stringify({ name: els.roomName.value.trim(), description: els.roomDescription.value.trim() }),
    });
    els.dialog.close();
    state.pendingRoom = room.slug;
    onRoomCreated(room); // the broadcast may arrive before or after this response
  } catch (err) {
    els.roomError.textContent = err.message;
  }
});

/* ---------------------------------------------------------------- boot */

window.addEventListener("pagehide", () => (state.leaving = true));
document.querySelector('form[action$="/logout"]').addEventListener("submit", () => (state.leaving = true));
$("menu-btn").addEventListener("click", () => app.classList.toggle("sidebar-open"));
$("me-avatar").replaceWith(avatar(ME));
window.addEventListener("hashchange", () => {
  const { kind, id } = routeFromHash();
  openConversation(kind, id);
});

async function boot() {
  setConnection("connecting");
  const rooms = await api("/api/rooms");
  rooms.forEach((r) => state.rooms.set(r.slug, r));
  renderRooms();
  renderPeople();
  const { kind, id } = routeFromHash();
  openConversation(kind, id);
  client.activate();
}

boot().catch((e) => toast(e.message));
