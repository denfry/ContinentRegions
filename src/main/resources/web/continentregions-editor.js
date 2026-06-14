/*
 * ContinentRegions — BlueMap web editor addon (v2).
 *
 * Activates only when an editor token is present (from the /continent editor
 * link, cached in sessionStorage). Clicking the map in Draw mode adds outline
 * points; in Move mode it repositions the selected vertex. BlueMap opens its
 * block popup at `bluemap.popupMarker`, whose `.position` holds the clicked world
 * coordinate — we hijack `open` to read it.
 *
 * v2 additions: full undo/redo history, an editable vertex list (edit/move/
 * insert/delete/reorder), a flag editor with config presets, client-side RDP
 * simplification, a per-continent visibility toggle and server-side rollback.
 */
(function () {
    "use strict";

    var PORT = "__CR_PORT__"; // replaced by the plugin when the asset is deployed
    var DISPLAY_Y = 80;
    var MAX_HISTORY = 200;

    // --- token / activation ------------------------------------------------

    function readTokenFromUrl() {
        var hash = window.location.hash || "";
        var qi = hash.indexOf("?");
        if (qi >= 0) {
            var t = new URLSearchParams(hash.substring(qi + 1)).get("token");
            if (t) return t;
        }
        return new URLSearchParams(window.location.search).get("token");
    }

    var token = readTokenFromUrl();
    if (token) {
        try { sessionStorage.setItem("cr_token", token); } catch (e) { /* ignore */ }
    } else {
        try { token = sessionStorage.getItem("cr_token"); } catch (e) { /* ignore */ }
    }
    if (!token) {
        return; // not an editor session — stay out of the way
    }

    function apiBase() {
        return window.location.protocol + "//" + window.location.hostname + ":" + PORT + "/api/v1";
    }

    // --- REST helpers ------------------------------------------------------

    function request(method, path, body) {
        var opts = { method: method, headers: {} };
        if (body !== undefined) {
            opts.headers["Content-Type"] = "application/json";
            opts.body = JSON.stringify(body);
        }
        if (method !== "GET") {
            opts.headers["Authorization"] = "Bearer " + token;
        }
        return fetch(apiBase() + path, opts).then(function (res) {
            return res.text().then(function (text) {
                var data = null;
                try { data = text ? JSON.parse(text) : null; } catch (e) { /* non-json */ }
                if (!res.ok) {
                    throw new Error((data && data.error) || ("HTTP " + res.status));
                }
                return data;
            });
        });
    }

    // --- editor state ------------------------------------------------------

    var bluemap = null;
    var clickMode = null;       // null | "draw" | "move"
    var pendingMoveIndex = -1;  // vertex index awaiting a map click in Move mode
    var points = [];            // [{x, z}]
    var loadedFlags = {};       // editable flag map for the current continent
    var hiddenFlag = false;     // BlueMap visibility toggle
    var knownIds = {};          // id -> true, to choose POST vs PUT
    var presets = {};           // name -> {flag: value}
    var previewMarker = null;

    var undoStack = [];
    var redoStack = [];

    var ui = {};

    // --- DOM helpers -------------------------------------------------------

    function el(tag, attrs, parent) {
        var e = document.createElement(tag);
        if (attrs) {
            for (var k in attrs) {
                if (k === "text") { e.textContent = attrs[k]; }
                else { e.setAttribute(k, attrs[k]); }
            }
        }
        if (parent) { parent.appendChild(e); }
        return e;
    }

    function buildPanel() {
        var toggle = el("button", { id: "cr-toggle", text: "🌍 Continents" }, document.body);
        var panel = el("div", { id: "cr-panel" }, document.body);
        toggle.addEventListener("click", function () { panel.classList.toggle("cr-hidden"); });

        el("h2", { text: "Continent Editor" }, panel);

        el("label", { text: "World" }, panel);
        ui.world = el("select", {}, panel);

        el("label", { text: "Continent" }, panel);
        ui.continent = el("select", {}, panel);
        ui.continent.addEventListener("change", onSelectContinent);

        el("label", { text: "ID" }, panel);
        ui.id = el("input", { type: "text", placeholder: "europe" }, panel);

        el("label", { text: "Name" }, panel);
        ui.name = el("input", { type: "text", placeholder: "Europe" }, panel);

        var row1 = el("div", { class: "cr-row" }, panel);
        var c1 = el("div", {}, row1);
        el("label", { text: "Color" }, c1);
        ui.color = el("input", { type: "color", value: "#3b82f6" }, c1);
        var c2 = el("div", {}, row1);
        el("label", { text: "Priority" }, c2);
        ui.priority = el("input", { type: "number", value: "10" }, c2);

        var row2 = el("div", { class: "cr-row" }, panel);
        var c3 = el("div", {}, row2);
        el("label", { text: "Min Y" }, c3);
        ui.minY = el("input", { type: "number", value: "-64" }, c3);
        var c4 = el("div", {}, row2);
        el("label", { text: "Max Y" }, c4);
        ui.maxY = el("input", { type: "number", value: "320" }, c4);

        var hiddenRow = el("label", { class: "cr-check" }, panel);
        ui.hidden = el("input", { type: "checkbox" }, hiddenRow);
        el("span", { text: " Hidden on map (keeps the WorldGuard region)" }, hiddenRow);
        ui.hidden.addEventListener("change", function () { hiddenFlag = ui.hidden.checked; });

        // Drawing / editing controls
        var buttons = el("div", { class: "cr-buttons" }, panel);
        ui.draw = el("button", { class: "cr-btn", text: "Draw" }, buttons);
        ui.undo = el("button", { class: "cr-btn", text: "↶ Undo" }, buttons);
        ui.redo = el("button", { class: "cr-btn", text: "↷ Redo" }, buttons);
        ui.clear = el("button", { class: "cr-btn", text: "Clear" }, buttons);
        ui.simplify = el("button", { class: "cr-btn", text: "Simplify" }, buttons);
        ui.preview = el("button", { class: "cr-btn", text: "Preview" }, buttons);

        // Vertex list
        ui.points = el("div", { id: "cr-points", text: "Points: 0" }, panel);
        ui.vertices = el("div", { id: "cr-vertices" }, panel);

        // Flags editor
        el("label", { text: "Flags" }, panel);
        ui.flags = el("div", { id: "cr-flags" }, panel);
        var flagBtns = el("div", { class: "cr-buttons" }, panel);
        ui.addFlag = el("button", { class: "cr-btn", text: "+ Flag" }, flagBtns);
        ui.presetSelect = el("select", { id: "cr-preset" }, flagBtns);
        ui.applyPreset = el("button", { class: "cr-btn", text: "Apply preset" }, flagBtns);

        // Persistence controls
        var actions = el("div", { class: "cr-buttons" }, panel);
        ui.save = el("button", { class: "cr-btn cr-primary", text: "Save" }, actions);
        ui.rollback = el("button", { class: "cr-btn", text: "Rollback" }, actions);
        ui.del = el("button", { class: "cr-btn cr-danger", text: "Delete" }, actions);
        ui.export = el("button", { class: "cr-btn", text: "Export JSON" }, actions);
        ui.exportAll = el("button", { class: "cr-btn", text: "Export all" }, actions);
        ui.import = el("button", { class: "cr-btn", text: "Import JSON" }, actions);
        ui.file = el("input", { id: "cr-file", type: "file", accept: ".json,application/json" }, panel);

        ui.status = el("div", { id: "cr-status" }, panel);

        ui.draw.addEventListener("click", toggleDraw);
        ui.undo.addEventListener("click", undo);
        ui.redo.addEventListener("click", redo);
        ui.clear.addEventListener("click", clearPoints);
        ui.simplify.addEventListener("click", simplifyClient);
        ui.preview.addEventListener("click", updatePreview);
        ui.addFlag.addEventListener("click", function () { addFlagRow("", ""); });
        ui.applyPreset.addEventListener("click", applyPresetLocal);
        ui.save.addEventListener("click", save);
        ui.rollback.addEventListener("click", rollback);
        ui.del.addEventListener("click", remove);
        ui.export.addEventListener("click", exportJson);
        ui.exportAll.addEventListener("click", exportAllJson);
        ui.import.addEventListener("click", function () { ui.file.click(); });
        ui.file.addEventListener("change", importJson);
    }

    function status(message, kind) {
        ui.status.textContent = message || "";
        ui.status.className = kind ? ("cr-" + kind) : "";
    }

    // --- history -----------------------------------------------------------

    function snapshotState() {
        return JSON.stringify({ points: points, flags: loadedFlags, hidden: hiddenFlag });
    }

    function restoreState(s) {
        var o = JSON.parse(s);
        points = o.points || [];
        loadedFlags = o.flags || {};
        hiddenFlag = !!o.hidden;
        ui.hidden.checked = hiddenFlag;
        renderAll();
    }

    /** Record the current state so the next mutation can be undone. */
    function pushUndo() {
        undoStack.push(snapshotState());
        if (undoStack.length > MAX_HISTORY) { undoStack.shift(); }
        redoStack = [];
        updateHistoryButtons();
    }

    function undo() {
        if (!undoStack.length) { status("Nothing to undo.", null); return; }
        redoStack.push(snapshotState());
        restoreState(undoStack.pop());
        status("Undo.", null);
    }

    function redo() {
        if (!redoStack.length) { status("Nothing to redo.", null); return; }
        undoStack.push(snapshotState());
        restoreState(redoStack.pop());
        status("Redo.", null);
    }

    function updateHistoryButtons() {
        ui.undo.disabled = undoStack.length === 0;
        ui.redo.disabled = redoStack.length === 0;
    }

    // --- rendering ---------------------------------------------------------

    function renderAll() {
        updatePointsLabel();
        renderVertices();
        renderFlags();
        updatePreview();
        updateHistoryButtons();
    }

    function updatePointsLabel() {
        var suffix = "";
        if (clickMode === "draw") { suffix = " (drawing — click the map)"; }
        else if (clickMode === "move") { suffix = " (move — click the map to place vertex #" + (pendingMoveIndex + 1) + ")"; }
        ui.points.textContent = "Points: " + points.length + suffix;
    }

    function renderVertices() {
        ui.vertices.innerHTML = "";
        points.forEach(function (p, i) {
            var row = el("div", { class: "cr-vrow" }, ui.vertices);
            el("span", { class: "cr-vidx", text: "#" + (i + 1) }, row);
            var xi = el("input", { type: "number", value: String(p.x), class: "cr-vnum" }, row);
            var zi = el("input", { type: "number", value: String(p.z), class: "cr-vnum" }, row);
            xi.addEventListener("change", function () { editVertex(i, parseInt(xi.value, 10), p.z); });
            zi.addEventListener("change", function () { editVertex(i, p.x, parseInt(zi.value, 10)); });
            mkBtn(row, "⌖", "Pick on map", function () { startMove(i); });
            mkBtn(row, "↑", "Move up", function () { reorder(i, -1); });
            mkBtn(row, "↓", "Move down", function () { reorder(i, 1); });
            mkBtn(row, "+", "Insert after", function () { insertAfter(i); });
            mkBtn(row, "✕", "Delete", function () { deleteVertex(i); });
        });
    }

    function renderFlags() {
        ui.flags.innerHTML = "";
        Object.keys(loadedFlags).forEach(function (name) {
            addFlagRow(name, loadedFlags[name], true);
        });
    }

    function mkBtn(parent, label, title, handler) {
        var b = el("button", { class: "cr-vbtn", text: label, title: title }, parent);
        b.addEventListener("click", handler);
        return b;
    }

    // --- vertex mutations (each records history) ---------------------------

    function addPoint(x, z) {
        pushUndo();
        points.push({ x: x, z: z });
        renderAll();
    }

    function editVertex(i, x, z) {
        if (isNaN(x) || isNaN(z)) { renderVertices(); return; }
        pushUndo();
        points[i] = { x: x, z: z };
        renderAll();
    }

    function deleteVertex(i) {
        pushUndo();
        points.splice(i, 1);
        renderAll();
    }

    function insertAfter(i) {
        // New vertex at the midpoint of edge i -> i+1 (wraps around).
        var a = points[i];
        var b = points[(i + 1) % points.length];
        var mid = { x: Math.round((a.x + b.x) / 2), z: Math.round((a.z + b.z) / 2) };
        pushUndo();
        points.splice(i + 1, 0, mid);
        renderAll();
    }

    function reorder(i, dir) {
        var j = i + dir;
        if (j < 0 || j >= points.length) { return; }
        pushUndo();
        var tmp = points[i];
        points[i] = points[j];
        points[j] = tmp;
        renderAll();
    }

    function clearPoints() {
        if (!points.length) { return; }
        pushUndo();
        points = [];
        renderAll();
    }

    function startMove(i) {
        clickMode = "move";
        pendingMoveIndex = i;
        ui.draw.classList.remove("cr-active");
        status("Click the map to place vertex #" + (i + 1) + ".", null);
        updatePointsLabel();
    }

    // --- client-side simplification (RDP) ----------------------------------

    function simplifyClient() {
        if (points.length <= 3) { status("Too few points to simplify.", null); return; }
        var tol = parseFloat(window.prompt("Simplify tolerance (blocks):", "5"));
        if (isNaN(tol) || tol <= 0) { return; }
        var simplified = rdpClosed(points, tol);
        if (simplified.length < 3) { status("Tolerance too high — would collapse the shape.", "error"); return; }
        pushUndo();
        points = simplified;
        renderAll();
        status("Simplified to " + points.length + " points.", "ok");
    }

    function rdpClosed(pts, tol) {
        var n = pts.length;
        // Anchor on the farthest point from pts[0] to split the ring into two chains.
        var far = 0, max = -1;
        for (var i = 1; i < n; i++) {
            var d = (pts[i].x - pts[0].x) * (pts[i].x - pts[0].x) + (pts[i].z - pts[0].z) * (pts[i].z - pts[0].z);
            if (d > max) { max = d; far = i; }
        }
        var first = pts.slice(0, far + 1);
        var second = pts.slice(far).concat([pts[0]]);
        var s1 = rdpChain(first, tol);
        var s2 = rdpChain(second, tol);
        var out = s1.slice();
        for (var k = 1; k < s2.length - 1; k++) { out.push(s2[k]); }
        return out;
    }

    function rdpChain(chain, tol) {
        var n = chain.length;
        if (n < 3) { return chain.slice(); }
        var keep = new Array(n).fill(false);
        keep[0] = true; keep[n - 1] = true;
        var stack = [[0, n - 1]];
        while (stack.length) {
            var seg = stack.pop();
            var first = seg[0], last = seg[1];
            if (last <= first + 1) { continue; }
            var maxd = -1, idx = -1;
            for (var i = first + 1; i < last; i++) {
                var d = perpDist(chain[i], chain[first], chain[last]);
                if (d > maxd) { maxd = d; idx = i; }
            }
            if (maxd > tol && idx !== -1) {
                keep[idx] = true;
                stack.push([first, idx]);
                stack.push([idx, last]);
            }
        }
        var res = [];
        for (var j = 0; j < n; j++) { if (keep[j]) { res.push(chain[j]); } }
        return res;
    }

    function perpDist(p, a, b) {
        var dx = b.x - a.x, dz = b.z - a.z;
        var len = dx * dx + dz * dz;
        if (len === 0) { return Math.hypot(p.x - a.x, p.z - a.z); }
        var t = ((p.x - a.x) * dx + (p.z - a.z) * dz) / len;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(p.x - (a.x + t * dx), p.z - (a.z + t * dz));
    }

    // --- flags & presets ---------------------------------------------------

    function addFlagRow(name, value, skipState) {
        var row = el("div", { class: "cr-frow" }, ui.flags);
        var ni = el("input", { type: "text", value: name || "", placeholder: "pvp", class: "cr-fname" }, row);
        var vi = el("input", { type: "text", value: value || "", placeholder: "deny", class: "cr-fval" }, row);
        var rm = el("button", { class: "cr-vbtn", text: "✕", title: "Remove" }, row);
        function sync() { rebuildFlags(); }
        ni.addEventListener("change", sync);
        vi.addEventListener("change", sync);
        rm.addEventListener("click", function () { row.remove(); rebuildFlags(); });
        if (!skipState) { rebuildFlags(); }
    }

    function rebuildFlags() {
        var next = {};
        var rows = ui.flags.querySelectorAll(".cr-frow");
        rows.forEach(function (row) {
            var name = row.querySelector(".cr-fname").value.trim().toLowerCase();
            var val = row.querySelector(".cr-fval").value.trim();
            if (name) { next[name] = val; }
        });
        loadedFlags = next;
    }

    function loadPresets() {
        return request("GET", "/presets").then(function (data) {
            presets = data || {};
            ui.presetSelect.innerHTML = "";
            el("option", { value: "", text: "— preset —" }, ui.presetSelect);
            Object.keys(presets).forEach(function (name) {
                el("option", { value: name, text: name }, ui.presetSelect);
            });
        }).catch(function () { /* presets are optional */ });
    }

    function applyPresetLocal() {
        var name = ui.presetSelect.value;
        if (!name || !presets[name]) { status("Pick a preset first.", null); return; }
        pushUndo();
        var bundle = presets[name];
        Object.keys(bundle).forEach(function (k) { loadedFlags[k] = bundle[k]; });
        renderFlags();
        status("Applied preset '" + name + "'. Save to persist.", "ok");
    }

    // --- continent form ----------------------------------------------------

    function loadWorlds() {
        return request("GET", "/worlds").then(function (worlds) {
            ui.world.innerHTML = "";
            (worlds || []).forEach(function (w) {
                el("option", { value: w.name, text: w.name }, ui.world);
            });
        });
    }

    function loadContinentList(selectId) {
        return request("GET", "/continents").then(function (list) {
            ui.continent.innerHTML = "";
            el("option", { value: "", text: "➕ New continent" }, ui.continent);
            knownIds = {};
            (list || []).forEach(function (c) {
                knownIds[c.id] = true;
                el("option", { value: c.id, text: c.id + (c.hidden ? " (hidden)" : "") }, ui.continent);
            });
            if (selectId) { ui.continent.value = selectId; }
        });
    }

    function onSelectContinent() {
        var id = ui.continent.value;
        if (!id) { resetForm(); return; }
        request("GET", "/continents/" + encodeURIComponent(id)).then(function (c) {
            fillForm(c);
        }).catch(function (e) { status("Load failed: " + e.message, "error"); });
    }

    function resetForm() {
        ui.id.value = "";
        ui.name.value = "";
        ui.color.value = "#3b82f6";
        ui.priority.value = "10";
        ui.minY.value = "-64";
        ui.maxY.value = "320";
        ui.hidden.checked = false;
        hiddenFlag = false;
        points = [];
        loadedFlags = {};
        undoStack = [];
        redoStack = [];
        renderAll();
    }

    function fillForm(c) {
        ui.id.value = c.id || "";
        ui.name.value = c.displayName || c.id || "";
        ui.color.value = c.color || "#3b82f6";
        ui.priority.value = (c.priority != null ? c.priority : 10);
        ui.minY.value = (c.minY != null ? c.minY : -64);
        ui.maxY.value = (c.maxY != null ? c.maxY : 320);
        hiddenFlag = !!c.hidden;
        ui.hidden.checked = hiddenFlag;
        if (c.world) { ui.world.value = c.world; }
        points = (c.points || []).map(function (p) { return { x: p.x, z: p.z }; });
        loadedFlags = c.flags || {};
        undoStack = [];
        redoStack = [];
        renderAll();
        status("Loaded " + c.id, "ok");
    }

    function buildPayload() {
        rebuildFlags();
        return {
            id: (ui.id.value || "").trim().toLowerCase(),
            displayName: ui.name.value || ui.id.value,
            world: ui.world.value,
            color: ui.color.value,
            priority: parseInt(ui.priority.value, 10),
            minY: parseInt(ui.minY.value, 10),
            maxY: parseInt(ui.maxY.value, 10),
            hidden: hiddenFlag,
            points: points.map(function (p) { return { x: p.x, z: p.z }; }),
            flags: loadedFlags
        };
    }

    // --- drawing -----------------------------------------------------------

    function toggleDraw() {
        if (clickMode === "draw") {
            clickMode = null;
            ui.draw.classList.remove("cr-active");
            status("Draw mode off.");
        } else {
            clickMode = "draw";
            pendingMoveIndex = -1;
            ui.draw.classList.add("cr-active");
            status("Draw mode ON — click the map to add points.");
        }
        updatePointsLabel();
    }

    function updatePreview() {
        if (!bluemap || !bluemap.popupMarkerSet || typeof BlueMap === "undefined") { return; }
        if (previewMarker) {
            try { bluemap.popupMarkerSet.remove(previewMarker); } catch (e) { /* ignore */ }
            previewMarker = null;
        }
        if (points.length < 2) { return; }
        previewMarker = new BlueMap.LineMarker("continentregions-preview");
        previewMarker.position = { x: 0, y: 0, z: 0 };
        var line = [];
        points.forEach(function (p) { line.push(p.x, DISPLAY_Y, p.z); });
        line.push(points[0].x, DISPLAY_Y, points[0].z); // close the loop
        previewMarker.setLine(line);
        if (previewMarker.data) { previewMarker.data.label = "Continent preview"; }
        previewMarker.visible = true;
        try { bluemap.popupMarkerSet.add(previewMarker); } catch (e) { /* ignore */ }
    }

    // Hijack the block popup so a map click becomes a draw/move action.
    function installClickHook() {
        if (!bluemap.popupMarker || bluemap.popupMarker.__crHooked) { return; }
        var original = bluemap.popupMarker.open;
        bluemap.popupMarker.open = function () {
            var result = original.apply(this, arguments);
            try {
                if (this.position) { onMapClick(Math.round(this.position.x), Math.round(this.position.z)); }
            } catch (e) {
                console.error("[ContinentRegions]", e);
            }
            return result;
        };
        bluemap.popupMarker.__crHooked = true;
    }

    function onMapClick(x, z) {
        if (clickMode === "move" && pendingMoveIndex >= 0 && pendingMoveIndex < points.length) {
            editVertex(pendingMoveIndex, x, z);
            clickMode = null;
            pendingMoveIndex = -1;
            status("Vertex moved.", "ok");
            updatePointsLabel();
        } else if (clickMode === "draw") {
            addPoint(x, z);
        }
    }

    // --- actions -----------------------------------------------------------

    function save() {
        var payload = buildPayload();
        if (!payload.id) { status("Enter an ID.", "error"); return; }
        if (!payload.world) { status("Select a world.", "error"); return; }
        if (payload.points.length < 3) { status("Need at least 3 points.", "error"); return; }
        var isUpdate = !!knownIds[payload.id];
        var p = isUpdate
            ? request("PUT", "/continents/" + encodeURIComponent(payload.id), payload)
            : request("POST", "/continents", payload);
        p.then(function (res) {
            var warns = (res && res.warnings) || [];
            if (warns.length) {
                status("Saved with warnings: " + warns.join("; "), "warn");
            } else {
                status("Saved & applied: " + payload.id, "ok");
            }
            return loadContinentList(payload.id);
        }).catch(function (e) { status("Save failed: " + e.message, "error"); });
    }

    function rollback() {
        var id = (ui.id.value || "").trim().toLowerCase();
        if (!id || !knownIds[id]) { status("Rollback needs a saved continent.", "error"); return; }
        if (!window.confirm("Roll '" + id + "' back to its previous saved version?")) { return; }
        request("POST", "/continents/" + encodeURIComponent(id) + "/rollback", {}).then(function () {
            status("Rolled back " + id, "ok");
            return loadContinentList(id).then(function () { ui.continent.value = id; onSelectContinent(); });
        }).catch(function (e) { status("Rollback failed: " + e.message, "error"); });
    }

    function remove() {
        var id = (ui.id.value || "").trim().toLowerCase();
        if (!id) { status("Nothing to delete.", "error"); return; }
        if (!window.confirm("Delete continent '" + id + "' from storage, WorldGuard and BlueMap?")) { return; }
        request("DELETE", "/continents/" + encodeURIComponent(id)).then(function () {
            status("Deleted " + id, "ok");
            resetForm();
            return loadContinentList("");
        }).catch(function (e) { status("Delete failed: " + e.message, "error"); });
    }

    function exportJson() {
        var payload = buildPayload();
        var blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
        var a = el("a", { href: URL.createObjectURL(blob), download: (payload.id || "continent") + ".json" }, document.body);
        a.click();
        URL.revokeObjectURL(a.href);
        a.remove();
    }

    function exportAllJson() {
        request("GET", "/continents").then(function (list) {
            var arr = list || [];
            var blob = new Blob([JSON.stringify(arr, null, 2)], { type: "application/json" });
            var a = el("a", { href: URL.createObjectURL(blob), download: "continents-all.json" }, document.body);
            a.click();
            URL.revokeObjectURL(a.href);
            a.remove();
            status("Exported " + arr.length + " continent(s) to continents-all.json", "ok");
        }).catch(function (e) { status("Export all failed: " + e.message, "error"); });
    }

    function importJson(ev) {
        var file = ev.target.files && ev.target.files[0];
        if (!file) { return; }
        var reader = new FileReader();
        reader.onload = function () {
            try {
                fillForm(JSON.parse(reader.result));
                status("Imported from file. Review and Save to apply.", "ok");
            } catch (e) {
                status("Invalid JSON file: " + e.message, "error");
            }
        };
        reader.readAsText(file);
        ev.target.value = "";
    }

    // --- bootstrap ---------------------------------------------------------

    function waitForBlueMap(callback) {
        var tries = 0;
        var timer = setInterval(function () {
            tries++;
            if (window.bluemap && window.bluemap.popupMarkerSet) {
                clearInterval(timer);
                callback(window.bluemap);
            } else if (tries > 100) { // ~20s
                clearInterval(timer);
                callback(window.bluemap || null);
            }
        }, 200);
    }

    function init() {
        buildPanel();
        document.addEventListener("keydown", function (e) {
            var key = e.key.toLowerCase();
            if ((e.ctrlKey || e.metaKey) && key === "z" && !e.shiftKey) {
                e.preventDefault(); undo();
            } else if ((e.ctrlKey || e.metaKey) && (key === "y" || (key === "z" && e.shiftKey))) {
                e.preventDefault(); redo();
            }
        });
        renderAll();
        Promise.all([loadWorlds(), loadContinentList(""), loadPresets()])
            .then(function () { status("Ready. Pick a world, then New continent or select one.", "ok"); })
            .catch(function (e) { status("Cannot reach plugin API: " + e.message, "error"); });

        waitForBlueMap(function (bm) {
            bluemap = bm;
            if (bluemap && bluemap.popupMarker) {
                installClickHook();
            } else {
                status("BlueMap not ready — map drawing unavailable, manual editing still works.", "error");
            }
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();
