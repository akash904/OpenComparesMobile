/*
 * Open Compares price extractor.
 *
 * Runs inside a marketplace page and returns the product cards it can see, as JSON.
 * It does not depend on any one site's markup: it finds rupee prices, then grows each
 * price into the smallest surrounding element that looks like a single product card.
 * Raw text is returned; the app parses and normalises the numbers.
 *
 * Returns: JSON string {url, title, products: [{name, priceText, mrpText, discountText,
 *          quantityText, etaText, inStock, canAdd, link, top}], scannedPrices}
 */
(function () {
  var MAX_PRODUCTS = 40;
  var PRICE_RE = /^(?:₹|rs\.?|inr)\s?\d[\d,]*(?:\.\d{1,2})?$/i;
  var PRICE_IN_TEXT_RE = /(?:₹|rs\.?|inr)\s?\d[\d,]*(?:\.\d{1,2})?/gi;
  var QTY_RE = /\b\d+(?:\.\d+)?\s?(?:x\s?\d+(?:\.\d+)?\s?)?(?:g|gm|gms|grams?|kg|kgs|ml|l|ltr|litres?|liters?|pc|pcs|pieces?|pack|units?|dozen|tabs?|tablets?|caps?|sachets?)\b(?:\s?(?:x|×)\s?\d+)?/i;
  var DISCOUNT_RE = /\b\d{1,2}\s?%\s?off\b|₹\s?\d[\d,]*\s?off\b/i;
  var ETA_RE = /\b\d{1,3}\s?(?:mins?|minutes|hrs?|hours)\b/i;
  var OOS_RE = /out of stock|sold out|notify me|currently unavailable|not available/i;
  var ADD_RE = /^(?:add|add to cart|add to bag|\+ ?add|add\s*\+)$/i;
  var NOISE_LINE_RE = /^(?:add|add to cart|add to bag|sponsored|ad|bestseller|new|off|options?|\d+ options?|view similar|save for later|buy now|notify me|\+|−|-)$/i;
  // Cart and checkout bars carry a price but are not products.
  var NOT_A_PRODUCT_RE = /^(?:view cart|go to cart|cart|proceed|checkout|place order|pay|total|subtotal|bill details|item total|unlock free delivery|get free delivery|free delivery)\b/i;
  var QTY_ALL_RE = new RegExp(QTY_RE.source, "gi");
  var WEIGHT_VOLUME_RE = /\d\s?(?:x\s?\d+(?:\.\d+)?\s?)?(?:g|gm|gms|grams?|kg|kgs|ml|l|ltr|litres?|liters?)\b/i;
  var LOGIN_RE = /\blog ?in\b|\bsign ?in\b/i;
  // Pages that want a delivery address before showing products (Flipkart Minutes).
  var LOCATION_WALL_RE = /select (a |your )?delivery (address|location)|use my current location|enter (your )?(delivery )?(pincode|location|address)/i;

  function text(el) {
    var t = (el.innerText !== undefined ? el.innerText : el.textContent) || "";
    return t.replace(/ /g, " ").trim();
  }

  function isStruck(el) {
    for (var e = el, i = 0; e && i < 3; e = e.parentElement, i++) {
      var tag = e.tagName;
      if (tag === "DEL" || tag === "S" || tag === "STRIKE") return true;
      var style = window.getComputedStyle ? getComputedStyle(e) : null;
      if (style && /line-through/.test(style.textDecorationLine || style.textDecoration || "")) return true;
    }
    return false;
  }

  function visible(el) {
    if (!el.getBoundingClientRect) return true;
    // Zepto wraps names in display:contents spans, which have no box of their own.
    while (el.parentElement && window.getComputedStyle && getComputedStyle(el).display === "contents") el = el.parentElement;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  // "₹21 OFF" is a saving, not a price. Zepto splits it into "₹21" and "OFF".
  function isSaving(el) {
    var next = el.nextElementSibling || (el.nextSibling && el.nextSibling.nodeType === 3 ? el.nextSibling : null);
    var nextText = next ? (next.textContent || "").trim() : "";
    if (/^off\b/i.test(nextText)) return true;
    var parentText = el.parentElement ? (el.parentElement.textContent || "").replace(/\s+/g, " ").trim() : "";
    return /^(?:save\s)?(?:₹|rs\.?)\s?\d[\d,]*(?:\.\d+)?\s?off$/i.test(parentText);
  }

  var BARE_NUMBER_RE = /^\d[\d,]*(?:\.\d{1,2})?$/;

  // Some sites (e.g. Instamart) draw the rupee sign with CSS (::before) next to a bare number.
  function cssCurrency(el) {
    if (!window.getComputedStyle) return false;
    for (var e = el, i = 0; e && i < 2; e = e.parentElement, i++) {
      var before = getComputedStyle(e, "::before").content || "";
      var after = getComputedStyle(e, "::after").content || "";
      if (/₹|rs\.?|inr/i.test(before + after)) return true;
    }
    return false;
  }

  function amount(t) {
    var m = t.replace(/,/g, "").match(/\d+(?:\.\d+)?/);
    return m ? parseFloat(m[0]) : NaN;
  }

  // 1. Smallest elements whose whole text is a single price.
  var priceEls = [];
  var all = document.body ? document.body.getElementsByTagName("*") : [];
  for (var i = 0; i < all.length; i++) {
    var el = all[i];
    if (/^(SCRIPT|STYLE|NOSCRIPT|SVG|PATH|OPTION|SELECT)$/i.test(el.tagName)) continue;
    var t = (el.textContent || "").replace(/ /g, " ").trim();
    if (t.length === 0 || t.length > 24) continue;
    if (!PRICE_RE.test(t)) {
      // A bare number only counts as a price when the page draws a rupee sign beside it.
      if (t.length > 9 || !BARE_NUMBER_RE.test(t) || el.firstElementChild || !cssCurrency(el)) continue;
      t = "₹" + t;
    }
    var childHasIt = false;
    for (var c = el.firstElementChild; c; c = c.nextElementSibling) {
      if ((c.textContent || "").replace(/ /g, " ").trim() === t) { childHasIt = true; break; }
    }
    if (childHasIt || !visible(el) || isSaving(el)) continue;
    priceEls.push({ el: el, text: t, value: amount(t), struck: isStruck(el) });
  }

  // Selling prices (not struck through) inside an element.
  function sellingPricesIn(container) {
    var n = 0;
    for (var k = 0; k < priceEls.length; k++) {
      if (!priceEls[k].struck && container.contains(priceEls[k].el)) n++;
    }
    return n;
  }

  // 2. Grow each selling price into the largest ancestor that still holds just one product.
  var cards = [];
  var seen = [];
  for (var p = 0; p < priceEls.length; p++) {
    var pe = priceEls[p];
    if (pe.struck) continue;
    var card = null;
    for (var a = pe.el.parentElement, depth = 0; a && a !== document.body && depth < 10; a = a.parentElement, depth++) {
      if (sellingPricesIn(a) > 1) break;
      card = a;
    }
    if (!card || seen.indexOf(card) >= 0) continue;
    // A card needs some descriptive text besides prices.
    var raw = text(card);
    if (raw.replace(PRICE_IN_TEXT_RE, "").replace(/[^a-z]/gi, "").length < 4) continue;
    seen.push(card);
    cards.push({ card: card, price: pe });
  }

  // 3. Read the fields of each card.
  function nameLike(line) {
    if (line.length < 3 || line.length > 160) return false;
    var hasPrice = PRICE_IN_TEXT_RE.test(line);
    PRICE_IN_TEXT_RE.lastIndex = 0;
    if (hasPrice) return false;
    if (NOISE_LINE_RE.test(line) || DISCOUNT_RE.test(line) || ETA_RE.test(line) && line.length < 14) return false;
    if (QTY_RE.test(line) && line.replace(QTY_ALL_RE, "").replace(/[^a-z]/gi, "").length < 3) return false;
    return !/^[\d.,\s()★☆%]+$/.test(line); // ratings, counts
  }

  // The name is the most prominent text in the card: largest type, then boldest, then longest.
  // Instamart, for one, puts a longer description under the name in smaller type.
  function pickName(card, lines) {
    var best = "", bestScore = null, els = [];
    var walker = document.createTreeWalker(card, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      var el = walker.currentNode.parentElement;
      if (!el || els.indexOf(el) >= 0 || !walker.currentNode.textContent.trim()) continue;
      els.push(el);
      var line = text(el);
      if (line.indexOf("\n") >= 0 || !nameLike(line) || !visible(el)) continue;
      var cs = getComputedStyle(el);
      var score = [Math.round((parseFloat(cs.fontSize) || 0) * 2), parseInt(cs.fontWeight, 10) || 400, line.length];
      if (!bestScore || score[0] > bestScore[0] ||
          score[0] === bestScore[0] && (score[1] > bestScore[1] || score[1] === bestScore[1] && score[2] > bestScore[2])) {
        best = line;
        bestScore = score;
      }
    }
    if (best) return best;
    for (var j = 0; j < lines.length; j++) {
      var l = lines[j].trim();
      if (nameLike(l) && l.length > best.length) best = l;
    }
    return best;
  }

  // Pack size: a separate size line beats a size inside the name ("25 g High Protein Milk"),
  // and grams or millilitres beat a bare "1 pack" or "1 pc".
  // A line that is only a size ("1 ltr", "1 pack (500 ml)") beats one inside a description.
  function pickQuantity(lines, name) {
    var best = null, bestRank = 0;
    for (var j = 0; j < lines.length; j++) {
      var line = lines[j].trim();
      if (line === name || line.length > 40 || !QTY_RE.test(line)) continue;
      var sizeOnly = line.replace(QTY_ALL_RE, "").replace(/[^a-z]/gi, "").length <= 6;
      var rank = (sizeOnly ? 2 : 0) + (WEIGHT_VOLUME_RE.test(line) ? 1 : 0);
      if (rank > bestRank) { best = line; bestRank = rank; }
    }
    if (best) return best;
    var inName = name.match(QTY_RE);
    return inName ? inName[0] : null;
  }

  var products = [];
  for (var q = 0; q < cards.length && products.length < MAX_PRODUCTS; q++) {
    var cd = cards[q].card;
    if (!(cards[q].price.value > 0)) continue;
    var body = text(cd);
    var lines = body.split(/\n+/);
    var name = pickName(cd, lines);
    if (!name) {
      var img = cd.querySelector("img[alt]");
      if (img && img.alt && img.alt.length > 3) name = img.alt.trim();
    }
    if (!name || NOT_A_PRODUCT_RE.test(name)) continue;

    var mrp = null;
    for (var r = 0; r < priceEls.length; r++) {
      var o = priceEls[r];
      if (o.struck && cd.contains(o.el) && o.value > cards[q].price.value) { mrp = o.text; break; }
    }

    var canAdd = false;
    var buttons = cd.querySelectorAll("button, [role=button], div, span");
    for (var b = 0; b < buttons.length && b < 200; b++) {
      var bt = (buttons[b].textContent || "").trim();
      if (bt.length <= 14 && ADD_RE.test(bt)) { canAdd = true; break; }
    }
    // Icon-only add buttons (e.g. a "+") usually carry an accessible label.
    if (!canAdd && cd.querySelector('[aria-label*="add" i], [data-testid*="add" i]')) canAdd = true;

    var link = cd.tagName === "A" ? cd.href : null;
    if (!link) {
      var anchor = cd.querySelector("a[href]") || (cd.closest && cd.closest("a[href]"));
      if (anchor) link = anchor.href;
    }

    var qty = pickQuantity(lines, name);
    var disc = body.match(DISCOUNT_RE);
    var eta = body.match(ETA_RE);
    var rect = cd.getBoundingClientRect ? cd.getBoundingClientRect() : { top: 0 };

    products.push({
      name: name.slice(0, 160),
      priceText: cards[q].price.text,
      mrpText: mrp,
      discountText: disc ? disc[0] : null,
      quantityText: qty,
      etaText: eta ? eta[0] : null,
      inStock: !OOS_RE.test(body),
      canAdd: canAdd,
      link: link,
      top: Math.round(rect.top + (window.scrollY || 0))
    });
  }

  // Page order: top to bottom, as the user sees it.
  products.sort(function (x, y) { return x.top - y.top; });

  // No prices but a login prompt usually means the site wants the user to sign in first.
  // No products but an address picker means the site wants a delivery address; that page often
  // mentions logging in too ("Login to see saved addresses"), so check it first.
  var head = text(document.body).slice(0, 4000);
  var needsLocation = products.length === 0 &&
    (LOCATION_WALL_RE.test(head) || /hyperlocal-preview-page/.test(location.pathname));
  var needsLogin = products.length === 0 && !needsLocation && LOGIN_RE.test(head);

  return JSON.stringify({
    url: location.href,
    title: document.title,
    products: products,
    scannedPrices: priceEls.length,
    needsLogin: needsLogin,
    needsLocation: needsLocation
  });
})();
