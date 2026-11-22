/*
 * Open Compares site status.
 *
 * Runs inside a marketplace page and reports whether the shopper is logged in and which
 * delivery location the site is using. It only checks whether a login token exists; it
 * never reads or returns the token, and nothing leaves the phone.
 *
 * Returns: JSON string {login: "in"|"out"|"unknown",
 *                       location: "set"|"guessed"|"missing"|"unknown",
 *                       label, lat, lon, loginWall, cartCount}
 *   cartCount is how many items are in the site's cart, or null when unknown.
 *   "guessed" means the site picked a default city rather than an address the shopper chose.
 */
(function () {
  var host = location.hostname;
  var out = { login: "unknown", location: "unknown", label: null, lat: null, lon: null, loginWall: false, cartCount: null };

  function stored(key) {
    try {
      var raw = localStorage.getItem(key);
      return raw == null ? null : JSON.parse(raw);
    } catch (e) {
      return null;
    }
  }

  function cookie(name) {
    var m = document.cookie.match(new RegExp("(?:^|; )" + name + "=([^;]*)"));
    if (!m) return null;
    try { return decodeURIComponent(m[1]); } catch (e) { return m[1]; }
  }

  function num(v) {
    var n = typeof v === "string" ? parseFloat(v) : v;
    return typeof n === "number" && isFinite(n) && n !== 0 ? n : null;
  }

  function text(el) {
    return ((el && (el.innerText || el.textContent)) || "").replace(/\s+/g, " ").trim();
  }

  // Short visible texts near the top of the page, where sites show the delivery address.
  function headerLines() {
    var lines = [];
    var all = document.body ? document.body.getElementsByTagName("*") : [];
    for (var i = 0; i < all.length && lines.length < 30; i++) {
      var el = all[i];
      if (el.children.length) continue;
      var r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0 || r.top > 160 || r.top < -5) continue;
      var t = text(el);
      if (t && t.length <= 80) lines.push(t);
    }
    return lines;
  }

  var body = document.body ? text(document.body).slice(0, 5000) : "";
  var SELECT_LOCATION_RE = /^(select|set|choose|enter|add)( your| a)?( delivery)? (location|address|pincode)$/i;
  var LOGIN_WALL_RE = /log ?in with (your )?phone|log ?in to continue|sign ?in to continue|please log ?in/i;
  out.loginWall = LOGIN_WALL_RE.test(body);

  if (/(^|\.)blinkit\.com$/.test(host)) {
    var auth = stored("auth");
    if (auth) out.login = auth.accessToken ? "in" : "out";
    var bcart = stored("cart");
    if (bcart && typeof bcart.count === "number") out.cartCount = bcart.count;
    var coords = (stored("location") || {}).coords;
    if (coords && num(coords.lat) && num(coords.lon)) {
      out.location = coords.isDefault ? "guessed" : "set";
      out.label = coords.isDefault ? coords.cityName || coords.locality : coords.locality || coords.landmark || coords.cityName;
      out.lat = num(coords.lat);
      out.lon = num(coords.lon);
    } else {
      out.location = "missing";
    }
  } else if (/(^|\.)zepto(now)?\.com$/.test(host)) {
    var user = stored("user");
    if (user && user.state) out.login = user.state.isAuth ? "in" : "out";
    var zcart = (stored("cart") || {}).state;
    if (zcart && zcart.cartContent) {
      out.cartCount = Object.keys(zcart.cartContent).reduce(function (n, k) {
        return n + ((zcart.cartContent[k] && zcart.cartContent[k].quantity) || 0);
      }, 0);
    }
    var pos = (stored("user-position") || {}).state;
    if (pos) {
      var p = pos.userPosition;
      if (p) {
        out.location = "set";
        out.lat = num(p.latitude || p.lat);
        out.lon = num(p.longitude || p.lng || p.lon);
        out.label = p.shortAddress || p.name || p.formattedAddress || null;
      } else {
        out.location = "missing";
      }
    }
  } else if (/(^|\.)swiggy\.com$/.test(host)) {
    var loc = cookie("userLocation");
    if (loc) {
      try {
        var o = JSON.parse(loc);
        if (num(o.lat) && num(o.lng)) {
          out.location = "set";
          out.lat = num(o.lat);
          out.lon = num(o.lng);
          out.label = o.annotation || o.address || null;
        }
      } catch (e) {}
    }
    if (out.location === "unknown") out.location = "missing";
    if (cookie("_is_logged_in") === "1") out.login = "in";
    else if (out.loginWall) out.login = "out";
  }

  // Any site: a "Select location" prompt at the top means no address is chosen yet,
  // and a visible "Login" button in the header means the shopper is logged out.
  var header = headerLines();
  for (var i = 0; i < header.length; i++) {
    if (SELECT_LOCATION_RE.test(header[i]) && out.location !== "set") out.location = out.location === "guessed" ? "guessed" : "missing";
    if (/^(log ?in|sign ?in|login\/sign ?up|log ?in \/ sign ?up)$/i.test(header[i]) && out.login === "unknown") out.login = "out";
  }
  if (out.loginWall && out.login === "unknown") out.login = "out";
  if (out.label) out.label = String(out.label).replace(/\s+/g, " ").trim().slice(0, 80);

  return JSON.stringify(out);
})();
