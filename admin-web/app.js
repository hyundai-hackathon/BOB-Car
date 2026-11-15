// BOB-Car admin web (read only). Two screens from the design (Figma Admin-Web): #flags (A1) and #logbook (A2),
// plus the states (A3 server error, loading, nothing to show). Data comes from the server's admin views
// (server/functions/src/admin.ts) through Firebase Hosting ("/api/v1/...", same origin).
// Every call carries a Firebase anonymous sign-in ID token in the Authorization header (never in the URL).

const VEHICLE_ID = "veh-001";
const API = "/api/v1";
const REFRESH_MS = 15_000;

// Local emulators only for now (the Auth emulator accepts any API key). A deployed admin web needs a real
// Firebase project and its Web API key: see server/README notes (not part of this build).
const LOCAL = ["127.0.0.1", "localhost"].includes(location.hostname);
const AUTH_BASE = LOCAL ? `http://${location.hostname}:9099` : null;
const AUTH_KEY = "emulator";

// ---------------------------------------------------------------- sign-in and API

let idToken = null;

async function signIn() {
  if (!AUTH_BASE) throw new ApiError("NO_AUTH", "로그인 설정 없음", "");
  const res = await fetch(`${AUTH_BASE}/identitytoolkit.googleapis.com/v1/accounts:signUp`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Goog-Api-Key": AUTH_KEY },
    body: JSON.stringify({ returnSecureToken: true }),
  });
  if (!res.ok) throw new ApiError(`HTTP ${res.status}`, "로그인 실패", "POST /identitytoolkit.googleapis.com/v1/accounts:signUp");
  idToken = (await res.json()).idToken;
}

class ApiError extends Error {
  constructor(code, message, request) {
    super(message);
    this.code = code;
    this.request = request;
  }
}

/** GET [path] with the ID token. Signs in again once on 401. */
async function api(path, asBlob = false) {
  const request = `GET ${API}${path.split("?")[0]}`;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      if (!idToken) await signIn();
      const res = await fetch(API + path, { headers: { Authorization: `Bearer ${idToken}` } });
      if (res.status === 401 && attempt === 0) {
        idToken = null;
        continue;
      }
      if (!res.ok) throw new ApiError(`HTTP ${res.status}`, "서버 오류", request);
      return asBlob ? res : await res.json();
    } catch (e) {
      if (e instanceof ApiError) throw e;
      throw new ApiError("NETWORK", "연결 거부됨", request);
    }
  }
  throw new ApiError("HTTP 401", "인증 실패", request);
}

// ---------------------------------------------------------------- formatting (Korea time)

const TIME = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
const PARTS = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit", weekday: "short" });

const parts = (ms) => Object.fromEntries(PARTS.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));

/** "11월 12일 (목)" */
function day(ms) {
  const p = parts(ms);
  return `${Number(p.month)}월 ${Number(p.day)}일 (${p.weekday})`;
}

function time(ms) {
  return TIME.format(new Date(ms));
}

/** "11월 12일 (목) 18:02" */
function dateTime(ms) {
  return `${day(ms)} ${time(ms)}`;
}

/** "11월 12일 (목) 18:02 – 18:21" (the end's day only when it differs) */
function range(from, to) {
  if (to == null || to === from) return dateTime(from);
  return day(from) === day(to) ? `${dateTime(from)} – ${time(to)}` : `${dateTime(from)} – ${dateTime(to)}`;
}

/** "11.13 (금) 09:12" for the logbook table */
function shortDateTime(ms) {
  const p = parts(ms);
  return `${p.month}.${p.day} (${p.weekday}) ${time(ms)}`;
}

const km = (v) => (v == null ? "—" : `${v.toLocaleString("ko-KR", { minimumFractionDigits: 1, maximumFractionDigits: 1 })}`);
const won = (v) => `${Number(v).toLocaleString("ko-KR")}원`;

// ---------------------------------------------------------------- tiny DOM helper (text is never parsed as HTML)

function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === "class") el.className = v;
    else if (k === "on") for (const [ev, fn] of Object.entries(v)) el.addEventListener(ev, fn);
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat()) if (c != null && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}

const icon = (name, cls = "") => h("span", { class: `icon icon-${name} ${cls}`.trim(), "aria-hidden": "true" });

// ---------------------------------------------------------------- shell: header, tabs, server status

const view = document.getElementById("view");
let lastSuccessAt = null;
let timer = null;
let loadedOnce = { flags: false, logbook: false };

function currentTab() {
  return location.hash === "#logbook" ? "logbook" : "flags";
}

function setTabs(tab) {
  for (const a of document.querySelectorAll(".tab")) {
    if (a.dataset.tab === tab) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  }
}

function setServer(online) {
  const status = document.getElementById("server-status");
  status.classList.toggle("offline", !online);
  document.getElementById("server-status-text").textContent = online ? "서버 연결됨" : "서버 연결 안 됨";
  document.getElementById("vehicle").hidden = !online;
}

function setVehicle(vehicle) {
  document.getElementById("vehicle").textContent = [vehicle.model ?? vehicle.name, vehicle.plate].filter(Boolean).join(" · ");
}

function setFlagCount(n) {
  const badge = document.getElementById("flag-count");
  badge.hidden = !n;
  badge.textContent = n ? String(n) : "";
}

function render(node) {
  view.replaceChildren(node);
}

// ---------------------------------------------------------------- states (A3 and same-layout states)

function stateLoading() {
  return h("section", { class: "state loading" }, icon("circle-notch"), h("h2", {}, "불러오는 중"), h("p", {}, "서버에서 최신 기록을 가져오고 있습니다."));
}

function stateEmpty(title, text) {
  return h("section", { class: "state" }, icon("check-circle"), h("h2", {}, title), h("p", {}, text));
}

function stateError(tab, error) {
  const what = tab === "logbook" ? "운행기록부를" : "플래그 목록을";
  return h(
    "section",
    { class: "state error" },
    icon("plugs"),
    h("h2", {}, "서버에 연결할 수 없습니다"),
    h("p", {}, `${what} 불러오지 못했습니다. 서버가 실행 중인지 확인한 뒤 다시 시도해 주세요. 차량에 저장된 운행 기록은 지워지지 않습니다.`),
    h(
      "div",
      { class: "actions" },
      h("button", { class: "btn btn-primary", type: "button", on: { click: () => load(true) } }, icon("arrow-clockwise"), "다시 시도"),
      lastSuccessAt ? h("span", { class: "meta" }, `마지막 성공 ${dateTime(lastSuccessAt)}`) : null,
    ),
    h("span", { class: "request" }, `${error.request || "GET " + API} · ${error.message}`),
  );
}

// ---------------------------------------------------------------- A1 확인이 필요한 항목

function attentionPage(data) {
  const cards = data.items.map(itemCard);
  if (data.consentPending.length > 0) cards.push(consentCard(data.consentPending));
  setFlagCount(cards.length);
  if (cards.length === 0) {
    return stateEmpty("확인할 항목이 없습니다", "차량 기록과 결제·예약·동의 정보가 모두 맞습니다. 15초마다 다시 확인합니다.");
  }
  return h(
    "div",
    {},
    h(
      "div",
      { class: "page-head" },
      h(
        "div",
        { class: "titles" },
        h("h2", {}, `확인이 필요한 항목 ${cards.length}건`),
        h("span", { class: "sub" }, `차량 기록과 결제·예약·동의 정보를 대조한 결과입니다. ${day(data.asOf)} ${time(data.asOf)} 기준`),
      ),
    ),
    h("div", { class: "attention" }, cards),
  );
}

function card({ iconName, tag, tagClass = "tag-accent", title, detail, when, meta, neutral = false }) {
  return h(
    "article",
    { class: `item${neutral ? " neutral" : ""}` },
    icon(iconName),
    h("div", { class: "kind" }, h("span", { class: `tag ${tagClass}` }, tag)),
    h("div", { class: "text" }, h("span", { class: "title" }, title), h("span", { class: "detail" }, detail)),
    h("div", { class: "side" }, h("span", { class: "when" }, when), meta),
  );
}

const logbookLink = () => h("a", { href: "#logbook", class: "meta" }, "운행기록부에서 보기");

function itemCard(item) {
  if (item.type === "payment") {
    const charge = item.category === "CHARGE";
    const noRecord = item.reason === "NO_CHARGE";
    return card({
      iconName: "credit-card",
      tag: "결제 대조",
      title: `${charge ? "충전" : "주유"} 결제 ${won(item.paidAmount)} — ${noRecord ? `차량 ${charge ? "충전" : "주유"} 기록 없음` : "결제 금액이 충전량보다 큼"}`,
      detail: noRecord
        ? `법인카드 결제 시각에 이 차량의 ${charge ? "충전" : "주유"}이 감지되지 않았습니다`
        : `충전량으로 예상한 금액 ${won(item.expectedAmount)}보다 많이 결제했습니다`,
      when: dateTime(item.at),
      meta: h("span", { class: "meta" }, `차량 사용자 ${item.driverName ?? "미상"}`),
    });
  }
  if (item.type === "unrecorded") {
    return card({
      iconName: "warning-circle",
      tag: "미기록 운행",
      title: `운전자 미상 ${km(item.km)} km`,
      detail:
        item.startKm != null && item.endKm != null
          ? `운전자 확인 없이 주행했습니다. 누적 거리 ${km(item.startKm)} → ${km(item.endKm)} km`
          : "운전자 확인 없이 주행했습니다",
      when: range(item.since ?? item.foundAt, item.foundAt),
      meta: logbookLink(),
    });
  }
  // use outside the reservation
  const trips = item.trips ?? [];
  const distance = trips.reduce((s, t) => s + (t.distanceKm ?? 0), 0);
  const where = item.reason === "NO_RESERVATION" ? "예약 없는 시간에" : `${item.reservedDriverName} 님 예약 시간에`;
  const purposeNote = purposeText(trips);
  return card({
    iconName: "calendar-x",
    tag: "예약 밖 사용",
    title: trips.length > 0 ? `${item.driverName} 님 · ${where} ${km(Math.round(distance * 10) / 10)} km` : `${item.driverName} 님 · ${where} 운전자로 선택`,
    detail: [
      item.reason === "NO_RESERVATION" ? "이 시간대에 예약이 없었습니다." : `예약자 ${item.reservedDriverName} 님과 다른 운전자입니다.`,
      trips.length === 0 ? "아직 운행 전입니다" : trips.length > 1 ? `운행 ${trips.length}건` : null,
      purposeNote,
    ]
      .filter(Boolean)
      .join(" "),
    when: trips.length > 0 ? range(trips[0].startedAt, trips[trips.length - 1].endedAt) : dateTime(item.at),
    meta: trips.length > 0 ? logbookLink() : null,
  });
}

const PURPOSE = { BUSINESS: "업무", COMMUTE: "출퇴근", PERSONAL: "개인" };

function purposeText(trips) {
  const last = trips[trips.length - 1];
  if (!last?.purpose) return null;
  return `${PURPOSE[last.purpose]} 용도로 ${last.purposeProvisional ? "잠정 " : ""}기록`;
}

function consentCard(pending) {
  const first = pending[0].name;
  return card({
    iconName: "user-circle-dashed",
    tag: "동의 대기",
    tagClass: "tag-outline",
    neutral: true,
    title: pending.length === 1 ? `${first} 님 · 개인정보 수집 동의 대기` : `${first} 님 외 ${pending.length - 1}명 · 개인정보 수집 동의 대기`,
    detail: "동의 전까지 이 운전자의 기록은 차량에만 보관됩니다",
    when: `동의 대기 ${pending.length}명`,
    meta: h("span", { class: "meta" }, pending.map((d) => d.name).join(" · ")),
  });
}

// ---------------------------------------------------------------- A2 운행기록부

const PURPOSE_TAG = {
  BUSINESS: ["일반 업무", "tag-accent"],
  COMMUTE: ["출퇴근", "tag-neutral"],
  PERSONAL: ["개인", "tag-neutral"],
};

function logbookPage(data) {
  const [year, month] = data.month.split("-").map(Number);
  const s = data.summary;
  const head = h(
    "div",
    { class: "page-head" },
    h("div", { class: "titles" }, h("h2", {}, "운행기록부"), h("span", { class: "sub" }, `${year}년 ${month}월 · 운행 ${s.trips}건`)),
    h(
      "button",
      { class: "btn btn-primary", type: "button", id: "excel", on: { click: () => downloadExcel(data.month) } },
      icon("download-simple"),
      "엑셀 내려받기 (국세청 양식)",
    ),
  );
  const summary = h(
    "div",
    { class: "summary" },
    stat("총 주행거리", `${km(s.totalKm)} km`),
    stat("업무용 사용거리", `${km(s.businessKm)} km`),
    stat("업무사용비율", `${km(s.ratioPercent)} %`),
    stat("예상 통행료", won(s.tollFare)),
  );
  if (data.rows.length === 0) {
    return h("div", {}, head, summary, stateEmpty("이번 달 운행 기록이 없습니다", "차량에서 운행이 끝나면 여기에 기록됩니다."));
  }
  const table = h(
    "table",
    { class: "logbook" },
    h(
      "thead",
      {},
      h(
        "tr",
        {},
        h("th", {}, "사용일자"),
        h("th", {}, "사용자"),
        h("th", { class: "num" }, "주행 전 (km)"),
        h("th", { class: "num" }, "주행 후 (km)"),
        h("th", { class: "num" }, "주행거리"),
        h("th", {}, "업무 구분"),
        h("th", {}, "목적지"),
        h("th", { class: "num" }, "예상 통행료"),
      ),
    ),
    h("tbody", {}, data.rows.map(logbookRow)),
  );
  return h(
    "div",
    {},
    head,
    summary,
    h("div", { class: "table-wrap" }, table, h("div", { class: "footnote" }, `${data.rows.length}건 표시 · 개인 운행의 목적지는 표시하지 않습니다`)),
  );
}

function stat(label, value) {
  return h("div", {}, h("span", { class: "label" }, label), h("span", { class: "value" }, value));
}

function logbookRow(r) {
  const personal = r.purpose === "PERSONAL";
  const user = r.unrecorded
    ? h("span", { class: "unknown-driver" }, icon("warning-circle"), "운전자 미상")
    : h("span", {}, [r.department, r.driverName].filter(Boolean).join(" "), r.driverProvisional ? h("span", { class: "provisional" }, " · 잠정") : null);
  const [label, cls] = r.unrecorded ? ["미기록 운행", "tag-outline"] : PURPOSE_TAG[r.purpose] ?? ["—", "tag-neutral"];
  const purpose = h("span", { class: `tag ${cls}`, title: !r.unrecorded && r.purposeProvisional ? "운전자가 확인하지 않은 자동 값" : null }, label);
  const dest = r.unrecorded ? h("span", { class: "muted" }, "—") : personal ? h("span", { class: "muted" }, "표시 안 함") : r.destination ?? h("span", { class: "muted" }, "—");
  const toll = r.unrecorded || personal || r.expectedTollFare == null ? h("span", { class: "muted" }, "—") : won(r.expectedTollFare);
  return h(
    "tr",
    { class: r.unrecorded ? "unrecorded" : null },
    h("td", {}, shortDateTime(r.startedAt)),
    h("td", {}, user),
    h("td", { class: "num" }, km(r.startKm)),
    h("td", { class: "num" }, km(r.endKm)),
    h("td", { class: "num" }, r.distanceKm == null ? "—" : `${km(r.distanceKm)} km`),
    h("td", {}, purpose),
    h("td", {}, dest),
    h("td", { class: "num" }, toll),
  );
}

async function downloadExcel(month) {
  const button = document.getElementById("excel");
  button.disabled = true;
  try {
    const res = await api(`/admin/logbook.xlsx?vehicleId=${VEHICLE_ID}&month=${month}`, true);
    const blob = await res.blob();
    const disposition = res.headers.get("Content-Disposition") ?? "";
    const encoded = /filename\*=UTF-8''([^;]+)/.exec(disposition)?.[1];
    const a = h("a", { href: URL.createObjectURL(blob), download: encoded ? decodeURIComponent(encoded) : `logbook-${month}.xlsx` });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
  } catch (e) {
    alert(`엑셀을 내려받지 못했습니다 (${e.message}).`);
  } finally {
    button.disabled = false;
  }
}

// ---------------------------------------------------------------- loading and refresh

async function load(showLoading = false) {
  const tab = currentTab();
  setTabs(tab);
  if (showLoading || !loadedOnce[tab]) render(stateLoading());
  try {
    if (tab === "logbook") {
      const book = await api(`/admin/logbook?vehicleId=${VEHICLE_ID}`);
      if (currentTab() !== tab) return;
      setVehicle(book.vehicle);
      render(logbookPage(book));
      // The flag tab's count, without holding up the logbook
      api(`/admin/attention?vehicleId=${VEHICLE_ID}`)
        .then((a) => setFlagCount(a.items.length + (a.consentPending.length > 0 ? 1 : 0)))
        .catch(() => {});
    } else {
      const data = await api(`/admin/attention?vehicleId=${VEHICLE_ID}`);
      if (currentTab() !== tab) return;
      setVehicle(data.vehicle);
      render(attentionPage(data));
    }
    loadedOnce[tab] = true;
    lastSuccessAt = Date.now();
    setServer(true);
  } catch (e) {
    if (currentTab() !== tab) return;
    loadedOnce = { flags: false, logbook: false };
    setServer(false);
    render(stateError(tab, e));
  }
}

function schedule() {
  clearInterval(timer);
  // Flags appear while a demo runs (driver chosen, payment made): refresh quietly
  timer = setInterval(() => {
    if (!document.hidden) load(false);
  }, REFRESH_MS);
}

window.addEventListener("hashchange", () => load(false));
load(true);
schedule();
