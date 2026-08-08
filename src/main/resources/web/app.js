"use strict";

const state = {
  csrf: "",
  role: "",
  overview: null,
  worlds: [],
  candidates: [],
  candidatePage: { total: 0, offset: 0, limit: 50 },
  candidateFocusId: null,
  selectedCandidate: null,
  candidateLoading: false,
  symbols: [],
  filteredSymbols: [],
  stations: [],
  lines: [],
  routePlans: [],
  routeDraftCalls: [],
  gates: [],
  zones: [],
  fareRules: [],
  selectedSymbol: null,
  graph: { nodes: [], edges: [], materials: [], shapes: [], runId: null, worldName: "", truncated: false },
  networkMap: null,
  mapViewMode: "TOP",
  railBlocksVisible: false,
  mapGridVisible: false,
  mapLoadSequence: 0
};

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

document.addEventListener("DOMContentLoaded", initialize);

async function initialize() {
  initializeTheme();
  try {
    state.networkMap = new window.AtcsNetworkMap($("#network-canvas"), selectSymbol);
    state.networkMap.setTheme(document.documentElement.dataset.theme);
  } catch (error) {
    $("#map-empty").hidden = false;
    $("#map-empty").replaceChildren(
      Object.assign(document.createElement("b"), { textContent: "WEBGL2 MAP UNAVAILABLE" }),
      Object.assign(document.createElement("span"), { textContent: error.message })
    );
  }
  bindEvents();
  updateClock();
  setInterval(updateClock, 1000);
  try {
    const session = await request("/api/v1/auth/session", { suppressAuthenticationPrompt: true });
    acceptSession(session);
    await loadApplication();
  } catch (error) {
    showLogin(error.status !== 401 ? "Administration service is unavailable." : "");
  }
}

function bindEvents() {
  $("#login-form").addEventListener("submit", login);
  $("#theme-toggle").addEventListener("click", toggleTheme);
  $("#logout-button").addEventListener("click", logout);
  $$(".module-strip button[data-view]").forEach((button) => {
    button.addEventListener("click", () => activateView(button.dataset.view));
  });
  $("#apply-filter").addEventListener("click", applyFilters);
  $("#clear-filter").addEventListener("click", clearFilters);
  $("#search-filter").addEventListener("keydown", (event) => {
    if (event.key === "Enter") applyFilters();
  });
  $("#refresh-symbols").addEventListener("click", refreshSymbols);
  $("#refresh-candidates").addEventListener("click", () => refreshCandidates());
  $("#apply-candidate-filter").addEventListener("click", applyCandidateFilters);
  $("#clear-candidate-filter").addEventListener("click", clearCandidateFilters);
  $("#candidate-search-filter").addEventListener("keydown", (event) => {
    if (event.key === "Enter") applyCandidateFilters();
  });
  $("#candidate-previous").addEventListener("click", () => changeCandidatePage(-1));
  $("#candidate-next").addEventListener("click", () => changeCandidatePage(1));
  $("#candidate-decision-form").addEventListener("submit", saveCandidateDecision);
  $("#candidate-decision-form select[name=decision]").addEventListener("change", updateCandidateDecisionFields);
  $("#candidate-decision-form input[name=targetId]").addEventListener("input", renderCandidateAuthorityPreview);
  $("#candidate-decision-form input[name=displayName]").addEventListener("input", renderCandidateAuthorityPreview);
  $("#show-candidate-source").addEventListener("click", showCandidateSourceOnMap);
  $("#candidate-inspector").addEventListener("keydown", (event) => {
    if (event.key === "ArrowLeft" && event.target === event.currentTarget) {
      event.preventDefault();
      focusCandidateRow(state.selectedCandidate?.candidateId);
    }
  });
  $("#refresh-stations").addEventListener("click", refreshStations);
  $("#decision-form").addEventListener("submit", saveDecision);
  $("#decision-form select[name=decision]").addEventListener("change", updateDecisionFields);
  $("#prefill-platform").addEventListener("click", prefillPlatformForm);
  $("#station-form").addEventListener("submit", createStation);
  $("#platform-form").addEventListener("submit", createPlatform);
  $("#refresh-lines").addEventListener("click", refreshLines);
  $("#line-form").addEventListener("submit", saveLine);
  $("#new-line").addEventListener("click", resetLineForm);
  $("#route-plan-form").addEventListener("submit", saveRoutePlan);
  $("#new-route-plan").addEventListener("click", resetRoutePlanForm);
  $("#call-station").addEventListener("change", rebuildCallPlatformSelect);
  $("#add-route-call").addEventListener("click", addRouteCall);
  $("#refresh-fares").addEventListener("click", refreshFares);
  $("#gate-form").addEventListener("submit", saveGate);
  $("#new-gate").addEventListener("click", resetGateForm);
  $("#zone-form").addEventListener("submit", saveZone);
  $("#new-zone").addEventListener("click", resetZoneForm);
  $("#fare-rule-form").addEventListener("submit", saveFareRule);
  $("#new-fare-rule").addEventListener("click", resetFareRuleForm);
  $("#refresh-audit").addEventListener("click", refreshAudit);
  $("#reload-map").addEventListener("click", loadMap);
  $("#fit-map").addEventListener("click", fitMap);
  $("#north-map").addEventListener("click", () => state.networkMap?.north());
  $("#map-object-scope").addEventListener("change", handleMapObjectScopeChange);
  $("#toggle-map-grid").addEventListener("click", toggleMapGrid);
  $("#toggle-rail-blocks").addEventListener("click", toggleRailBlocks);
  $("#view-3d-map").addEventListener("click", () => setMapView(false));
  $("#view-top-map").addEventListener("click", () => setMapView(true));
  $("#map-world").addEventListener("change", loadMap);
  new ResizeObserver(resizeCanvas).observe($(".map-shell"));
}

function initializeTheme() {
  const saved = localStorage.getItem("atcs-theme");
  applyTheme(saved === "dark" ? "dark" : "light");
}

function toggleTheme() {
  applyTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark");
}

function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  localStorage.setItem("atcs-theme", theme);
  const toggle = $("#theme-toggle");
  if (toggle) {
    const dark = theme === "dark";
    toggle.textContent = dark ? "LIGHT" : "DARK";
    toggle.setAttribute("aria-label", dark ? "Switch to light theme" : "Switch to dark theme");
  }
  state.networkMap?.setTheme(theme);
}

async function login(event) {
  event.preventDefault();
  const formElement = event.currentTarget;
  const token = new FormData(formElement).get("token");
  $("#login-message").textContent = "CHECKING CREDENTIALS...";
  try {
    const session = await request("/api/v1/auth/login", {
      method: "POST",
      body: { token },
      suppressAuthenticationPrompt: true
    });
    formElement.reset();
    acceptSession(session);
    await loadApplication();
  } catch (error) {
    $("#login-message").textContent = error.message || "LOGON FAILED";
  }
}

async function logout() {
  try {
    await request("/api/v1/auth/logout", { method: "POST" });
  } finally {
    state.csrf = "";
    showLogin("SESSION CLOSED");
  }
}

function acceptSession(session) {
  state.csrf = session.csrfToken;
  state.role = session.role;
  $("#session-role").textContent = session.role;
  $("#login-screen").hidden = true;
  $("#application").hidden = false;
}

function showLogin(message = "") {
  $("#application").hidden = true;
  $("#login-screen").hidden = false;
  $("#login-message").textContent = message;
  setTimeout(() => $("#admin-token").focus(), 0);
}

async function loadApplication() {
  await Promise.all([
    refreshOverview(), refreshWorlds(), refreshSymbols(), refreshStations(),
    refreshLines(), refreshFares(), refreshCandidates()
  ]);
  rebuildFilters();
  await loadMap();
  resizeCanvas();
}

async function refreshWorlds() {
  const response = await request("/api/v1/worlds");
  state.worlds = response.items;
}

async function refreshOverview() {
  state.overview = await request("/api/v1/overview");
  const rows = [
    ["Symbols", state.overview.symbols],
    ["Pending", state.overview.pendingSymbols],
    ["Included", state.overview.includedSymbols],
    ["Ignored", state.overview.ignoredSymbols],
    ["Stations", state.overview.stations],
    ["Platforms", state.overview.platforms],
    ["Lines", state.overview.lines],
    ["Fare gates", state.overview.gates],
    ["Fare rules", state.overview.fareRules]
  ];
  const register = $("#overview-list");
  register.replaceChildren(...rows.map(([label, value]) => {
    const wrapper = document.createElement("div");
    const term = document.createElement("dt");
    const definition = document.createElement("dd");
    term.textContent = label;
    definition.textContent = value;
    wrapper.append(term, definition);
    return wrapper;
  }));
  const rail = state.overview.railRun;
  $("#rail-status").innerHTML = rail.runId
    ? `<span class="lamp green"></span>RAIL ${rail.nodes} NODES / ${rail.edges} EDGES / ${rail.chunksScanned}/${rail.chunksConsidered} CHUNKS`
    : `<span class="lamp amber"></span>RAIL SNAPSHOT NOT AVAILABLE`;
}

async function refreshSymbols() {
  const all = [];
  let offset = 0;
  let total = 1;
  while (offset < total) {
    const page = await request(`/api/v1/symbols?offset=${offset}&limit=100`);
    all.push(...page.items);
    total = page.total;
    offset += page.items.length;
    if (page.items.length === 0) break;
  }
  state.symbols = all;
  if (state.selectedSymbol) {
    state.selectedSymbol = state.symbols.find((item) => item.id === state.selectedSymbol.id) || null;
  }
  applyFilters();
  rebuildFilters();
  renderInspector();
  updateNetworkMapScene();
}

async function refreshCandidates(options = {}) {
  const preserveSelection = options.preserveSelection !== false;
  state.candidateLoading = true;
  $("#candidate-list-message").textContent = "LOADING CANDIDATES...";
  renderCandidateTable();
  try {
    const query = candidateQuery();
    query.set("offset", String(state.candidatePage.offset));
    query.set("limit", String(state.candidatePage.limit));
    const page = await request(`/api/v1/infrastructure-candidates?${query.toString()}`);
    const pageTotal = Number(page.total || 0);
    const pageLimit = Number(page.limit || state.candidatePage.limit);
    if (pageTotal > 0 && state.candidatePage.offset >= pageTotal) {
      state.candidatePage.offset = Math.floor((pageTotal - 1) / pageLimit) * pageLimit;
      return await refreshCandidates(options);
    }
    state.candidates = page.items || [];
    state.candidatePage = {
      total: pageTotal,
      offset: Number(page.offset || 0),
      limit: pageLimit
    };
    if (preserveSelection && state.selectedCandidate) {
      const current = state.candidates.find((item) => item.candidateId === state.selectedCandidate.candidateId);
      state.selectedCandidate = current || null;
    } else if (!preserveSelection) {
      state.selectedCandidate = null;
    }
    if (!state.candidates.some((item) => item.candidateId === state.candidateFocusId)) {
      state.candidateFocusId = state.selectedCandidate?.candidateId || state.candidates[0]?.candidateId || null;
    }
    $("#candidate-list-message").textContent = "";
    rebuildFilters();
  } catch (error) {
    state.candidates = [];
    state.candidatePage.total = 0;
    state.candidateFocusId = null;
    state.selectedCandidate = null;
    $("#candidate-list-message").textContent = `${error.message} Reload the candidate register to try again.`;
  } finally {
    state.candidateLoading = false;
    renderCandidateTable();
    renderInspector();
  }
}

function candidateQuery() {
  const query = new URLSearchParams();
  const filters = [
    ["world", $("#candidate-world-filter").value],
    ["type", $("#candidate-type-filter").value],
    ["decision", $("#candidate-decision-filter").value],
    ["role", $("#candidate-role-filter").value],
    ["search", $("#candidate-search-filter").value.trim()]
  ];
  filters.forEach(([key, value]) => { if (value) query.set(key, value); });
  return query;
}

function applyCandidateFilters() {
  state.candidatePage.offset = 0;
  refreshCandidates({ preserveSelection: false });
}

function clearCandidateFilters() {
  $("#candidate-world-filter").value = "";
  $("#candidate-type-filter").value = "";
  $("#candidate-decision-filter").value = "";
  $("#candidate-role-filter").value = "";
  $("#candidate-search-filter").value = "";
  applyCandidateFilters();
}

function changeCandidatePage(direction) {
  if (state.candidateLoading) return;
  const nextOffset = state.candidatePage.offset + direction * state.candidatePage.limit;
  if (nextOffset < 0 || nextOffset >= state.candidatePage.total) return;
  state.candidatePage.offset = nextOffset;
  refreshCandidates({ preserveSelection: false });
}

function renderCandidateTable() {
  const body = $("#candidate-table-body");
  if (state.candidateLoading) {
    body.replaceChildren(candidateStateRow("Loading the latest completed discovery run..."));
  } else if (state.candidates.length === 0) {
    body.replaceChildren(candidateStateRow("No candidates match the current filters."));
  } else {
    const focusCandidateId = state.candidates.some((item) => item.candidateId === state.candidateFocusId)
      ? state.candidateFocusId
      : state.selectedCandidate?.candidateId || state.candidates[0].candidateId;
    state.candidateFocusId = focusCandidateId;
    body.replaceChildren(...state.candidates.map((item) => {
      const row = document.createElement("tr");
      const selected = state.selectedCandidate?.candidateId === item.candidateId;
      row.classList.toggle("selected", selected);
      row.dataset.candidateId = item.candidateId;
      row.tabIndex = item.candidateId === focusCandidateId ? 0 : -1;
      row.setAttribute("aria-selected", String(selected));
      row.setAttribute("aria-label", `${candidateTypeLabel(item.kind)} ${candidateRoleLabel(item.suggestedRole)} at ${item.worldName} ${item.blockX} ${item.blockY} ${item.blockZ}`);
      const stateCell = document.createElement("td");
      const pill = document.createElement("span");
      pill.className = `state-pill ${item.decision.status.toLowerCase()}`;
      pill.textContent = item.decision.status;
      stateCell.append(pill);
      const values = [
        candidateTypeLabel(item.kind),
        candidateRoleLabel(item.suggestedRole),
        formatConfidence(item.confidence),
        item.worldName,
        item.blockX,
        item.blockY,
        item.blockZ,
        item.sourceSignId.slice(0, 8)
      ];
      row.append(stateCell, ...values.map((value) => {
        const cell = document.createElement("td");
        cell.textContent = value;
        cell.title = String(value);
        return cell;
      }));
      const choose = () => {
        state.candidateFocusId = item.candidateId;
        return selectCandidate(item.candidateId);
      };
      row.addEventListener("click", choose);
      row.addEventListener("focus", () => { state.candidateFocusId = item.candidateId; });
      row.addEventListener("keydown", (event) => {
        switch (event.key) {
          case "ArrowDown":
            event.preventDefault();
            moveCandidateRowFocus(row, 1);
            break;
          case "ArrowUp":
            event.preventDefault();
            moveCandidateRowFocus(row, -1);
            break;
          case "Home":
            event.preventDefault();
            moveCandidateRowFocus(row, -Infinity);
            break;
          case "End":
            event.preventDefault();
            moveCandidateRowFocus(row, Infinity);
            break;
          case "ArrowRight":
            event.preventDefault();
            state.candidateFocusId = item.candidateId;
            selectCandidate(item.candidateId, { focusInspector: true });
            break;
          case "Enter":
          case " ":
            event.preventDefault();
            choose();
            break;
          default:
            break;
        }
      });
      return row;
    }));
  }
  const { total, offset, limit } = state.candidatePage;
  const start = total === 0 ? 0 : offset + 1;
  const end = Math.min(offset + state.candidates.length, total);
  $("#candidate-count").textContent = `${total} CANDIDATES`;
  $("#candidate-page-status").textContent = `${start}–${end} OF ${total}`;
  $("#candidate-previous").disabled = state.candidateLoading || offset <= 0;
  $("#candidate-next").disabled = state.candidateLoading || offset + limit >= total;
}

function moveCandidateRowFocus(currentRow, direction) {
  const rows = $$("#candidate-table-body tr[data-candidate-id]");
  if (rows.length === 0) return;
  const currentIndex = Math.max(0, rows.indexOf(currentRow));
  const nextIndex = direction === -Infinity
    ? 0
    : direction === Infinity
      ? rows.length - 1
      : Math.max(0, Math.min(rows.length - 1, currentIndex + direction));
  const nextRow = rows[nextIndex];
  rows.forEach((row) => { row.tabIndex = row === nextRow ? 0 : -1; });
  state.candidateFocusId = nextRow.dataset.candidateId;
  nextRow.focus();
}

function focusCandidateRow(candidateId) {
  if (!candidateId) return;
  const rows = $$("#candidate-table-body tr[data-candidate-id]");
  const row = rows.find((candidateRow) => candidateRow.dataset.candidateId === candidateId);
  if (!row) return;
  rows.forEach((candidateRow) => { candidateRow.tabIndex = candidateRow === row ? 0 : -1; });
  state.candidateFocusId = candidateId;
  row.focus();
}

function candidateStateRow(message) {
  const row = document.createElement("tr");
  const cell = document.createElement("td");
  cell.className = "empty-row";
  cell.colSpan = 9;
  cell.textContent = message;
  row.append(cell);
  return row;
}

async function selectCandidate(candidateId, options = {}) {
  const existing = state.candidates.find((item) => item.candidateId === candidateId);
  state.candidateFocusId = candidateId;
  state.selectedCandidate = existing || null;
  renderCandidateTable();
  renderInspector();
  $("#candidate-decision-message").textContent = existing ? "Loading complete candidate evidence..." : "";
  let failureMessage = "";
  try {
    state.selectedCandidate = await request(`/api/v1/infrastructure-candidates/${encodeURIComponent(candidateId)}`);
  } catch (error) {
    failureMessage = `${error.message} Reload the candidate and try again.`;
  }
  renderCandidateTable();
  renderInspector();
  $("#candidate-decision-message").textContent = failureMessage;
  if (options.focusInspector) $("#candidate-inspector").focus();
  else focusCandidateRow(candidateId);
}

async function refreshStations() {
  const response = await request("/api/v1/stations");
  state.stations = response.items;
  renderStations();
  rebuildStationSelect();
}

function rebuildFilters() {
  replaceSelectOptions($("#world-filter"), "ALL ACTIVE", state.worlds);
  replaceSelectOptions($("#type-filter"), "ALL TYPES", unique(state.symbols.map((item) => item.type)));
  replaceSelectOptions($("#candidate-world-filter"), "ALL ACTIVE", state.worlds);
  const worlds = state.worlds;
  const mapWorld = $("#map-world");
  const previous = mapWorld.value || state.graph.worldName;
  mapWorld.replaceChildren(...worlds.map((world) => new Option(world, world)));
  if (worlds.includes(previous)) {
    mapWorld.value = previous;
  } else if (worlds.length > 0) {
    mapWorld.value = worlds[0];
  }
  const stationWorld = $("#station-world");
  const previousStationWorld = stationWorld.value;
  stationWorld.replaceChildren(...worlds.map((world) => new Option(world, world)));
  if (worlds.includes(previousStationWorld)) stationWorld.value = previousStationWorld;
}

function replaceSelectOptions(select, firstLabel, values) {
  const previous = select.value;
  select.replaceChildren(new Option(firstLabel, ""), ...values.map((value) => new Option(value, value)));
  if (values.includes(previous)) select.value = previous;
}

function applyFilters() {
  state.filteredSymbols = state.symbols.filter((item) => symbolMatchesCurrentFilters(item, true));
  const networkView = $("#view-network").classList.contains("active");
  if (networkView) synchronizeMapScopeWithDecision();
  const selectionVisible = state.selectedSymbol && (networkView
    ? state.selectedSymbol.worldName === $("#map-world").value &&
      symbolMatchesCurrentFilters(state.selectedSymbol, false) &&
      mapObjectScopeIncludes($("#map-object-scope").value, state.selectedSymbol)
    : state.filteredSymbols.some((item) => item.id === state.selectedSymbol.id));
  if (state.selectedSymbol && !selectionVisible) {
    state.selectedSymbol = null;
    renderInspector();
  }
  renderSymbolTable();
  updateNetworkMapScene();
}

function synchronizeMapScopeWithDecision() {
  const decision = $("#decision-filter").value;
  const requiredScope = ({ INCLUDED: "INCLUDED", PENDING: "REVIEWABLE", IGNORED: "ALL" })[decision];
  if (requiredScope) $("#map-object-scope").value = requiredScope;
}

function symbolMatchesCurrentFilters(item, includeWorld) {
  const world = includeWorld ? $("#world-filter").value : "";
  const type = $("#type-filter").value;
  const decision = $("#decision-filter").value;
  const search = $("#search-filter").value.trim().toLowerCase();
  return (!world || item.worldName === world) &&
    (!type || item.type === type) &&
    (!decision || item.decision === decision) &&
    (!search || searchableSymbol(item).includes(search));
}

function clearFilters() {
  $("#world-filter").value = "";
  $("#type-filter").value = "";
  $("#decision-filter").value = "";
  $("#search-filter").value = "";
  $("#map-object-scope").value = "INCLUDED";
  applyFilters();
}

function renderSymbolTable() {
  $("#symbol-count").textContent = `${state.filteredSymbols.length} / ${state.symbols.length} RECORDS`;
  const body = $("#symbol-table-body");
  const table = body.closest("table");
  table.setAttribute("role", "grid");
  table.setAttribute("aria-label", "Legacy sign evidence");
  body.replaceChildren(...state.filteredSymbols.map((item, index) => {
    const row = document.createElement("tr");
    row.dataset.symbolId = item.id;
    const selected = state.selectedSymbol?.id === item.id;
    row.classList.toggle("selected", selected);
    row.setAttribute("aria-selected", String(selected));
    const stateCell = document.createElement("td");
    const pill = document.createElement("span");
    pill.className = `state-pill ${item.decision.toLowerCase()}`;
    pill.textContent = item.decision;
    stateCell.append(pill);
    const values = [item.type, item.worldName, item.x, item.y, item.z, item.lines.slice(1).filter(Boolean).join(" / "), item.id.slice(0, 8)];
    row.append(stateCell, ...values.map((value) => {
      const cell = document.createElement("td");
      cell.textContent = value;
      cell.title = String(value);
      return cell;
    }));
    row.addEventListener("click", () => selectSymbol(item));
    row.tabIndex = state.selectedSymbol?.id === item.id || (!state.selectedSymbol && index === 0) ? 0 : -1;
    row.addEventListener("keydown", (event) => handleSymbolRowKeydown(event, index));
    return row;
  }));
}

function selectSymbol(symbol) {
  state.selectedSymbol = symbol || null;
  renderInspector();
  updateSymbolTableSelection();
  state.networkMap?.setSelectedSymbol(symbol?.id || null);
  updateNetworkMapScene();
  announceSymbolSelection(symbol);
}

function announceSymbolSelection(symbol) {
  const announcement = $("#symbol-selection-announcement");
  if (!announcement) return;
  announcement.textContent = symbol
    ? `${symbol.decision} ${symbol.type} selected at ${symbol.worldName}, X ${symbol.x}, Y ${symbol.y}, Z ${symbol.z}.`
    : "Symbol selection cleared.";
}

function updateSymbolTableSelection() {
  Array.from($("#symbol-table-body").rows).forEach((row, index) => {
    const selected = row.dataset.symbolId === state.selectedSymbol?.id;
    row.classList.toggle("selected", selected);
    row.setAttribute("aria-selected", String(selected));
    row.tabIndex = selected || (!state.selectedSymbol && index === 0) ? 0 : -1;
  });
}

function handleSymbolRowKeydown(event, index) {
  const rows = Array.from($("#symbol-table-body").rows);
  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    event.preventDefault();
    const delta = event.key === "ArrowDown" ? 1 : -1;
    const next = Math.max(0, Math.min(rows.length - 1, index + delta));
    rows[index].tabIndex = -1;
    rows[next].tabIndex = 0;
    rows[next].focus();
    return;
  }
  if (event.key === "Enter" || event.key === " ") {
    event.preventDefault();
    selectSymbol(state.filteredSymbols[index]);
    requestAnimationFrame(() => {
      const selectedRow = $("#symbol-table-body").rows[index];
      if (!selectedRow) return;
      selectedRow.tabIndex = 0;
      selectedRow.focus();
    });
  }
}

function renderInspector() {
  const candidateView = $("#view-candidates").classList.contains("active");
  $(".inspector").classList.toggle(
    "has-selection",
    Boolean(candidateView ? state.selectedCandidate : state.selectedSymbol)
  );
  if (candidateView) {
    renderCandidateInspector();
    return;
  }
  const item = state.selectedSymbol;
  $("#candidate-inspector").hidden = true;
  $("#inspector-empty").hidden = Boolean(item);
  $("#symbol-inspector").hidden = !item;
  if (!item) return;
  $("#object-state").textContent = item.decision;
  $("#object-state").style.background = decisionColor(item.decision);
  $("#object-type").textContent = item.type;
  const properties = [
    ["ID", item.id],
    ["Location", `${item.worldName} ${item.x} ${item.y} ${item.z}`],
    ["Side", item.side],
    ["Role", item.assignedRole || "—"],
    ["Target", item.targetId || "—"],
    ["Version", item.version]
  ];
  const list = $("#object-properties");
  list.replaceChildren(...properties.map(([key, value]) => {
    const wrapper = document.createElement("div");
    const term = document.createElement("dt");
    const definition = document.createElement("dd");
    term.textContent = key;
    definition.textContent = value;
    wrapper.append(term, definition);
    return wrapper;
  }));
  const signLines = $("#sign-lines");
  signLines.replaceChildren(...item.lines.map((line) => {
    const element = document.createElement("li");
    element.textContent = line || " ";
    return element;
  }));
  const form = $("#decision-form");
  form.elements.decision.value = item.decision;
  form.elements.assignedRole.value = item.assignedRole || "";
  form.elements.targetId.value = item.targetId || "";
  form.elements.note.value = item.note || "";
  updateDecisionFields();
}

function renderCandidateInspector() {
  const item = state.selectedCandidate;
  $("#symbol-inspector").hidden = true;
  $("#inspector-empty").hidden = Boolean(item);
  $("#candidate-inspector").hidden = !item;
  if (!item) {
    $("#inspector-empty b").textContent = "NO CANDIDATE SELECTED";
    $("#inspector-empty span").textContent = "Select a candidate to inspect its preserved evidence.";
    return;
  }

  const decision = item.decision;
  const stateTag = $("#candidate-object-state");
  stateTag.textContent = decision.status;
  stateTag.className = `state-tag ${decision.status.toLowerCase()}`;
  $("#candidate-object-type").textContent = candidateRoleLabel(item.suggestedRole);
  $("#candidate-properties").replaceChildren(...definitionRows([
    ["Candidate", item.candidateId],
    ["Run", item.discoveryRunId],
    ["Discovered", item.discoveredAt],
    ["Type", candidateTypeLabel(item.kind)],
    ["Suggested role", candidateRoleLabel(item.suggestedRole)],
    ["Confidence", formatConfidence(item.confidence)],
    ["Location", `${item.worldName} ${item.blockX} ${item.blockY} ${item.blockZ}`],
    ["Source sign", item.sourceSignId],
    ["Sign side", item.signSide],
    ["Action type", item.actionType],
    ["Evidence revision", item.evidenceRevision],
    ["Fingerprint", item.evidenceFingerprint],
    ["Decision version", decision.version]
  ]));

  $("#candidate-sign-lines").replaceChildren(...item.rawLines.map((line) => {
    const element = document.createElement("li");
    element.textContent = line || " ";
    return element;
  }));

  const attributes = Object.entries(item.attributes || {}).sort(([left], [right]) => left.localeCompare(right));
  $("#candidate-attributes").replaceChildren(...definitionRows(
    attributes.length > 0 ? attributes : [["Attributes", "None preserved"]]
  ));

  const candidateProperties = Object.entries(item.candidateProperties || {})
    .sort(([left], [right]) => left.localeCompare(right));
  $("#candidate-properties-detail").replaceChildren(...definitionRows(
    candidateProperties.length > 0 ? candidateProperties : [["Properties", "None recorded"]]
  ));

  const evidence = item.evidence || [];
  $("#candidate-evidence").replaceChildren(...(evidence.length > 0 ? evidence.map((entry) => {
    const row = document.createElement("li");
    const heading = document.createElement("b");
    heading.textContent = candidateTypeLabel(entry.kind);
    const detail = document.createElement("span");
    const parts = [entry.evidenceText, entry.distanceBlocks == null ? null : `${Number(entry.distanceBlocks).toFixed(2)} blocks`, entry.sourceSignId]
      .filter(Boolean);
    detail.textContent = parts.join(" / ");
    row.append(heading, detail);
    return row;
  }) : [Object.assign(document.createElement("li"), { textContent: "No additional evidence was recorded." })]));

  const form = $("#candidate-decision-form");
  const stale = decision.status === "STALE";
  form.elements.decision.value = stale ? "PENDING" : decision.status;
  form.elements.targetId.value = decision.targetId || "";
  form.elements.displayName.value = decision.authorityDisplayName || "";
  form.elements.note.value = decision.note || "";
  form.elements.evidenceReviewed.checked = false;
  Array.from(form.elements).forEach((control) => { control.disabled = stale; });
  $("#save-candidate-decision").disabled = stale;
  if (stale) {
    $("#candidate-authority-fields").disabled = true;
    $("#candidate-review-confirmation").hidden = true;
  }
  $("#candidate-decision-message").textContent = stale
    ? decision.staleReason || "This decision is stale. Run discovery again and review the new candidate."
    : "";
  $("#candidate-decision-message").classList.remove("success");
  updateCandidateDecisionFields();
  renderCandidateAuthorityPreview();
}

function definitionRows(entries) {
  return entries.map(([key, value]) => {
    const wrapper = document.createElement("div");
    const term = document.createElement("dt");
    const definition = document.createElement("dd");
    term.textContent = key;
    definition.textContent = value == null || value === "" ? "—" : String(value);
    wrapper.append(term, definition);
    return wrapper;
  });
}

function updateCandidateDecisionFields() {
  const form = $("#candidate-decision-form");
  if (!state.selectedCandidate || state.selectedCandidate.decision.status === "STALE") return;
  const included = form.elements.decision.value === "INCLUDED";
  $("#candidate-authority-fields").disabled = !included;
  $("#candidate-review-confirmation").hidden = !included;
  form.elements.evidenceReviewed.required = included;
  if (!included) form.elements.evidenceReviewed.checked = false;
  renderCandidateAuthorityPreview();
}

function renderCandidateAuthorityPreview() {
  const item = state.selectedCandidate;
  if (!item) return;
  const form = $("#candidate-decision-form");
  const decision = form.elements.decision.value;
  const rows = decision === "INCLUDED"
    ? [
      ["Record type", item.kind === "DEPARTURE" ? "DeparturePoint" : "TrackControlPoint"],
      ["Target ID", form.elements.targetId.value.trim() || "Required before inclusion"],
      ["Display name", form.elements.displayName.value.trim() || "Required before inclusion"],
      ["Authority role", candidateRoleLabel(item.suggestedRole)],
      ["Location", `${item.worldName} ${item.blockX} ${item.blockY} ${item.blockZ}`],
      ["External writes", "None — digital network record only"]
    ]
    : [["Result", decision === "IGNORED" ? "Candidate remains evidence and creates no authority record." : "No authority decision is stored."]];
  $("#candidate-authority-preview").replaceChildren(...definitionRows(rows));
}

async function saveCandidateDecision(event) {
  event.preventDefault();
  const item = state.selectedCandidate;
  if (!item || item.decision.status === "STALE") return;
  const formElement = event.currentTarget;
  const form = new FormData(formElement);
  const decision = form.get("decision");
  const message = $("#candidate-decision-message");
  message.classList.remove("success");
  if (decision === "INCLUDED" && !formElement.elements.evidenceReviewed.checked) {
    message.textContent = "Review the preserved evidence and authority preview before inclusion.";
    formElement.elements.evidenceReviewed.focus();
    return;
  }
  const authority = decision === "INCLUDED" ? {
    targetId: String(form.get("targetId") || "").trim(),
    displayName: String(form.get("displayName") || "").trim()
  } : null;
  if (decision === "INCLUDED" && (!authority.targetId || !authority.displayName)) {
    message.textContent = "Target ID and display name are required for inclusion.";
    return;
  }

  $("#save-candidate-decision").disabled = true;
  message.textContent = "Saving one audited candidate decision...";
  try {
    const result = await request(`/api/v1/infrastructure-candidates/${encodeURIComponent(item.candidateId)}/decision`, {
      method: "PUT",
      body: {
        expectedRunId: item.discoveryRunId,
        expectedDecisionVersion: item.decision.version,
        decision,
        note: String(form.get("note") || "").trim() || null,
        authority
      }
    });
    const savedCandidate = result.candidate || result;
    state.selectedCandidate = savedCandidate;
    showToast("CANDIDATE DECISION SAVED");
    await Promise.all([
      refreshOverview(),
      refreshCandidates({ preserveSelection: false })
    ]);
    state.selectedCandidate = state.candidates.find((candidate) =>
      candidate.candidateId === savedCandidate.candidateId
    ) || savedCandidate;
    renderCandidateTable();
    renderCandidateInspector();
    message.textContent = result.authorityChanged
      ? "Decision saved. The linked digital authority record was updated."
      : "Decision saved. No authority record was created.";
    message.classList.add("success");
  } catch (error) {
    message.textContent = error.status === 409
      ? `${error.message} Reload this candidate before deciding again.`
      : `${error.message} Correct the decision and try again.`;
    showToast(error.message, true);
  } finally {
    $("#save-candidate-decision").disabled = state.selectedCandidate?.decision.status === "STALE";
  }
}

async function showCandidateSourceOnMap() {
  const item = state.selectedCandidate;
  if (!item) return;
  const source = state.symbols.find((symbol) => symbol.id === item.sourceSignId);
  if (!source) {
    $("#candidate-decision-message").textContent = "The source sign is not present in the current active-world symbol register.";
    return;
  }
  $("#world-filter").value = source.worldName;
  $("#type-filter").value = "";
  $("#decision-filter").value = "";
  $("#search-filter").value = "";
  $("#map-object-scope").value = source.decision === "IGNORED"
    ? "ALL"
    : source.decision === "PENDING" ? "REVIEWABLE" : "INCLUDED";
  applyFilters();
  selectSymbol(source);
  if ($("#map-world").value !== source.worldName) {
    $("#map-world").value = source.worldName;
    await loadMap();
  }
  activateView("network");
  setTimeout(() => state.networkMap?.focusSymbol(source.id), 0);
}

function updateDecisionFields() {
  const form = $("#decision-form");
  const included = form.elements.decision.value === "INCLUDED";
  form.elements.assignedRole.disabled = !included;
  form.elements.targetId.disabled = !included;
}

async function saveDecision(event) {
  event.preventDefault();
  if (!state.selectedSymbol) return;
  const form = new FormData(event.currentTarget);
  const decision = form.get("decision");
  const assignedRole = decision === "INCLUDED" ? form.get("assignedRole") || null : null;
  if (decision === "INCLUDED" && !assignedRole) {
    showToast("SELECT AN ASSIGNED ROLE", true);
    return;
  }
  try {
    await request(`/api/v1/symbols/${state.selectedSymbol.id}/decision`, {
      method: "PUT",
      body: {
        decision,
        assignedRole,
        targetId: decision === "INCLUDED" ? form.get("targetId") || null : null,
        note: form.get("note") || null,
        expectedVersion: state.selectedSymbol.version
      }
    });
    showToast("SYMBOL DECISION SAVED");
    await Promise.all([refreshSymbols(), refreshOverview()]);
  } catch (error) {
    showToast(error.message, true);
    if (error.status === 409) await refreshSymbols();
  }
}

function prefillPlatformForm() {
  if (!state.selectedSymbol) return;
  const item = state.selectedSymbol;
  const form = $("#platform-form");
  form.elements.x.value = item.x;
  form.elements.y.value = item.y;
  form.elements.z.value = item.z;
  activateView("stations");
  form.elements.id.focus();
}

async function createStation(event) {
  event.preventDefault();
  const formElement = event.currentTarget;
  const form = new FormData(formElement);
  try {
    await request("/api/v1/stations", {
      method: "POST",
      body: Object.fromEntries(form.entries())
    });
    formElement.reset();
    showToast("STATION RECORD CREATED");
    await Promise.all([refreshStations(), refreshOverview()]);
  } catch (error) {
    showToast(error.message, true);
  }
}

async function createPlatform(event) {
  event.preventDefault();
  const formElement = event.currentTarget;
  const form = new FormData(formElement);
  const stationId = form.get("stationId");
  try {
    await request(`/api/v1/stations/${encodeURIComponent(stationId)}/platforms`, {
      method: "POST",
      body: {
        id: form.get("id"),
        displayName: form.get("displayName"),
        x: Number(form.get("x")),
        y: Number(form.get("y")),
        z: Number(form.get("z")),
        berthTolerance: Number(form.get("berthTolerance"))
      }
    });
    formElement.reset();
    formElement.elements.berthTolerance.value = "1.5";
    showToast("PLATFORM RECORD CREATED");
    await Promise.all([refreshStations(), refreshOverview()]);
  } catch (error) {
    showToast(error.message, true);
  }
}

function renderStations() {
  const register = $("#station-register");
  if (!state.stations.length) {
    register.textContent = "No authoritative stations have been created. Select legacy symbols manually; no record is inferred automatically.";
    return;
  }
  register.replaceChildren(...state.stations.map((station) => {
    const card = document.createElement("article");
    card.className = "station-card";
    const header = document.createElement("header");
    const id = document.createElement("b");
    const name = document.createElement("strong");
    const world = document.createElement("span");
    id.textContent = station.id;
    name.textContent = station.displayName;
    world.textContent = `${station.worldName} / V${station.version}`;
    header.append(id, name, world);
    const table = document.createElement("table");
    const body = document.createElement("tbody");
    if (!station.platforms.length) {
      const row = body.insertRow();
      const cell = row.insertCell();
      cell.colSpan = 4;
      cell.textContent = "NO PLATFORMS";
    } else {
      station.platforms.forEach((platform) => {
        const row = body.insertRow();
        [platform.id, platform.displayName, `${platform.x} ${platform.y} ${platform.z}`, `±${platform.berthTolerance}`]
          .forEach((value) => { const cell = row.insertCell(); cell.textContent = value; });
      });
    }
    table.append(body);
    card.append(header, table);
    return card;
  }));
}

function rebuildStationSelect() {
  const select = $("#platform-form select[name=stationId]");
  const previous = select.value;
  select.replaceChildren(new Option("SELECT STATION", ""), ...state.stations.map((station) =>
    new Option(`${station.id} — ${station.displayName}`, station.id)));
  if (state.stations.some((station) => station.id === previous)) select.value = previous;
  rebuildCatalogSelects();
}

async function refreshLines() {
  const [lineResponse, routeResponse] = await Promise.all([
    request("/api/v1/lines"), request("/api/v1/route-plans")
  ]);
  state.lines = lineResponse.items;
  state.routePlans = routeResponse.items;
  renderLines();
  renderRoutePlans();
  rebuildCatalogSelects();
}

function renderLines() {
  const register = $("#line-register");
  if (!state.lines.length) {
    register.textContent = "NO LINE RECORDS. CREATE THE PUBLIC LINE IDENTITY BEFORE ROUTE ENGINEERING.";
    return;
  }
  register.replaceChildren(...state.lines.map((line) => {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "record-row";
    const swatch = document.createElement("i");
    swatch.style.background = rgbToHex(line.colorRgb);
    const id = document.createElement("b"); id.textContent = line.id;
    const name = document.createElement("span"); name.textContent = line.displayName;
    const status = document.createElement("em"); status.textContent = `${line.enabled ? "ENABLED" : "DISABLED"} / V${line.version}`;
    row.append(swatch, id, name, status);
    row.addEventListener("click", () => editLine(line));
    return row;
  }));
}

function editLine(line) {
  const form = $("#line-form");
  form.elements.id.value = line.id;
  form.elements.id.readOnly = true;
  form.elements.displayName.value = line.displayName;
  form.elements.color.value = rgbToHex(line.colorRgb);
  form.elements.enabled.checked = line.enabled;
  form.elements.expectedVersion.value = line.version;
}

function renderRoutePlans() {
  renderRecordRegister("#route-plan-register", state.routePlans, (route) => ({
    id: route.id,
    label: `${route.lineId} / ${route.directionName} / ${route.calls.length} CALLS`,
    status: `${route.trackAssignmentState} / V${route.version}`,
    color: route.trackAssignmentState === "COMPLETE" ? "#5ee06e" : "#ffb31a",
    click: () => editRoutePlan(route)
  }), "NO ROUTE PLANS. ROUTES ARE NEVER INFERRED FROM SIGNS.");
}

function editRoutePlan(route) {
  const form = $("#route-plan-form");
  form.elements.id.value = route.id;
  form.elements.id.readOnly = true;
  form.elements.lineId.value = route.lineId;
  form.elements.displayName.value = route.displayName;
  form.elements.directionName.value = route.directionName;
  form.elements.destinationName.value = route.destinationName || "";
  form.elements.circular.checked = route.circular;
  form.elements.anchorStationId.value = route.anchorStationId || "";
  form.elements.terminalPolicy.value = route.terminalPolicy;
  form.elements.expectedVersion.value = route.version;
  state.routeDraftCalls = route.calls.map(call => ({
    stationId: call.stationId,
    platformId: call.platformId || null,
    stopPolicy: call.stopPolicy,
    dwellSeconds: call.dwellSeconds
  }));
  renderRouteCalls();
}

function resetRoutePlanForm() {
  const form = $("#route-plan-form");
  form.reset();
  form.elements.id.readOnly = false;
  form.elements.expectedVersion.value = "0";
  state.routeDraftCalls = [];
  renderRouteCalls();
  form.elements.id.focus();
}

function rebuildCallPlatformSelect() {
  const station = state.stations.find(item => item.id === $("#call-station").value);
  const select = $("#call-platform");
  select.replaceChildren(new Option("ANY / UNASSIGNED", ""), ...(station?.platforms || []).map(platform =>
    new Option(`${platform.id} — ${platform.displayName}`, platform.id)));
}

function addRouteCall() {
  const stationId = $("#call-station").value;
  if (!stationId) {
    showToast("SELECT A STATION FOR THE CALL", true);
    return;
  }
  state.routeDraftCalls.push({
    stationId,
    platformId: $("#call-platform").value || null,
    stopPolicy: $("#call-policy").value,
    dwellSeconds: Number($("#call-dwell").value)
  });
  renderRouteCalls();
}

function renderRouteCalls() {
  const body = $("#route-call-body");
  body.replaceChildren(...state.routeDraftCalls.map((call, index) => {
    const row = document.createElement("tr");
    const values = [index + 1, call.stationId, call.platformId || "—", call.stopPolicy, call.dwellSeconds];
    row.append(...values.map(value => { const cell = document.createElement("td"); cell.textContent = value; return cell; }));
    const actions = row.insertCell();
    [["↑", -1], ["↓", 1], ["×", 0]].forEach(([label, movement]) => {
      const button = document.createElement("button");
      button.type = "button"; button.className = "micro-button"; button.textContent = label;
      button.disabled = movement < 0 ? index === 0 : movement > 0 ? index === state.routeDraftCalls.length - 1 : false;
      button.addEventListener("click", () => {
        if (movement === 0) state.routeDraftCalls.splice(index, 1);
        else [state.routeDraftCalls[index], state.routeDraftCalls[index + movement]] = [state.routeDraftCalls[index + movement], state.routeDraftCalls[index]];
        renderRouteCalls();
      });
      actions.append(button);
    });
    return row;
  }));
}

async function saveRoutePlan(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const version = Number(form.elements.expectedVersion.value);
  const id = form.elements.id.value;
  try {
    await request(version > 0 ? `/api/v1/route-plans/${encodeURIComponent(id)}` : "/api/v1/route-plans", {
      method: version > 0 ? "PUT" : "POST",
      body: {
        id,
        lineId: form.elements.lineId.value,
        displayName: form.elements.displayName.value,
        directionName: form.elements.directionName.value,
        destinationName: form.elements.destinationName.value || null,
        circular: form.elements.circular.checked,
        anchorStationId: form.elements.anchorStationId.value || null,
        terminalPolicy: form.elements.terminalPolicy.value,
        calls: state.routeDraftCalls,
        expectedVersion: version
      }
    });
    showToast(version > 0 ? "ROUTE PLAN UPDATED" : "ROUTE PLAN CREATED");
    resetRoutePlanForm();
    await refreshLines();
  } catch (error) {
    showToast(error.message, true);
    if (error.status === 409) await refreshLines();
  }
}

function resetLineForm() {
  const form = $("#line-form");
  form.reset();
  form.elements.id.readOnly = false;
  form.elements.expectedVersion.value = "0";
  form.elements.color.value = "#1687a7";
  form.elements.enabled.checked = true;
  form.elements.id.focus();
}

async function saveLine(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const version = Number(form.elements.expectedVersion.value);
  const id = form.elements.id.value;
  try {
    await request(version > 0 ? `/api/v1/lines/${encodeURIComponent(id)}` : "/api/v1/lines", {
      method: version > 0 ? "PUT" : "POST",
      body: {
        id,
        displayName: form.elements.displayName.value,
        colorRgb: hexToRgb(form.elements.color.value),
        enabled: form.elements.enabled.checked,
        expectedVersion: version
      }
    });
    showToast(version > 0 ? "LINE RECORD UPDATED" : "LINE RECORD CREATED");
    resetLineForm();
    await Promise.all([refreshLines(), refreshOverview()]);
  } catch (error) {
    showToast(error.message, true);
    if (error.status === 409) await refreshLines();
  }
}

async function refreshFares() {
  const [gates, zones, rules] = await Promise.all([
    request("/api/v1/gates"),
    request("/api/v1/fare-zones"),
    request("/api/v1/fare-rules")
  ]);
  state.gates = gates.items;
  state.zones = zones.items;
  state.fareRules = rules.items;
  renderFareCatalog();
  rebuildCatalogSelects();
}

function renderFareCatalog() {
  renderRecordRegister("#gate-register", state.gates, (gate) => ({
    id: gate.id,
    label: `${gate.stationId} / ${gate.gateMode} / ${gate.x} ${gate.y} ${gate.z}`,
    status: `${gate.enabled ? "ENABLED" : "DISABLED"} / V${gate.version}`,
    color: gate.enabled ? "#5ee06e" : "#737b7b",
    click: () => editGate(gate)
  }), "NO GATE RECORDS");
  renderRecordRegister("#zone-register", state.zones, (zone) => ({
    id: zone.id,
    label: zone.displayName,
    status: `V${zone.version}`,
    color: rgbToHex(zone.colorRgb),
    click: () => editZone(zone)
  }), "NO FARE ZONES");
  renderRecordRegister("#fare-rule-register", state.fareRules, (rule) => ({
    id: rule.id,
    label: `${rule.ruleType} / ${formatMoney(rule.amountMinor, rule.currencyCode)}`,
    status: `P${rule.priority} / V${rule.version}`,
    color: rule.enabled ? "#ffb31a" : "#737b7b",
    click: () => editFareRule(rule)
  }), "NO FARE RULES");
}

function renderRecordRegister(selector, records, mapper, emptyText) {
  const register = $(selector);
  if (!records.length) {
    register.textContent = emptyText;
    return;
  }
  register.replaceChildren(...records.map((record) => {
    const item = mapper(record);
    const row = document.createElement("button");
    row.type = "button";
    row.className = "record-row";
    if (!item.click) row.disabled = true;
    const swatch = document.createElement("i"); swatch.style.background = item.color;
    const id = document.createElement("b"); id.textContent = item.id;
    const label = document.createElement("span"); label.textContent = item.label;
    const status = document.createElement("em"); status.textContent = item.status;
    row.append(swatch, id, label, status);
    if (item.click) row.addEventListener("click", item.click);
    return row;
  }));
}

function editGate(gate) {
  const form = $("#gate-form");
  form.elements.id.value = gate.id;
  form.elements.id.readOnly = true;
  form.elements.displayName.value = gate.displayName;
  form.elements.stationId.value = gate.stationId;
  form.elements.x.value = gate.x;
  form.elements.y.value = gate.y;
  form.elements.z.value = gate.z;
  form.elements.gateMode.value = gate.gateMode;
  form.elements.authorizationTimeoutSeconds.value = gate.authorizationTimeoutSeconds;
  form.elements.sensorDefinitionJson.value = gate.sensorDefinitionJson;
  form.elements.outputDefinitionJson.value = gate.outputDefinitionJson;
  form.elements.enabled.checked = gate.enabled;
  form.elements.expectedVersion.value = gate.version;
}

function resetGateForm() {
  const form = $("#gate-form");
  form.reset();
  form.elements.id.readOnly = false;
  form.elements.expectedVersion.value = "0";
  form.elements.authorizationTimeoutSeconds.value = "10";
  form.elements.sensorDefinitionJson.value = "{}";
  form.elements.outputDefinitionJson.value = "{}";
  form.elements.enabled.checked = true;
  form.elements.id.focus();
}

async function saveGate(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const version = Number(form.elements.expectedVersion.value);
  const id = form.elements.id.value;
  try {
    await request(version > 0 ? `/api/v1/gates/${encodeURIComponent(id)}` : "/api/v1/gates", {
      method: version > 0 ? "PUT" : "POST",
      body: {
        id,
        displayName: form.elements.displayName.value,
        stationId: form.elements.stationId.value,
        x: Number(form.elements.x.value), y: Number(form.elements.y.value), z: Number(form.elements.z.value),
        gateMode: form.elements.gateMode.value,
        sensorDefinitionJson: form.elements.sensorDefinitionJson.value,
        outputDefinitionJson: form.elements.outputDefinitionJson.value,
        authorizationTimeoutSeconds: Number(form.elements.authorizationTimeoutSeconds.value),
        enabled: form.elements.enabled.checked,
        expectedVersion: version
      }
    });
    showToast(version > 0 ? "FARE GATE UPDATED" : "FARE GATE CREATED");
    resetGateForm();
    await Promise.all([refreshFares(), refreshOverview()]);
  } catch (error) {
    showToast(error.message, true);
    if (error.status === 409) await refreshFares();
  }
}

function editZone(zone) {
  const form = $("#zone-form");
  form.elements.id.value = zone.id;
  form.elements.id.readOnly = true;
  form.elements.displayName.value = zone.displayName;
  form.elements.color.value = rgbToHex(zone.colorRgb);
  form.elements.expectedVersion.value = zone.version;
}

function resetZoneForm() {
  const form = $("#zone-form");
  form.reset();
  form.elements.id.readOnly = false;
  form.elements.expectedVersion.value = "0";
  form.elements.color.value = "#808080";
  form.elements.id.focus();
}

async function saveZone(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const version = Number(form.elements.expectedVersion.value);
  const id = form.elements.id.value;
  try {
    await request(version > 0 ? `/api/v1/fare-zones/${encodeURIComponent(id)}` : "/api/v1/fare-zones", {
      method: version > 0 ? "PUT" : "POST",
      body: {
        id,
        displayName: form.elements.displayName.value,
        colorRgb: hexToRgb(form.elements.color.value),
        expectedVersion: version
      }
    });
    resetZoneForm();
    showToast(version > 0 ? "FARE ZONE UPDATED" : "FARE ZONE CREATED");
    await refreshFares();
  } catch (error) {
    showToast(error.message, true);
    if (error.status === 409) await refreshFares();
  }
}

function editFareRule(rule) {
  const form = $("#fare-rule-form");
  form.elements.id.value = rule.id;
  form.elements.id.readOnly = true;
  form.elements.displayName.value = rule.displayName;
  form.elements.ruleType.value = rule.ruleType;
  form.elements.originStationId.value = rule.originStationId || "";
  form.elements.destinationStationId.value = rule.destinationStationId || "";
  form.elements.originZoneId.value = rule.originZoneId || "";
  form.elements.destinationZoneId.value = rule.destinationZoneId || "";
  form.elements.lineId.value = rule.lineId || "";
  form.elements.amountMinor.value = rule.amountMinor;
  form.elements.currencyCode.value = rule.currencyCode;
  form.elements.priority.value = rule.priority;
  form.elements.bidirectional.checked = rule.bidirectional;
  form.elements.enabled.checked = rule.enabled;
  form.elements.expectedVersion.value = rule.version;
}

function resetFareRuleForm() {
  const form = $("#fare-rule-form");
  form.reset();
  form.elements.id.readOnly = false;
  form.elements.expectedVersion.value = "0";
  form.elements.currencyCode.value = "HKD";
  form.elements.bidirectional.checked = true;
  form.elements.enabled.checked = true;
  form.elements.id.focus();
}

async function saveFareRule(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const version = Number(form.elements.expectedVersion.value);
  const id = form.elements.id.value;
  try {
    await request(version > 0 ? `/api/v1/fare-rules/${encodeURIComponent(id)}` : "/api/v1/fare-rules", {
      method: version > 0 ? "PUT" : "POST",
      body: {
        id,
        displayName: form.elements.displayName.value,
        ruleType: form.elements.ruleType.value,
        originStationId: form.elements.originStationId.value || null,
        destinationStationId: form.elements.destinationStationId.value || null,
        originZoneId: form.elements.originZoneId.value || null,
        destinationZoneId: form.elements.destinationZoneId.value || null,
        lineId: form.elements.lineId.value || null,
        amountMinor: Number(form.elements.amountMinor.value),
        currencyCode: form.elements.currencyCode.value,
        priority: Number(form.elements.priority.value),
        bidirectional: form.elements.bidirectional.checked,
        enabled: form.elements.enabled.checked,
        expectedVersion: version
      }
    });
    resetFareRuleForm();
    showToast(version > 0 ? "FARE RULE UPDATED" : "FARE RULE CREATED");
    await Promise.all([refreshFares(), refreshOverview()]);
  } catch (error) {
    showToast(error.message, true);
    if (error.status === 409) await refreshFares();
  }
}

function rebuildCatalogSelects() {
  replaceOptionsPreserving("#gate-form select[name=stationId]", "SELECT STATION", state.stations, item => `${item.id} — ${item.displayName}`);
  replaceOptionsPreserving("#fare-rule-form select[name=originStationId]", "ANY / NOT USED", state.stations, item => `${item.id} — ${item.displayName}`);
  replaceOptionsPreserving("#fare-rule-form select[name=destinationStationId]", "ANY / NOT USED", state.stations, item => `${item.id} — ${item.displayName}`);
  replaceOptionsPreserving("#fare-rule-form select[name=originZoneId]", "ANY / NOT USED", state.zones, item => `${item.id} — ${item.displayName}`);
  replaceOptionsPreserving("#fare-rule-form select[name=destinationZoneId]", "ANY / NOT USED", state.zones, item => `${item.id} — ${item.displayName}`);
  replaceOptionsPreserving("#fare-rule-form select[name=lineId]", "ANY LINE", state.lines, item => `${item.id} — ${item.displayName}`);
  replaceOptionsPreserving("#route-plan-form select[name=lineId]", "SELECT LINE", state.lines, item => `${item.id} — ${item.displayName}`);
  replaceOptionsPreserving("#route-plan-form select[name=anchorStationId]", "NOT CIRCULAR", state.stations, item => `${item.id} — ${item.displayName}`);
  replaceOptionsPreserving("#call-station", "SELECT STATION", state.stations, item => `${item.id} — ${item.displayName}`);
  rebuildCallPlatformSelect();
}

function replaceOptionsPreserving(selector, firstLabel, items, labeler) {
  const select = $(selector);
  const previous = select.value;
  select.replaceChildren(new Option(firstLabel, ""), ...items.map(item => new Option(labeler(item), item.id)));
  if (items.some(item => item.id === previous)) select.value = previous;
}

async function refreshAudit() {
  try {
    const response = await request("/api/v1/audit?limit=200");
    const body = $("#audit-table-body");
    body.replaceChildren(...response.items.map((record) => {
      const row = document.createElement("tr");
      const values = [record.createdAt, record.action, record.targetType, record.targetId || "—", `${record.actorType}:${record.actorId}`];
      row.append(...values.map(value => { const cell = document.createElement("td"); cell.textContent = value; return cell; }));
      row.title = `Before: ${record.beforeJson || "—"}\nAfter: ${record.afterJson || "—"}`;
      return row;
    }));
  } catch (error) { showToast(error.message, true); }
}

async function loadMap() {
  const world = $("#map-world").value;
  const loadSequence = ++state.mapLoadSequence;
  if (!state.networkMap) return;
  if (state.selectedSymbol && state.selectedSymbol.worldName !== world) {
    state.selectedSymbol = null;
    renderInspector();
    renderSymbolTable();
  }
  if (!world) {
    state.graph = emptyRailGraph("");
    updateMapEmptyState(world);
    updateNetworkMapScene(true);
    return;
  }
  state.graph = emptyRailGraph(world);
  showMapMessage("LOADING RAIL EVIDENCE", `Reading the latest saved rail snapshot for ${world}.`);
  updateNetworkMapScene(true);
  try {
    const graph = await request(`/api/v1/network/rails?world=${encodeURIComponent(world)}`);
    if (loadSequence !== state.mapLoadSequence || $("#map-world").value !== world) return;
    const overviewRunId = state.overview?.railRun?.runId || null;
    const graphRunId = graph.runId || null;
    if (overviewRunId !== graphRunId) await refreshOverview();
    if (loadSequence !== state.mapLoadSequence || $("#map-world").value !== world) return;
    state.graph = graph;
    updateMapEmptyState(world);
    updateNetworkMapScene(true);
    fitMap();
    if (state.selectedSymbol?.worldName === world) {
      state.networkMap?.focusSymbol(state.selectedSymbol.id);
    }
  } catch (error) {
    if (loadSequence !== state.mapLoadSequence || $("#map-world").value !== world) return;
    state.graph = emptyRailGraph(world);
    state.selectedSymbol = null;
    renderInspector();
    renderSymbolTable();
    showMapMessage("RAIL MAP UNAVAILABLE", `${error.message} Use Reload to try again.`);
    updateNetworkMapScene(true);
    showToast(error.message, true);
  }
}

function emptyRailGraph(world) {
  return { nodes: [], edges: [], materials: [], shapes: [], runId: null, worldName: world, truncated: false };
}

function showMapMessage(titleText, messageText) {
  const empty = $("#map-empty");
  empty.hidden = false;
  const title = document.createElement("b");
  const message = document.createElement("span");
  title.textContent = titleText;
  message.textContent = messageText;
  empty.replaceChildren(title, message);
}

function updateMapEmptyState(world) {
  if (!state.networkMap) return;
  if (state.graph.runId && state.graph.nodes.length > 0) {
    $("#map-empty").hidden = true;
    return;
  }
  if (state.graph.runId) {
    showMapMessage(
      "NO RAIL EVIDENCE IN THIS WORLD",
      `The latest rail snapshot contains no saved rail nodes for ${world}.`
    );
  } else {
    showMapMessage(
      "NO RAIL CANDIDATE SNAPSHOT",
      "Run /atcs discover rail to inventory existing rail evidence without generating terrain."
    );
  }
}

function fitMap() {
  state.networkMap?.fit();
}

function resizeCanvas() {
  state.networkMap?.resize();
}

function updateNetworkMapScene(graphChanged = false) {
  const world = $("#map-world").value;
  const worldSymbols = state.symbols.filter((item) => item.worldName === world);
  const filtered = worldSymbols.filter((item) => symbolMatchesCurrentFilters(item, false));
  const objectScope = $("#map-object-scope")?.value || "INCLUDED";
  const symbols = state.graph.nodes.length > 0
    ? filtered.filter((item) => mapObjectScopeIncludes(objectScope, item))
    : [];
  if (graphChanged) state.networkMap?.setScene(state.graph, symbols);
  else state.networkMap?.setSymbols(symbols);
  state.networkMap?.setSelectedSymbol(state.selectedSymbol?.id || null);
  const rail = state.overview?.railRun;
  const detail = [
    `WORLD ${world || "—"}`,
    state.mapViewMode,
    `RAIL NODES ${state.graph.nodes.length}`,
    `EDGES ${state.graph.edges.length}`,
    `OBJECTS ${symbols.length}/${worldSymbols.length}`,
    state.railBlocksVisible ? "RAIL BLOCKS ON" : "RAIL BLOCKS OFF",
    state.mapGridVisible ? "GRID ON" : "GRID OFF"
  ];
  if (state.graph.truncated) detail.push("TRUNCATED");
  if (rail?.discoveryMode) detail.push(rail.discoveryMode);
  $("#map-coordinates").textContent = detail.join(" / ");
  updateMapSelectionBadge(symbols);
}

function mapObjectScopeIncludes(scope, item) {
  if (scope === "NONE") return false;
  if (scope === "INCLUDED") return item.decision === "INCLUDED";
  if (scope === "REVIEWABLE") return item.decision !== "IGNORED";
  return true;
}

function handleMapObjectScopeChange() {
  const scope = $("#map-object-scope").value;
  const decision = $("#decision-filter").value;
  if (decision && !mapObjectScopeIncludes(scope, { decision })) {
    $("#decision-filter").value = "";
    applyFilters();
    return;
  }
  if (state.selectedSymbol && !mapObjectScopeIncludes(scope, state.selectedSymbol)) {
    state.selectedSymbol = null;
    renderInspector();
    renderSymbolTable();
  }
  updateNetworkMapScene();
}

function toggleRailBlocks() {
  state.railBlocksVisible = !state.railBlocksVisible;
  const button = $("#toggle-rail-blocks");
  button.classList.toggle("active-control", state.railBlocksVisible);
  button.setAttribute("aria-pressed", String(state.railBlocksVisible));
  state.networkMap?.setRailBlocksVisible(state.railBlocksVisible);
  updateNetworkMapScene();
}

function toggleMapGrid() {
  state.mapGridVisible = !state.mapGridVisible;
  const button = $("#toggle-map-grid");
  button.classList.toggle("active-control", state.mapGridVisible);
  button.setAttribute("aria-pressed", String(state.mapGridVisible));
  state.networkMap?.setGridVisible(state.mapGridVisible);
  updateNetworkMapScene();
}

function updateMapSelectionBadge(visibleSymbols) {
  const selected = state.selectedSymbol;
  const visible = selected && visibleSymbols.some((item) => item.id === selected.id);
  const badge = $("#map-selection");
  badge.hidden = !visible;
  if (!visible) return;
  $("#map-selection-state").textContent = `SELECTED SIGN EVIDENCE / ${selected.decision}`;
  $("#map-selection-title").textContent = `${selected.type} / ${selected.id.slice(0, 8)}`;
  $("#map-selection-location").textContent = `${selected.worldName} / X ${selected.x} / Y ${selected.y} / Z ${selected.z}`;
}

function setMapView(top) {
  $("#view-top-map").classList.toggle("active-control", top);
  $("#view-3d-map").classList.toggle("active-control", !top);
  $("#view-top-map").setAttribute("aria-pressed", String(top));
  $("#view-3d-map").setAttribute("aria-pressed", String(!top));
  state.mapViewMode = top ? "TOP" : "3D";
  if (top) state.networkMap?.setTopView();
  else state.networkMap?.setThreeDimensionalView();
  updateNetworkMapScene();
}

function activateView(name) {
  $$(".module-strip button[data-view]").forEach((button) => button.classList.toggle("active", button.dataset.view === name));
  $$(".workspace .view").forEach((view) => view.classList.toggle("active", view.id === `view-${name}`));
  $("#candidate-filter-panel").hidden = name !== "candidates";
  $("#symbol-filter-panel").hidden = name === "candidates";
  $("#symbol-world-filter-label").hidden = name === "network";
  if (name === "network") {
    synchronizeMapScopeWithDecision();
    if (state.selectedSymbol && !mapObjectScopeIncludes($("#map-object-scope").value, state.selectedSymbol)) {
      $("#map-object-scope").value = state.selectedSymbol.decision === "IGNORED" ? "ALL" : "REVIEWABLE";
    }
    let mapPreparation = Promise.resolve();
    if (state.selectedSymbol && $("#map-world").value !== state.selectedSymbol.worldName) {
      $("#map-world").value = state.selectedSymbol.worldName;
      mapPreparation = loadMap();
    }
    mapPreparation.finally(() => setTimeout(() => {
      resizeCanvas();
      updateNetworkMapScene();
      if (state.selectedSymbol) state.networkMap?.focusSymbol(state.selectedSymbol.id);
    }, 0));
  }
  if (name === "audit") refreshAudit();
  if (name !== "candidates" && !state.selectedSymbol) {
    $("#inspector-empty b").textContent = "NO SYMBOL SELECTED";
    $("#inspector-empty span").textContent = "Select a map marker or table row.";
  }
  renderInspector();
}

async function request(path, options = {}) {
  const headers = { Accept: "application/json" };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (state.csrf && options.method && options.method !== "GET") headers["X-ATCS-CSRF"] = state.csrf;
  const response = await fetch(path, {
    method: options.method || "GET",
    headers,
    credentials: "same-origin",
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined
  });
  const text = await response.text();
  let payload = {};
  if (text) {
    try { payload = JSON.parse(text); } catch { payload = { message: text }; }
  }
  if (!response.ok) {
    const error = new Error(payload.message || `HTTP ${response.status}`);
    error.status = response.status;
    if (response.status === 401 && !options.suppressAuthenticationPrompt) showLogin("SESSION EXPIRED");
    throw error;
  }
  return payload;
}

function updateClock() {
  const now = new Date();
  const time = now.toISOString().slice(11, 19);
  $("#utc-clock").textContent = time;
  $("#footer-time").textContent = now.toISOString().replace("T", " ").slice(0, 19) + "Z";
}

function showToast(message, failure = false) {
  const toast = $("#toast");
  toast.textContent = message ? String(message).toUpperCase() : "";
  toast.classList.toggle("failure", failure);
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => {
    toast.textContent = "";
    toast.classList.remove("failure");
  }, 5000);
}

function searchableSymbol(item) {
  return [item.id, item.worldName, item.type, item.x, item.y, item.z, ...item.lines, item.assignedRole || "", item.targetId || ""].join(" ").toLowerCase();
}
function candidateTypeLabel(value) {
  return ({ DEPARTURE: "DEPARTURE POINT", TRACK_CONTROL: "TRACK CONTROL" })[value]
    || String(value || "UNKNOWN").replaceAll("_", " ");
}
function candidateRoleLabel(value) { return String(value || "UNCLASSIFIED").replaceAll("_", " "); }
function formatConfidence(value) { return `${Math.round(Number(value || 0) * 100)}%`; }
function unique(values) { return [...new Set(values)].sort((a, b) => a.localeCompare(b)); }
function rgbToHex(value) { return `#${Number(value).toString(16).padStart(6, "0")}`; }
function hexToRgb(value) { return Number.parseInt(String(value).replace("#", ""), 16); }
function formatMoney(amountMinor, currency) { return `${currency} ${(Number(amountMinor) / 100).toFixed(2)}`; }
function decisionColor(decision) { return ({ INCLUDED: "#5ee06e", IGNORED: "#8e9997", PENDING: "#ffb31a" })[decision] || "#ffb31a"; }
