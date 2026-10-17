from flask import Flask, request, jsonify, Response
import hmac
import hashlib
import secrets
import time
import threading

DEVICE_KEYS = {
    "slab-01": b"poneglyph-dev-key-change-me-0001",
    "slab-test": b"poneglyph-dev-key-change-me-0001",
}
CODE_LENGTH = 6
CODE_SECONDS = 120
MAX_WRONG = 3

app = Flask(__name__)
lock = threading.Lock()
transfers = {}
order = []
last_counter = {}
next_number = 1


@app.post("/api/transfers")
def create_transfer():
    global next_number
    body = request.get_json(silent=True) or {}
    amount = body.get("amount_cents")
    dest = str(body.get("dest", "")).strip()
    requester = str(body.get("requester", "")).strip()
    if not isinstance(amount, int) or amount < 1 or amount > 9999999999:
        return jsonify({"error": "amount_cents must be 1 to 9999999999"}), 400
    if dest == "" or len(dest) > 64 or "|" in dest or '"' in dest:
        return jsonify({"error": "dest must be 1-64 characters, no | or quotes"}), 400
    if requester == "" or len(requester) > 64:
        requester = "unknown"

    code_parts = []
    for i in range(CODE_LENGTH):
        code_parts.append(str(secrets.randbelow(3)))
    code = "-".join(code_parts)

    with lock:
        tx_id = "tx" + str(next_number) + secrets.token_hex(2)
        next_number = next_number + 1
        now = time.time()
        transfers[tx_id] = {
            "tx_id": tx_id,
            "amount": amount,
            "dest": dest,
            "requester": requester,
            "code": code,
            "status": "awaiting_tablet",
            "created": now,
            "expires": now + CODE_SECONDS,
            "wrong": 0,
            "receipt": None,
        }
        order.append(tx_id)
    return jsonify({"tx_id": tx_id}), 201


@app.get("/api/transfers")
def list_transfers():
    now = time.time()
    rows = []
    with lock:
        for tx_id in reversed(order):
            t = transfers[tx_id]
            if t["status"] == "awaiting_tablet" and now > t["expires"]:
                t["status"] = "expired"
            rows.append({
                "tx_id": t["tx_id"],
                "amount": t["amount"],
                "dest": t["dest"],
                "requester": t["requester"],
                "code": t["code"] if t["status"] == "awaiting_tablet" or t["status"] == "approved" else "",
                "status": t["status"],
                "seconds_left": max(0, int(t["expires"] - now)),
                "wrong": t["wrong"],
                "receipt": t["receipt"],
            })
    return jsonify(rows)


@app.get("/api/pending")
def pending_for_device():
    device = request.args.get("device", "")
    if device not in DEVICE_KEYS:
        return jsonify({"error": "unknown device"}), 404
    now = time.time()
    with lock:
        for tx_id in order:
            t = transfers[tx_id]
            if t["status"] == "awaiting_tablet" and now > t["expires"]:
                t["status"] = "expired"
            if t["status"] == "awaiting_tablet":
                return jsonify({"tx_id": t["tx_id"], "amount": t["amount"], "dest": t["dest"]})
    return Response(status=204)


@app.post("/api/authorize")
def authorize():
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return jsonify({"result": "bad_request"}), 400
    device = body.get("device")
    counter = body.get("counter")
    tx_id = body.get("tx_id")
    amount = body.get("amount")
    dest = body.get("dest")
    glyphs = body.get("glyphs")
    sig = body.get("sig")
    if (body.get("v") != 1 or not isinstance(device, str) or not isinstance(counter, int)
            or not isinstance(tx_id, str) or not isinstance(amount, int) or not isinstance(dest, str)
            or not isinstance(glyphs, str) or not isinstance(sig, str)):
        return jsonify({"result": "bad_request"}), 400
    if device not in DEVICE_KEYS:
        return jsonify({"result": "unknown_device"}), 403

    msg = "v1|" + device + "|" + str(counter) + "|" + tx_id + "|" + str(amount) + "|" + dest + "|" + glyphs
    expected = hmac.new(DEVICE_KEYS[device], msg.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, sig.lower()):
        return jsonify({"result": "bad_signature"}), 401

    now = time.time()
    with lock:
        if counter <= last_counter.get(device, 0):
            return jsonify({"result": "replay"}), 409
        last_counter[device] = counter

        t = transfers.get(tx_id)
        if t is None:
            return jsonify({"result": "unknown_transfer"}), 404
        if t["status"] == "awaiting_tablet" and now > t["expires"]:
            t["status"] = "expired"
        if t["status"] != "awaiting_tablet":
            return jsonify({"result": "not_pending", "status": t["status"]}), 409
        if amount != t["amount"] or dest != t["dest"]:
            return jsonify({"result": "details_mismatch"}), 409

        if not hmac.compare_digest(glyphs, t["code"]):
            t["wrong"] = t["wrong"] + 1
            tries_left = MAX_WRONG - t["wrong"]
            if tries_left <= 0:
                t["status"] = "locked"
                return jsonify({"result": "locked"}), 403
            return jsonify({"result": "wrong_code", "tries_left": tries_left}), 403

        t["status"] = "approved"
        t["receipt"] = {
            "time": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(now)),
            "device": device,
            "counter": counter,
            "sig_prefix": sig[:12],
        }
    return jsonify({"result": "approved"}), 200


@app.get("/")
def dashboard():
    return Response(DASHBOARD_HTML, mimetype="text/html")


DASHBOARD_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RUNEVAULT</title>
<style>
  :root { --bg:#1d1f22; --slab:#2b2e33; --edge:#3b3f46; --ink:#e8e4da; --quiet:#a29d92; --gold:#d6a74a; --good:#6fbf73; --bad:#e0705c; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--ink); font-family:Georgia, 'Times New Roman', serif; }
  main { max-width:880px; margin:0 auto; padding:24px 16px 64px; }
  h1 { font-size:28px; letter-spacing:6px; margin:0 0 4px; }
  .sub { color:var(--quiet); margin:0 0 24px; font-family:system-ui, sans-serif; font-size:14px; }
  form { display:flex; flex-wrap:wrap; gap:8px; background:var(--slab); border:1px solid var(--edge); border-radius:10px; padding:16px; margin-bottom:24px; }
  input { flex:1 1 160px; background:var(--bg); color:var(--ink); border:1px solid var(--edge); border-radius:6px; padding:10px; font-size:15px; }
  button { background:var(--gold); color:#1d1f22; border:0; border-radius:6px; padding:10px 18px; font-size:15px; font-weight:bold; cursor:pointer; }
  #error { color:var(--bad); font-family:system-ui, sans-serif; font-size:14px; width:100%; }
  .card { background:var(--slab); border:1px solid var(--edge); border-radius:10px; padding:16px; margin-bottom:16px; }
  .row { display:flex; justify-content:space-between; gap:12px; flex-wrap:wrap; align-items:baseline; }
  .amount { font-size:26px; }
  .meta { color:var(--quiet); font-family:system-ui, sans-serif; font-size:13px; }
  .pill { font-family:system-ui, sans-serif; font-size:12px; padding:4px 10px; border-radius:999px; border:1px solid var(--edge); }
  .awaiting_tablet { color:var(--gold); border-color:var(--gold); }
  .approved { color:var(--good); border-color:var(--good); }
  .locked, .expired { color:var(--bad); border-color:var(--bad); }
  .glyphs { display:flex; gap:10px; margin:16px 0 8px; flex-wrap:wrap; }
  .glyph { width:64px; height:80px; background:var(--bg); border:1px solid var(--edge); border-radius:8px; display:flex; flex-direction:column; align-items:center; justify-content:center; }
  .glyph span { font-family:system-ui, sans-serif; font-size:11px; color:var(--quiet); margin-top:4px; }
  .receipt { font-family:ui-monospace, monospace; font-size:13px; color:var(--quiet); margin-top:8px; }
</style>
</head>
<body>
<main>
  <h1>PONEGLYPH</h1>
  <p class="sub">Request a transfer, then press the glyphs shown on the tablet to authorize it.</p>
  <form id="new">
    <input id="amount" placeholder="Amount ($)" autocomplete="off">
    <input id="dest" placeholder="Recipient account" autocomplete="off">
    <input id="requester" placeholder="Requested by" autocomplete="off">
    <button type="submit">Request transfer</button>
    <div id="error"></div>
  </form>
  <div id="list"></div>
</main>
<script>
var GLYPH_SVG = {
  "0": '<svg width="40" height="48" viewBox="0 0 40 48" fill="none" stroke="#e8e4da" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 4 L28 12 L20 20 L12 12 Z"/><path d="M20 20 L20 44"/><path d="M13 28 L27 28"/><path d="M20 38 L28 38"/></svg>',
  "1": '<svg width="40" height="48" viewBox="0 0 40 48" fill="none" stroke="#e8e4da" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 L36 24 L20 42 L4 24 Z"/><circle cx="20" cy="24" r="3" fill="#e8e4da"/></svg>',
  "2": '<svg width="40" height="48" viewBox="0 0 40 48" fill="none" stroke="#e8e4da" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10 44 L10 14"/><path d="M30 44 L30 14"/><path d="M5 14 L35 14"/><path d="M4 6 L36 6"/></svg>'
};
var GLYPH_NAME = { "0": "Key", "1": "Eye", "2": "Gate" };
var STATUS_TEXT = { awaiting_tablet: "Awaiting tablet", approved: "Approved", locked: "Locked", expired: "Expired" };

function dollars(cents) {
  var whole = Math.floor(cents / 100);
  var part = String(cents % 100);
  if (part.length < 2) { part = "0" + part; }
  return "$" + whole.toLocaleString("en-US") + "." + part;
}

document.getElementById("new").addEventListener("submit", function (e) {
  e.preventDefault();
  var errorBox = document.getElementById("error");
  errorBox.textContent = "";
  var text = document.getElementById("amount").value.trim().replace("$", "").split(",").join("");
  var dollarsPart = text;
  var centsPart = "00";
  if (text.indexOf(".") >= 0) {
    var pieces = text.split(".");
    if (pieces.length !== 2) { errorBox.textContent = "Enter an amount like 500 or 500.25"; return; }
    dollarsPart = pieces[0];
    centsPart = pieces[1];
    if (centsPart.length === 1) { centsPart = centsPart + "0"; }
  }
  if (dollarsPart === "") { dollarsPart = "0"; }
  if (!/^[0-9]+$/.test(dollarsPart) || !/^[0-9]{2}$/.test(centsPart)) { errorBox.textContent = "Enter an amount like 500 or 500.25"; return; }
  var cents = parseInt(dollarsPart, 10) * 100 + parseInt(centsPart, 10);
  var dest = document.getElementById("dest").value.trim();
  if (dest === "") { errorBox.textContent = "Enter a recipient account"; return; }
  fetch("/api/transfers", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ amount_cents: cents, dest: dest, requester: document.getElementById("requester").value.trim() })
  }).then(function (r) { return r.json().then(function (j) { return { ok: r.ok, body: j }; }); })
    .then(function (res) {
      if (!res.ok) { errorBox.textContent = res.body.error; return; }
      document.getElementById("amount").value = "";
      document.getElementById("dest").value = "";
      refresh();
    });
});

function render(rows) {
  var list = document.getElementById("list");
  list.innerHTML = "";
  for (var i = 0; i < rows.length; i++) {
    var t = rows[i];
    var card = document.createElement("div");
    card.className = "card";
    var html = '<div class="row"><div class="amount"></div><span class="pill ' + t.status + '">' + STATUS_TEXT[t.status] + '</span></div>';
    html += '<div class="meta"></div>';
    card.innerHTML = html;
    card.querySelector(".amount").textContent = dollars(t.amount) + " to " + t.dest;
    var meta = t.tx_id + " · requested by " + t.requester;
    if (t.status === "awaiting_tablet") { meta += " · " + t.seconds_left + "s left"; }
    if (t.wrong > 0) { meta += " · " + t.wrong + " wrong attempt" + (t.wrong === 1 ? "" : "s"); }
    card.querySelector(".meta").textContent = meta;
    if (t.code !== "") {
      var glyphRow = document.createElement("div");
      glyphRow.className = "glyphs";
      var ids = t.code.split("-");
      for (var g = 0; g < ids.length; g++) {
        var cell = document.createElement("div");
        cell.className = "glyph";
        cell.innerHTML = GLYPH_SVG[ids[g]] + "<span>" + GLYPH_NAME[ids[g]] + "</span>";
        glyphRow.appendChild(cell);
      }
      card.appendChild(glyphRow);
    }
    if (t.receipt) {
      var receipt = document.createElement("div");
      receipt.className = "receipt";
      receipt.textContent = "Sealed " + t.receipt.time + " by " + t.receipt.device + " · counter " + t.receipt.counter + " · sig " + t.receipt.sig_prefix + "…";
      card.appendChild(receipt);
    }
    list.appendChild(card);
  }
}

function refresh() {
  fetch("/api/transfers").then(function (r) { return r.json(); }).then(render).catch(function () {});
}
refresh();
setInterval(refresh, 1000);
</script>
</body>
</html>
"""


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
