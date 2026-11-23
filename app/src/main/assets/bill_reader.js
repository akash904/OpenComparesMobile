/*
 * Open Compares bill reader.
 *
 * Runs on a marketplace's cart page and reads its bill: item total, each charge, discounts and
 * the amount to pay. Tips and donations are left out; they are not part of the order price.
 *
 * Returns: JSON string {found, rows: [{label, amount}], toPay, freeDeliveryAbove, itemLines}
 *   amount is in rupees; a charge shown as FREE is 0, a discount is negative.
 */
(function () {
  var HEADING_RE = /^(bill details|bill summary|price details|order summary|payment summary|bill)$/i;
  var TOTAL_RE = /^(to pay|you pay|grand total|total payable|total amount|amount payable|net payable|order total|total)$/i;
  // Side notes inside a line, such as Amazon's "Free for" (Prime) or "Inclusive of taxes": not line names.
  var NOTE_RE = /^(free\b|for\b|inclusive|incl\.|includes\b|prime\b)/i;
  var SKIP_RE = /tip|donation|feeding india|charity/i;
  var AMOUNT_RE = /^[-−]?\s?(?:₹|rs\.?)\s?[\d,]+(?:\.\d{1,2})?$/i;
  var FREE_RE = /^free$/i;
  // Badges such as Blinkit's "Saved ₹5" next to the item total are neither labels nor amounts.
  var BADGE_RE = /^(?:you )?saved?\b|\bsaved$|^save (?:₹|rs)/i;

  function struck(el) {
    for (var e = el, i = 0; e && i < 3; e = e.parentElement, i++) {
      if (/^(DEL|S|STRIKE)$/.test(e.tagName)) return true;
      if (/line-through/.test(getComputedStyle(e).textDecorationLine || "")) return true;
    }
    return false;
  }

  function rupees(t) {
    var n = parseFloat(t.replace(/[^\d.]/g, ""));
    return /^[-−]/.test(t.trim()) ? -n : n;
  }

  // Visible text pieces in page order, each with whether it is struck through.
  function pieces(root) {
    var out = [];
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      var el = walker.currentNode.parentElement;
      var t = walker.currentNode.textContent.replace(/ /g, " ").replace(/\s+/g, " ").trim();
      if (!t || !el) continue;
      var r = el.getBoundingClientRect();
      if (r.width === 0 && r.height === 0 && getComputedStyle(el).display !== "contents") continue;
      // "₹" and "30" split into two spans read as one amount.
      if (out.length && /^(?:₹|rs\.?)$/i.test(out[out.length - 1].text) && /^[\d,.]+$/.test(t)) {
        out[out.length - 1].text += t;
        continue;
      }
      out.push({ text: t, struck: struck(el), el: el });
    }
    return out;
  }

  // The bill heading, then everything after it up to and including the total.
  var all = pieces(document.body);
  var start = -1;
  for (var i = 0; i < all.length; i++) {
    if (HEADING_RE.test(all[i].text)) { start = i; break; }
  }
  if (start < 0) {
    var empty = /your (shopping )?(cart|basket) is( getting)? (empty|lonely)|cart is empty|no items in (your )?cart/i.test(
      (document.body.innerText || "").slice(0, 1500));
    return JSON.stringify({ found: false, empty: empty, rows: [], toPay: null, freeDeliveryAbove: null });
  }

  var rows = [];
  var toPay = null;
  var label = null;
  var amounts = [];
  var freeAbove = null;

  function flush() {
    if (label && !SKIP_RE.test(label)) {
      // A struck-through amount is the old price; the other one counts. FREE means 0.
      var live = amounts.filter(function (a) { return !a.struck; });
      var pick = live.length ? live[live.length - 1] : amounts[amounts.length - 1];
      if (pick) rows.push({ label: label, amount: pick.free ? 0 : rupees(pick.text) });
    }
    label = null;
    amounts = [];
  }

  for (var j = start + 1; j < all.length && j < start + 120; j++) {
    var p = all[j];
    var m = p.text.match(/free (?:delivery )?(?:above|over|on orders above|for orders above) (?:₹|rs\.?)\s?([\d,]+)/i);
    if (m) { freeAbove = parseFloat(m[1].replace(/,/g, "")); continue; }
    if (BADGE_RE.test(p.text)) continue;
    if (NOTE_RE.test(p.text) && !FREE_RE.test(p.text)) continue;
    if (AMOUNT_RE.test(p.text) || FREE_RE.test(p.text)) {
      if (label) amounts.push({ text: p.text, struck: p.struck, free: FREE_RE.test(p.text) });
      continue;
    }
    // A new label: finish the previous row first.
    if (label && amounts.length) flush();
    if (TOTAL_RE.test(p.text)) {
      // The total's amount is the next amount on the page.
      for (var k = j + 1; k < all.length && k < j + 6; k++) {
        if (AMOUNT_RE.test(all[k].text) && !all[k].struck) { toPay = rupees(all[k].text); break; }
      }
      break;
    }
    if (p.text.length <= 40 && /[a-z]/i.test(p.text)) {
      label = p.text;
      amounts = [];
    }
  }
  if (label && amounts.length) flush();

  return JSON.stringify({ found: toPay !== null, rows: rows, toPay: toPay, freeDeliveryAbove: freeAbove });
})();
