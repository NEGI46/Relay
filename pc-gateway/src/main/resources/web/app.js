(() => {
  const $ = (id) => document.getElementById(id);
  const state = { timer: null };

  function adminKey() {
    return ($("adminKey").value || localStorage.getItem("relay_admin_key") || "").trim();
  }

  function saveKey() {
    const key = $("adminKey").value.trim();
    if (key) localStorage.setItem("relay_admin_key", key);
    else localStorage.removeItem("relay_admin_key");
    $("footerStatus").textContent = key ? "管理者キーをこのブラウザに保存しました" : "管理者キーを削除しました";
  }

  function authHeaders(json = false) {
    const headers = { "X-Admin-Key": adminKey() };
    if (json) headers["Content-Type"] = "application/json";
    return headers;
  }

  async function api(path, options = {}) {
    const res = await fetch(path, options);
    const text = await res.text();
    let body = text;
    try {
      body = text ? JSON.parse(text) : null;
    } catch (_) {
      /* keep text */
    }
    if (!res.ok) {
      const err = new Error(typeof body === "object" ? JSON.stringify(body) : body || res.statusText);
      err.status = res.status;
      throw err;
    }
    return body;
  }

  function fmtTime(ms) {
    if (ms == null || ms === 0) return "—";
    try {
      return new Date(ms).toLocaleString("ja-JP", { hour12: false });
    } catch {
      return String(ms);
    }
  }

  function trustBadge(trust) {
    const t = (trust || "").toUpperCase();
    if (t === "VERIFIED") return `<span class="badge verified">VERIFIED</span>`;
    return `<span class="badge unverified">UNVERIFIED</span>`;
  }

  function priorityBadge(p) {
    const key = (p || "NORMAL").toLowerCase();
    return `<span class="badge ${key}">${p || "NORMAL"}</span>`;
  }

  function typeLabel(t) {
    if (t === "SAFETY") return "安否";
    if (t === "SUPPLY") return "物資";
    return t || "—";
  }

  function escapeHtml(s) {
    return String(s)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
  }

  function row(k, v) {
    return `<dt>${k}</dt><dd>${escapeHtml(String(v))}</dd>`;
  }

  function prettyJson(raw) {
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch {
      return raw || "";
    }
  }

  function renderStats(d) {
    $("statCards").innerHTML = [
      stat("保存件数", d.totalMessages, ""),
      stat("ACTIVE", d.activeMessages, "active"),
      stat("UNVERIFIED", d.unverifiedMessages, "unverified"),
      stat("VERIFIED", d.verifiedMessages, "verified"),
      stat("Bridge", `${d.pairedBridgeCount}/${d.bridgeCount}`, ""),
      stat("Receipt", d.receiptCount, ""),
    ].join("");
  }

  function stat(label, value, cls) {
    return `<div class="stat ${cls}"><div class="label">${label}</div><div class="value">${value}</div></div>`;
  }

  function renderService(d) {
    $("subtitle").textContent = `${d.gatewayId} · 中継拠点コンソール`;
    $("opsDiscoveryPort").textContent = d.lanDiscoveryPort;
    $("serviceStatus").innerHTML = [
      row("Gateway ID", d.gatewayId),
      row("Bind", `${d.bindHost}:${d.httpPort}`),
      row("公開同期", d.anonymousIngressEnabled ? "有効" : "無効"),
      row("LAN 発見", d.lanDiscoveryEnabled ? `UDP ${d.lanDiscoveryPort}` : "無効"),
      row("DB 上限", d.maxStoredMessages.toLocaleString()),
      row("スナップショット", fmtTime(d.generatedAt)),
    ].join("");
  }

  function renderTypes(d) {
    if (!d.byType.length) {
      $("typeBreakdown").innerHTML = `<div class="empty">まだメッセージがありません</div>`;
      return;
    }
    const max = Math.max(1, ...d.byType.map((x) => x.count));
    $("typeBreakdown").innerHTML = d.byType
      .map((t) => {
        const pct = Math.round((t.count / max) * 100);
        return `<div class="bar-row"><span>${escapeHtml(typeLabel(t.messageType))}</span>
        <div class="bar-track"><div class="bar-fill" style="width:${pct}%"></div></div>
        <span>${t.count}</span></div>`;
      })
      .join("");
  }

  function renderRecent(d) {
    $("recentHint").textContent = `${d.recentMessages.length} 件`;
    if (!d.recentMessages.length) {
      $("recentBody").innerHTML = `<tr><td colspan="6" class="empty">受信待ち</td></tr>`;
      return;
    }
    $("recentBody").innerHTML = d.recentMessages
      .map((m) => {
        const cls = (m.ingressTrust || "").toUpperCase() === "VERIFIED" ? "verified" : "unverified";
        return `<tr class="${cls}">
        <td>${priorityBadge(m.priority)}</td>
        <td>${escapeHtml(typeLabel(m.messageType))}</td>
        <td>${trustBadge(m.ingressTrust)}</td>
        <td>${escapeHtml(m.status)}</td>
        <td title="${escapeHtml(m.origin)}">${escapeHtml((m.origin || "").slice(0, 12))}</td>
        <td>${fmtTime(m.receivedAt)}</td>
      </tr>`;
      })
      .join("");
  }

  function renderBridges(d) {
    if (!d.bridges.length) {
      $("bridgesBody").innerHTML =
        `<tr><td colspan="7" class="empty">Bridge 未登録（公開経路のみで運用可能）</td></tr>`;
      return;
    }
    $("bridgesBody").innerHTML = d.bridges
      .map(
        (b) => `<tr>
      <td>${escapeHtml(b.bridgeId)}</td>
      <td>${escapeHtml(b.name)}</td>
      <td>${b.paired ? '<span class="badge ok">paired</span>' : '<span class="badge off">no</span>'}</td>
      <td>${b.connected ? '<span class="badge ok">yes</span>' : '<span class="badge off">no</span>'}</td>
      <td>${fmtTime(b.lastSyncAt)}</td>
      <td>${b.receivedCount}</td>
      <td><button type="button" class="btn small danger" data-revoke="${escapeHtml(b.bridgeId)}">失効</button></td>
    </tr>`,
      )
      .join("");

    $("bridgesBody").querySelectorAll("[data-revoke]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        if (!confirm(`Bridge ${btn.dataset.revoke} の token を失効しますか？`)) return;
        try {
          await api("/api/pair/reject", {
            method: "POST",
            headers: authHeaders(true),
            body: JSON.stringify({ bridgeId: btn.dataset.revoke }),
          });
          $("opsResult").textContent = `revoked ${btn.dataset.revoke}`;
          await refresh();
        } catch (e) {
          $("opsResult").textContent = `error ${e.status || ""}: ${e.message}`;
        }
      });
    });
  }

  function renderMessages(list) {
    $("messagesMeta").textContent = `${list.length} 件表示`;
    if (!list.length) {
      $("messagesBody").innerHTML = `<tr><td colspan="10" class="empty">該当なし</td></tr>`;
      return;
    }
    $("messagesBody").innerHTML = list
      .map((m) => {
        const cls = (m.ingressTrust || "").toUpperCase() === "VERIFIED" ? "verified" : "unverified";
        const rx =
          [m.gatewayReceived ? "V" : null, m.gatewayReceivedUnverified ? "U" : null]
            .filter(Boolean)
            .join("/") || "—";
        return `<tr class="${cls}">
        <td title="${escapeHtml(m.messageId)}">${escapeHtml(m.messageId.slice(0, 12))}</td>
        <td>${escapeHtml(typeLabel(m.messageType))}</td>
        <td>${priorityBadge(m.priority)}</td>
        <td>${escapeHtml(m.status)}</td>
        <td>${trustBadge(m.ingressTrust)}</td>
        <td>${rx}</td>
        <td>${escapeHtml((m.sourceBridgeId || "public").slice(0, 10))}</td>
        <td title="${escapeHtml(m.origin)}">${escapeHtml((m.origin || "").slice(0, 10))}</td>
        <td>${fmtTime(m.receivedAt)}</td>
        <td><button type="button" class="btn small secondary" data-detail="${escapeHtml(m.messageId)}">詳細</button></td>
      </tr>`;
      })
      .join("");

    $("messagesBody").querySelectorAll("[data-detail]").forEach((btn) => {
      btn.addEventListener("click", () => openDetail(btn.dataset.detail));
    });
  }

  async function openDetail(id) {
    try {
      const d = await api(`/api/messages/${encodeURIComponent(id)}`, { headers: authHeaders() });
      $("detailBody").innerHTML = `
        <dl class="kv">
          ${row("messageId", d.messageId)}
          ${row("type", d.messageType)}
          ${row("record", d.recordType)}
          ${row("priority", d.priority)}
          ${row("status", d.status)}
          ${row("trust", d.ingressTrust)}
          ${row("origin", d.originDeviceId)}
          ${row("bridge", d.sourceBridgeId || "public")}
          ${row("hops", `${d.hopCount} / ${d.hopLimit}`)}
          ${row("created", fmtTime(d.createdAt))}
          ${row("received", fmtTime(d.receivedAt))}
          ${row("age/lifetime", `${d.accumulatedAgeMs} / ${d.lifetimeMs} ms`)}
          ${row("receipts", (d.receipts || []).map((r) => r.receiptType).join(", ") || "—")}
        </dl>
        <h3>payload</h3>
        <pre>${escapeHtml(prettyJson(d.payloadJson))}</pre>
        <p class="muted">運用者向け表示です。ログや外部共有には載せないでください。</p>
      `;
      $("detailDialog").showModal();
    } catch (e) {
      alert(`詳細取得に失敗: ${e.status || ""} ${e.message}`);
    }
  }

  async function refreshDashboard() {
    const d = await api("/api/dashboard");
    renderStats(d);
    renderService(d);
    renderTypes(d);
    renderRecent(d);
    renderBridges(d);
    $("footerStatus").textContent = `online · ${d.gatewayId} · messages ${d.totalMessages}`;
    $("footerClock").textContent = fmtTime(d.generatedAt);
  }

  async function refreshMessages() {
    const q = new URLSearchParams();
    const query = $("msgQuery").value.trim();
    const type = $("msgType").value;
    const status = $("msgStatus").value;
    const trust = $("msgTrust").value;
    if (query) q.set("q", query);
    if (type) q.set("type", type);
    if (status) q.set("status", status);
    if (trust) q.set("trust", trust);
    q.set("limit", "300");
    const data = await api(`/api/messages?${q}`, { headers: authHeaders() });
    renderMessages(data.items || []);
  }

  async function refresh() {
    try {
      await refreshDashboard();
      if (adminKey()) {
        try {
          await refreshMessages();
        } catch (e) {
          $("messagesMeta").textContent = `メッセージ取得失敗 (${e.status || "?"}): 管理者キーを確認`;
        }
      } else {
        $("messagesMeta").textContent = "メッセージ一覧・詳細・CSV には管理者キーが必要です";
      }
    } catch (e) {
      $("footerStatus").textContent = `error: ${e.message}`;
    }
  }

  function setupTabs() {
    document.querySelectorAll(".tab").forEach((tab) => {
      tab.addEventListener("click", () => {
        document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
        document.querySelectorAll(".panel").forEach((p) => p.classList.remove("active"));
        tab.classList.add("active");
        $(`panel-${tab.dataset.tab}`).classList.add("active");
      });
    });
  }

  function setupOps() {
    $("btnPairCode").addEventListener("click", async () => {
      try {
        const body = await api("/api/pair/code", { headers: authHeaders() });
        $("pairCodeResult").textContent = typeof body === "object" ? JSON.stringify(body, null, 2) : body;
      } catch (e) {
        $("pairCodeResult").textContent = `error ${e.status || ""}: ${e.message}`;
      }
    });

    $("formApprove").addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const fd = new FormData(ev.target);
      try {
        const body = await api("/api/pair/approve", {
          method: "POST",
          headers: authHeaders(true),
          body: JSON.stringify({ bridgeId: fd.get("bridgeId"), code: fd.get("code") }),
        });
        $("opsResult").textContent = JSON.stringify(body, null, 2);
        await refresh();
      } catch (e) {
        $("opsResult").textContent = `error ${e.status || ""}: ${e.message}`;
      }
    });

    $("formReject").addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const fd = new FormData(ev.target);
      const payload = { bridgeId: fd.get("bridgeId") };
      const code = (fd.get("code") || "").trim();
      if (code) payload.code = code;
      try {
        await api("/api/pair/reject", {
          method: "POST",
          headers: authHeaders(true),
          body: JSON.stringify(payload),
        });
        $("opsResult").textContent = "rejected / revoked";
        await refresh();
      } catch (e) {
        $("opsResult").textContent = `error ${e.status || ""}: ${e.message}`;
      }
    });
  }

  function setupAutoRefresh() {
    const arm = () => {
      if (state.timer) clearInterval(state.timer);
      if ($("autoRefresh").checked) state.timer = setInterval(refresh, 5000);
    };
    $("autoRefresh").addEventListener("change", arm);
    arm();
  }

  function init() {
    const saved = localStorage.getItem("relay_admin_key");
    if (saved) $("adminKey").value = saved;
    setupTabs();
    setupOps();
    setupAutoRefresh();
    $("btnSaveKey").addEventListener("click", saveKey);
    $("btnRefresh").addEventListener("click", refresh);
    $("btnFilter").addEventListener("click", () => refreshMessages().catch((e) => alert(e.message)));
    $("btnExport").addEventListener("click", async () => {
      try {
        const res = await fetch("/api/messages/export.csv", { headers: authHeaders() });
        if (!res.ok) throw new Error(await res.text());
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = `relay-messages-${Date.now()}.csv`;
        a.click();
        URL.revokeObjectURL(url);
      } catch (e) {
        alert(`CSV 失敗: ${e.message}`);
      }
    });
    refresh();
  }

  init();
})();
