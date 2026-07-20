(() => {
  const $ = (id) => document.getElementById(id);
  const state = { requests: [], selectedId: null, filter: "active", timer: null, soundTimer: null, notifiedWarning: false };
  const center = { latitude: 34.392, longitude: 132.504 };
  const zoom = 15;

  function pin() { return sessionStorage.getItem("relay_staff_pin") || ""; }
  function nodeId() { return sessionStorage.getItem("relay_node_id") || "fuchu-shelter-pc-1"; }
  function authHeaders(json = false) {
    const headers = { "X-Admin-Key": pin() };
    if (json) headers["Content-Type"] = "application/json";
    return headers;
  }
  function escapeHtml(value) {
    return String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");
  }
  async function api(path, options = {}) {
    const response = await fetch(path, options);
    const text = await response.text();
    let body = text;
    try {
      body = text ? JSON.parse(text) : null;
    } catch (_) { /* plain text */ }
    const outcomes = {
      true: () => body,
      false: () => {
        const errorMsg = typeof body === 'object' ? JSON.stringify(body) : body || response.statusText;
        const error = new Error(errorMsg);
        error.status = response.status;
        throw error;
      }
    };
    return outcomes[response.ok]();
  }
  function fmtTime(value) { return value ? new Date(value).toLocaleString("ja-JP", { hour12: false }) : "不明"; }
  function statusLabel(value) {
    return ({ UNCONFIRMED: "未確認", CONFIRMED: "確認済み", PREPARING: "対応準備中", RESCUE_REQUESTED: "救助機関へ要請済み", RESPONDING: "対応中", COMPLETED: "完了", UNABLE: "対応不可", DUPLICATE: "重複" })[value] || value;
  }
  function conditionLabel(value) {
    return ({ LIFE_THREATENING: "命の危険", INJURED_OR_UNWELL: "けが・体調不良", MOBILITY_IMPAIRED: "自力移動困難", SUPPORT_NEEDED: "生活・医療支援" })[value] || value;
  }
  function needLabel(value) {
    return ({ WATER: "水", FOOD: "食料", MEDICINE: "薬・医療", RESCUE_TEAM: "救助隊", TRANSPORT: "移動支援" })[value] || value;
  }
  function isTerminal(request) { return ["COMPLETED", "UNABLE", "DUPLICATE"].includes(request.responseStatus); }
  function isImmediate(request) { return request.urgency === "IMMEDIATE" && request.action !== "CANCELLED"; }

  async function unlock(event) {
    event.preventDefault();
    const candidate = $("staffPin").value.trim();
    if (!candidate) return;
    sessionStorage.setItem("relay_staff_pin", candidate);
    sessionStorage.setItem("relay_node_id", $("nodeId").value.trim() || "fuchu-shelter-pc-1");
    try {
      await loadRequests();
      $("setup").classList.add("hidden");
      $("staffNodeLabel").textContent = nodeId();
      $("settingsNode").textContent = nodeId();
      ("Notification" in window && Notification.permission === "default") && Notification.requestPermission();
      await refreshAll();
      armRefresh();
    } catch (error) {
      sessionStorage.removeItem("relay_staff_pin");
      const errorMessages = { 401: "PINが違います" };
      const message = errorMessages[error.status] || "接続できません";
      $("staffPin").setCustomValidity(message);
      $("staffPin").reportValidity();
      $("staffPin").setCustomValidity("");
    }
  }

  function lock() {
    sessionStorage.removeItem("relay_staff_pin");
    stopAlarm();
    if (state.timer) clearInterval(state.timer);
    $("staffPin").value = "";
    $("setup").classList.remove("hidden");
  }

  async function loadRequests() {
    const response = await api("/api/rescue/requests", { headers: authHeaders() });
    state.requests = response.items || [];
    $("requestCount").textContent = state.requests.filter((item) => !isTerminal(item)).length;
    if (!state.selectedId && state.requests.length) state.selectedId = state.requests[0].requestId;
    renderRequests();
    renderMaps();
    showCriticalIfNeeded();
    $("lastUpdated").textContent = `更新 ${fmtTime(response.generatedAtEpochMillis)}`;
  }

  function renderRequests() {
    const visible = state.requests.filter((request) => state.filter === "all" || !isTerminal(request));
    if (!visible.length) {
      $("requestList").innerHTML = '<p class="empty">該当する救助依頼はありません。</p>';
      renderDetail(null);
      return;
    }
    $("requestList").innerHTML = visible.map((request) => {
      const selected = request.requestId === state.selectedId;
      const critical = isImmediate(request) && request.responseStatus === "UNCONFIRMED";
      const labels = {
        priority: critical ? "命の危険・未確認" : statusLabel(request.responseStatus),
        personCount: request.personCount != null ? `${request.personCount}人` : "人数不明",
        conditions: request.conditions.map(conditionLabel).join(" / ") || "状態未記載",
        location: request.locationDescription || "GPS位置あり",
        assigned: request.assignedNodeId ? `担当: ${escapeHtml(request.assignedNodeId)}` : "担当未確定"
      };
      return `<button type="button" class="request-card ${selected ? "selected" : ""} ${critical ? "critical" : ""}" data-request="${escapeHtml(request.requestId)}">
        <span class="request-top"><span class="priority">${labels.priority}</span><time>${fmtTime(request.receivedAtEpochMillis)}</time></span>
        <strong>${labels.personCount} · ${labels.conditions}</strong>
        <span>${labels.location}</span>
        <span class="fine">${labels.assigned}</span>
      </button>`;
    }).join("");
    $("requestList").querySelectorAll("[data-request]").forEach((button) => button.addEventListener("click", () => {
      state.selectedId = button.dataset.request;
      renderRequests(); renderMaps();
    }));
    renderDetail(state.requests.find((request) => request.requestId === state.selectedId));
  }

  function renderDetail(request) {
    if (!request) {
      $("selectedDetail").innerHTML = '<p class="empty">依頼を選択してください。</p>';
      return;
    }
    const tagMap = {
      elderlyPresent: "高齢者",
      childrenPresent: "子ども",
      pregnantPresent: "妊娠中",
      trapped: "閉じ込め",
      fireOrCollapseRisk: "火災・倒壊危険"
    };
    const tags = [
      ...request.conditions.map(conditionLabel),
      ...request.supportNeeds.map(needLabel),
      ...Object.entries(tagMap)
        .filter(([key]) => request[key])
        .map(([, label]) => label)
    ].filter(Boolean);
    const ownedElsewhere = request.assignedNodeId && request.assignedNodeId !== nodeId();
    const actions = nextActions(request).map(([status, label, danger]) =>
      `<button class="button ${danger ? "danger-outline" : "primary"}" data-status="${status}" ${ownedElsewhere ? "disabled" : ""}>${label}</button>`
    ).join("");
    $("selectedDetail").innerHTML = `
      <div class="detail-head"><div><p class="eyebrow">${isImmediate(request) ? "IMMEDIATE" : "RESCUE REQUEST"}</p><h3>${request.personCount == null ? "人数不明" : `${request.personCount}人`} / ${statusLabel(request.responseStatus)}</h3></div><span class="status-chip">v${request.requestVersion}</span></div>
      ${ownedElsewhere ? `<p class="assignment-note">${escapeHtml(request.assignedNodeId)} が担当中です。</p>` : ""}
      <dl class="detail-grid"><dt>GPS</dt><dd>${request.latitude?.toFixed(6) ?? "不明"}, ${request.longitude?.toFixed(6) ?? "不明"}</dd><dt>位置精度</dt><dd>${request.accuracyMeters == null ? "不明" : `約${Math.round(request.accuracyMeters)}m`}</dd><dt>位置取得</dt><dd>${fmtTime(request.locationCapturedAtEpochMillis)}</dd><dt>場所の補足</dt><dd>${escapeHtml(request.locationDescription || "なし")}</dd><dt>状態・タグ</dt><dd>${tags.map((tag) => `<span class="tag">${escapeHtml(tag)}</span>`).join(" ") || "なし"}</dd><dt>補足文</dt><dd class="free-text">${escapeHtml(request.freeText || "なし")}</dd><dt>中継端末</dt><dd>${request.uniqueCarrierCount}台</dd></dl>
      <div class="action-row">${actions}</div>`;
    $("selectedDetail").querySelectorAll("[data-status]").forEach((button) => button.addEventListener("click", () => updateStatus(request.requestId, button.dataset.status)));
  }

  function nextActions(request) {
    if (request.action === "CANCELLED" || isTerminal(request)) return [];
    const primary = ({ UNCONFIRMED: ["CONFIRMED", "確認して担当開始"], CONFIRMED: ["PREPARING", "対応準備を開始"], PREPARING: ["RESPONDING", "現地対応を開始"], RESCUE_REQUESTED: ["RESPONDING", "現地対応を開始"], RESPONDING: ["COMPLETED", "対応完了"] })[request.responseStatus];
    return [primary ? [...primary, false] : null, ["UNABLE", "対応不可", true]].filter(Boolean);
  }

  async function updateStatus(id, status) {
    try {
      await api(`/api/rescue/requests/${encodeURIComponent(id)}/status`, { method: "POST", headers: authHeaders(true), body: JSON.stringify({ status, operatorNodeId: nodeId() }) });
      await loadRequests();
    } catch (error) {
      alert(error.status === 409 ? "別のPCが先に担当したか、状態の順序が正しくありません。更新してください。" : `状態更新に失敗しました: ${error.message}`);
    }
  }

  function showCriticalIfNeeded() {
    const urgent = state.requests.find((request) => isImmediate(request) && request.responseStatus === "UNCONFIRMED");
    if (!urgent) { $("criticalAlert").classList.add("hidden"); stopAlarm(); return; }
    state.selectedId = urgent.requestId;
    $("criticalSummary").textContent = `${urgent.personCount == null ? "人数不明" : `${urgent.personCount}人`} / ${urgent.locationDescription || "GPS位置を確認してください"}`;
    $("criticalAlert").classList.remove("hidden");
    $("ackCritical").onclick = () => updateStatus(urgent.requestId, "CONFIRMED");
    startAlarm();
  }

  function startAlarm() {
    if (state.soundTimer) return;
    const beep = () => {
      try {
        const context = new (window.AudioContext || window.webkitAudioContext)();
        const oscillator = context.createOscillator(); const gain = context.createGain();
        oscillator.frequency.value = 880; gain.gain.value = 0.08; oscillator.connect(gain); gain.connect(context.destination);
        oscillator.start(); oscillator.stop(context.currentTime + 0.22); oscillator.onended = () => context.close();
      } catch (_) { /* visual alert remains */ }
    };
    beep(); state.soundTimer = setInterval(beep, 1800);
  }
  function stopAlarm() { if (state.soundTimer) clearInterval(state.soundTimer); state.soundTimer = null; }

  function globalPixel(latitude, longitude, z) {
    const scale = 256 * 2 ** z; const sin = Math.sin(latitude * Math.PI / 180);
    return { x: (longitude + 180) / 360 * scale, y: (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * scale };
  }
  function renderTileMap(element, requests) {
    const viewportWidth = element.clientWidth || (element.classList.contains("large") ? 935 : 560);
    const viewportHeight = element.clientHeight || (element.classList.contains("large") ? 560 : 310);
    const widthTiles = Math.ceil(viewportWidth / 256) + 1; const heightTiles = Math.ceil(viewportHeight / 256) + 1;
    const centerPixel = globalPixel(center.latitude, center.longitude, zoom);
    const viewportLeft = centerPixel.x - viewportWidth / 2; const viewportTop = centerPixel.y - viewportHeight / 2;
    const startX = Math.floor(viewportLeft / 256); const startY = Math.floor(viewportTop / 256);
    element.innerHTML = "";
    for (let row = 0; row < heightTiles; row += 1) for (let column = 0; column < widthTiles; column += 1) {
      const image = document.createElement("img"); image.className = "map-tile"; image.alt = "";
      image.src = `/api/map/tiles/${zoom}/${startX + column}/${startY + row}.png`;
      image.style.left = `${(startX + column) * 256 - viewportLeft}px`;
      image.style.top = `${(startY + row) * 256 - viewportTop}px`; element.appendChild(image);
    }
    requests.filter((request) => request.latitude != null && request.longitude != null).forEach((request) => {
      const point = globalPixel(request.latitude, request.longitude, zoom); const marker = document.createElement("button");
      marker.className = `map-marker ${isImmediate(request) ? "critical" : ""}`; marker.type = "button";
      marker.style.left = `${point.x - viewportLeft}px`; marker.style.top = `${point.y - viewportTop}px`;
      marker.title = `${request.personCount ?? "人数不明"} / ${statusLabel(request.responseStatus)}`;
      marker.addEventListener("click", () => { state.selectedId = request.requestId; activatePanel("rescue"); renderRequests(); renderMaps(); });
      element.appendChild(marker);
    });
  }
  function renderMaps() { renderTileMap($("rescueMap"), state.requests.filter((item) => !isTerminal(item))); renderTileMap($("fullMap"), state.requests); }

  async function loadMapStatus() {
    const map = await api("/api/map/status", { headers: authHeaders() });
    const ratio = map.expectedTiles ? map.cachedTiles / map.expectedTiles : 0;
    $("mapProgress").value = ratio;
    $("mapProgressLabel").textContent = `${map.cachedTiles} / ${map.expectedTiles} タイル保存済み${map.lastError ? ` / ${map.lastError}` : ""}`;
    const stateTexts = {
      ready: "オフライン準備済み",
      preparing: "地図保存中"
    };
    $("mapStatus").textContent = stateTexts[map.state] || "地図未完了";
    const disabledStates = new Set(["preparing", "ready"]);
    $("prepareMap").disabled = disabledStates.has(map.state);
  }
  async function prepareMap() { await api("/api/map/prepare", { method: "POST", headers: authHeaders() }); await loadMapStatus(); }

  async function loadOfficial() {
    const info = await api("/api/official-info");
    const alertMessages = {
      true: `気象庁: ${info.warningHeadline}`,
      false: `公式情報: ${info.warningHeadline}`
    };
    const detailList = info.warningStatuses.map((value) => `<li>${escapeHtml(value)}</li>`).join("") || "<li>府中町の発表状況なし</li>";
    $("officialAlert").textContent = alertMessages[info.urgent];
    $("officialAlert").classList.toggle("urgent", info.urgent);
    $("warningDetail").innerHTML = `<h3>気象庁 警報・注意報</h3><p>${escapeHtml(info.warningHeadline)}</p><ul>${detailList}</ul><p class="fine">確認 ${fmtTime(info.checkedAtEpochMillis)}${info.usedCachedWarning ? "（保存済み情報）" : ""}</p>`;
    $("officialSources").innerHTML = info.sources.map((source) => `<a class="source-card" href="${escapeHtml(source.url)}" target="_blank" rel="noopener"><strong>${escapeHtml(source.title)}</strong><span>${escapeHtml(source.organization)} 公式サイト</span></a>`).join("");
    if (info.urgent && !state.notifiedWarning && "Notification" in window && Notification.permission === "granted") {
      new Notification("Relay 府中町 公式警報", { body: info.warningHeadline }); state.notifiedWarning = true;
    }
  }

  async function refreshAll() {
    try {
      await Promise.all([loadRequests(), loadMapStatus(), loadOfficial(), api("/api/health").then((health) => { $("healthStatus").textContent = `Gateway ${health.status} / BLE ${health.bleBridgeStatus}`; })]);
      $("connectionDot").classList.add("online"); $("connectionLabel").textContent = "接続中";
    } catch (error) {
      if (error.status === 401) return lock();
      $("connectionDot").classList.remove("online"); $("connectionLabel").textContent = "再接続中";
    }
  }
  function armRefresh() { if (state.timer) clearInterval(state.timer); state.timer = setInterval(refreshAll, 5000); }
  function activatePanel(name) {
    document.querySelectorAll(".tab").forEach((tab) => tab.classList.toggle("active", tab.dataset.panel === name));
    document.querySelectorAll(".panel").forEach((panel) => panel.classList.toggle("active", panel.id === `panel-${name}`));
  }

  $("setupForm").addEventListener("submit", unlock); $("lockButton").addEventListener("click", lock);
  $("refreshButton").addEventListener("click", refreshAll); $("prepareMap").addEventListener("click", prepareMap);
  document.querySelectorAll(".tab").forEach((tab) => tab.addEventListener("click", () => activatePanel(tab.dataset.panel)));
  document.querySelectorAll(".filter").forEach((button) => button.addEventListener("click", () => { state.filter = button.dataset.filter; document.querySelectorAll(".filter").forEach((item) => item.classList.toggle("active", item === button)); renderRequests(); }));
  if (pin()) { $("staffPin").value = pin(); $("nodeId").value = nodeId(); $("setupForm").requestSubmit(); }
})();
