/*
 * Open Compares cart actions.
 *
 * Runs inside a marketplace page that lists a product, finds that product's card by name and
 * price, and reads or presses its ADD button or quantity stepper. One action per run: the app
 * waits between runs so the site can update. It never goes near checkout or payment.
 *
 * Called with {mode: "find" | "add" | "plus" | "minus" | "variant" | "confirm" | "lines" | "removeOne", name, price, force}.
 * force presses even when the card doesn't look live yet, after the app has waited long enough.
 * "lines" and "removeOne" work on a cart page without a name: they count every line's items,
 * or take one item out of the first line, returning {lines, units, pressed}.
 * "variant" and "confirm" work in a pack-size picker some sites open after ADD (Instamart):
 * the first adds the row with the right price, the second closes the picker with Confirm. An empty price matches
 * any price, for cart pages that show line totals.
 * Returns: JSON string {found, live, count, canAdd, canPlus, outOfStock, cardText}; live is false
 * while the card's buttons don't work yet, and then nothing is pressed.
 */
(function (args) {
  var ADD_RE = /^(?:add|add to cart|add to bag|\+ ?add|add ?\+)$/i;
  var OOS_RE = /out of stock|sold out|notify me|currently unavailable/i;

  function norm(s) {
    return (s || "").replace(/ /g, " ").replace(/\s+/g, " ").trim().toLowerCase();
  }

  // "Potato (Aloo)" on a search page can be just "Potato" in the cart.
  function stripped(s) {
    return norm(s).replace(/\s*\([^)]*\)/g, "").trim();
  }

  function compact(s) {
    return (s || "").replace(/[\s,]/g, "").toLowerCase();
  }

  // The control to press for an element. Never a link: whole product cards are often links,
  // and following one opens the product page instead of adding it.
  function clickable(el) {
    return (el.closest && el.closest("button, [role=button]")) || el;
  }

  // Presses a control. A control inside a card that is a link (Flipkart) would also follow the
  // link and open the product page, so the link is told to stay put for this one click.
  function press(el) {
    var link = el.closest && el.closest("a[href]");
    function stay(e) { e.preventDefault(); }
    // A plain box, not a button (Flipkart's "Add" on items with options), may only take a press
    // that starts with the finger going down; a click alone reaches the card and opens it.
    if (!el.closest("button, [role=button]")) {
      var r = el.getBoundingClientRect();
      var at = { bubbles: true, cancelable: true, composed: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2,
        pointerId: 1, pointerType: "touch", isPrimary: true, button: 0, buttons: 1 };
      el.dispatchEvent(new PointerEvent("pointerdown", at));
      at.buttons = 0;
      el.dispatchEvent(new PointerEvent("pointerup", at));
    }
    if (link) link.addEventListener("click", stay);
    try {
      el.click();
    } finally {
      if (link) link.removeEventListener("click", stay);
    }
  }

  // React sites (Flipkart) draw their cards a second or two before the buttons work, and a press
  // in between falls through to the card's link. A control is live once the page has finished
  // loading and, on a React page, React has attached to it or a box around it.
  function hasReactKey(el, prefix) {
    for (var k in el) if (k.indexOf(prefix) === 0) return true;
    return false;
  }
  function live(el) {
    if (!el || args.force) return true;
    if (document.readyState !== "complete") return false;
    var tops = document.querySelectorAll("body, body > *, body > * > *");
    var react = false;
    for (var i = 0; i < tops.length && !react; i++) react = hasReactKey(tops[i], "__react");
    if (!react) return true;
    for (var e = el; e && e !== document.body; e = e.parentElement) {
      if (hasReactKey(e, "__reactProps")) return true;
    }
    return false;
  }

  function visibleText(el) {
    return el.getBoundingClientRect().width > 0 ? (el.innerText || "").trim() : "";
  }

  // ADD button inside a card, or null. Real buttons first; otherwise the innermost element
  // reading "ADD", so a wrapper around the whole card is never chosen.
  function addButton(card) {
    var buttons = card.querySelectorAll("button, [role=button]");
    for (var i = 0; i < buttons.length; i++) {
      var t = visibleText(buttons[i]);
      if (t.length <= 14 && ADD_RE.test(t)) return buttons[i];
    }
    var all = card.querySelectorAll("div, span");
    for (var j = all.length - 1; j >= 0; j--) {
      var u = visibleText(all[j]);
      if (u.length <= 14 && ADD_RE.test(u) && !all[j].querySelector("div, span")) return clickable(all[j]);
    }
    // Icon-only add buttons, e.g. Instamart's "+" labelled "Add item to cart".
    var labelled = card.querySelectorAll("[aria-label], [data-testid]");
    for (var k = 0; k < labelled.length; k++) {
      var aria = labelled[k].getAttribute("aria-label") || "";
      var testid = labelled[k].getAttribute("data-testid") || "";
      var isAdd = /^add(\s|$)/i.test(aria) || /(^|[-_])add($|[-_]to[-_]cart)|add-to-cart/i.test(testid);
      if (isAdd && labelled[k].getBoundingClientRect().width > 0) return clickable(labelled[k]);
    }
    return null;
  }

  function isButton(el) {
    return el && (el.tagName === "BUTTON" || el.getAttribute("role") === "button");
  }

  // A small control next to the count: an icon, "+" or "-", not a block of text.
  function smallControl(el) {
    if (!el || el.nodeType !== 1) return false;
    var t = (el.innerText || el.textContent || "").trim();
    return t.length <= 2 && el.getBoundingClientRect().width > 0;
  }

  function siblingElement(node, forward) {
    for (var n = forward ? node.nextSibling : node.previousSibling; n; n = forward ? n.nextSibling : n.previousSibling) {
      if (n.nodeType === 1) return n;
      if (n.nodeType === 3 && n.textContent.trim()) return null;
    }
    return null;
  }

  // The quantity stepper: [minus] count [plus]. Returns {count, plus, minus} or null.
  // Sites lay it out as buttons around a count element, or as icons around a bare number.
  function stepper(card) {
    var inc = card.querySelector('[aria-label*="increase" i], [aria-label*="increment" i]');
    var dec = card.querySelector('[aria-label*="decrease" i], [aria-label*="decrement" i], [aria-label*="remove" i]');
    // Labelled icon pairs with the count drawn elsewhere between them (Instamart): a "remove"
    // control and an "add" control under one parent, and a number in that parent.
    var minusish = card.querySelector('[aria-label*="decre" i], [data-testid*="remove" i], [data-testid*="minus" i], [data-testid*="decrement" i]');
    if (minusish && minusish.getBoundingClientRect().width > 0) {
      var plusish = inc || card.querySelector('[data-testid*="add" i], [aria-label^="add" i], [data-testid*="increment" i], [data-testid*="plus" i]');
      if (plusish) {
        for (var box0 = minusish.parentElement, d0 = 0; box0 && d0 < 4; box0 = box0.parentElement, d0++) {
          if (!box0.contains(plusish)) continue;
          // A count that says itself in words, e.g. Instamart's "Current item count is 1": its
          // rolling digits keep the next number on the page too.
          var said = box0.querySelectorAll('[aria-label*="count" i], [aria-label*="quantity" i]');
          for (var q = 0; q < said.length; q++) {
            var qm = /\b(\d{1,2})\b/.exec(said[q].getAttribute("aria-label"));
            if (qm && said[q] !== minusish && said[q] !== plusish && qm[1] !== "0") {
              return { count: parseInt(qm[1], 10), plus: clickable(plusish), minus: clickable(minusish) };
            }
          }
          var tw = document.createTreeWalker(box0, NodeFilter.SHOW_TEXT);
          while (tw.nextNode()) {
            var n0 = tw.currentNode.textContent.trim();
            var p0 = tw.currentNode.parentElement;
            if (/^\d{1,2}$/.test(n0) && n0 !== "0" && p0 && p0.getBoundingClientRect().width > 0) {
              return { count: parseInt(n0, 10), plus: clickable(plusish), minus: clickable(minusish) };
            }
          }
          break;
        }
      }
    }
    var walker = document.createTreeWalker(card, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      var node = walker.currentNode;
      var t = node.textContent.trim();
      var el = node.parentElement;
      // Zepto keeps a hidden "0" stepper behind its ADD button; only a visible count is real.
      if (!/^\d{1,2}$/.test(t) || t === "0" || !el || el.getBoundingClientRect().width === 0) continue;
      // Icons on either side of the number, or of a wrapper around it (Blinkit's cart page,
      // Flipkart, whose number sits three wrappers deep): [icon] 1 [icon].
      for (var at = node, lvl = 0; at && lvl < 4; at = lvl === 0 ? el : at.parentElement, lvl++) {
        var before = siblingElement(at, false);
        var after = siblingElement(at, true);
        if (smallControl(before) && smallControl(after)) {
          // − is the one on the left as drawn: Flipkart puts + first in the page and flips the row.
          var flipped = before.getBoundingClientRect().left > after.getBoundingClientRect().left;
          return {
            count: parseInt(t, 10),
            plus: inc ? clickable(inc) : (flipped ? before : after),
            minus: dec ? clickable(dec) : (flipped ? after : before)
          };
        }
      }
      // A count element between two buttons, possibly inside wrappers.
      var box = el.parentElement;
      for (var d = 0; d < 3 && box; d++, box = box.parentElement) {
        var buttons = Array.prototype.slice.call(box.children).filter(isButton);
        if (buttons.length >= 2) {
          return {
            count: parseInt(t, 10),
            plus: inc ? clickable(inc) : buttons[buttons.length - 1],
            minus: dec ? clickable(dec) : buttons[0]
          };
        }
      }
    }
    return null;
  }

  // Distinct add or increase controls inside an element: one per product card.
  function controlCount(el) {
    var seen = [];
    var all = el.querySelectorAll("button, [role=button], [aria-label], [data-testid]");
    for (var i = 0; i < all.length; i++) {
      var x = all[i];
      var t = visibleText(x);
      var aria = x.getAttribute("aria-label") || "";
      var tid = x.getAttribute("data-testid") || "";
      var isControl = (t.length <= 14 && ADD_RE.test(t)) || /^add(\s|$)|increase|increment/i.test(aria) ||
        /(^|[-_])add($|[-_]to[-_]cart)|add-to-cart/i.test(tid);
      if (isControl && seen.indexOf(clickable(x)) < 0) seen.push(clickable(x));
    }
    return seen.length;
  }

  // An element with two or more children that each have their own ADD button or stepper is a
  // list of products, never one product's card; climbing past it would borrow a neighbour's
  // button. (A card's own price block and ADD button are separate children, so prices alone
  // can't tell a card from a list.)
  function isList(el) {
    var n = 0;
    for (var c = el.firstElementChild; c; c = c.nextElementSibling) {
      if (hasControl(c)) n++;
      if (n >= 2) return true;
    }
    return false;
  }

  function hasControl(el) {
    return !!(addButton(el) || stepper(el));
  }

  // Cards whose name matches: the nearest ancestor of the name that has an ADD button or stepper.
  function findCards() {
    var want = norm(args.name);
    var wantShort = stripped(args.name);
    // Without a price (cart pages), a name that differs only by a bracketed part also counts.
    var loose = !args.price;
    var cards = [];
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      var el = walker.currentNode.parentElement;
      if (!el) continue;
      var text = el.innerText || el.textContent;
      if (norm(text) !== want && !(loose && wantShort && stripped(text) === wantShort)) continue;
      for (var a = el.parentElement, depth = 0; a && a !== document.body && depth < 10; a = a.parentElement, depth++) {
        if (isList(a)) break;
        if (hasControl(a)) {
          if (cards.indexOf(a) < 0) cards.push(a);
          break;
        }
      }
    }
    return cards;
  }

  // Several cards can share a name (sizes, variants): the price tells them apart. Match the
  // number on its own, since some sites (Instamart) draw the rupee sign with CSS.
  var amount = (args.price || "").replace(/[^\d.]/g, "").replace(/\.0+$/, "");
  var priceRe = amount ? new RegExp("(^|[^\\d.])" + amount.replace(".", "\\.") + "(?![\\d])") : null;
  // A pack-size picker opened over the page, if any.
  function openPicker() {
    var dialogs = document.querySelectorAll("[role=dialog], dialog");
    for (var d = dialogs.length - 1; d >= 0; d--) {
      if (dialogs[d].getBoundingClientRect().height > 0 && hasControl(dialogs[d])) return dialogs[d];
    }
    // Flipkart's options sheet is a plain full-screen overlay: a fixed box over the whole
    // page with its own ADD buttons and prices.
    var boxes = document.body.querySelectorAll("div");
    for (var f = 0; f < boxes.length; f++) {
      var r = boxes[f].getBoundingClientRect();
      if (r.height < window.innerHeight * 0.9 || getComputedStyle(boxes[f]).position !== "fixed") continue;
      if (/₹\s*\d/.test(boxes[f].innerText || "") && addButton(boxes[f])) return boxes[f];
    }
    return null;
  }

  // A picker that is a proper dialog (Instamart), not a plain overlay sheet (Flipkart).
  function isDialog(picker) {
    return picker.matches("[role=dialog], dialog");
  }

  // ADD buttons in a picker: real buttons, and in an overlay sheet the innermost elements reading "ADD".
  function pickerAdds(picker) {
    var sheet = !isDialog(picker);
    return Array.prototype.slice.call(picker.querySelectorAll("button, [role=button], div, span")).filter(function (b) {
      var t = visibleText(b);
      if (isButton(b)) return ADD_RE.test(t) || /^add$/i.test(b.getAttribute("aria-label") || "");
      return sheet && t.length <= 14 && ADD_RE.test(t) && !b.querySelector("div, span") && !isButton(clickable(b));
    }).map(clickable);
  }

  if (args.mode === "variant" || args.mode === "confirm") {
    var picker = openPicker();
    if (!picker) return JSON.stringify({ found: false, picker: false });
    if (args.mode === "confirm") {
      // Confirm may be a plain element (Instamart); take the innermost one with that text.
      var done = Array.prototype.slice.call(picker.querySelectorAll("*")).filter(function (b) {
        return /^(confirm|done|continue|add to cart|add item)$/i.test(visibleText(b));
      }).pop();
      if (done) done = clickable(done);
      if (done) done.click();
      // A sheet without a confirm button (Flipkart) has taken the pack already: tapping the
      // dimmed backdrop above the sheet closes it.
      else if (!isDialog(picker)) {
        var x = window.innerWidth / 2, y = window.innerHeight * 0.15;
        var backdrop = document.elementFromPoint(x, y);
        if (backdrop && picker.contains(backdrop)) {
          var at = { bubbles: true, cancelable: true, clientX: x, clientY: y, pointerId: 1, pointerType: "touch", isPrimary: true, buttons: 1 };
          backdrop.dispatchEvent(new PointerEvent("pointerdown", at));
          at.buttons = 0;
          backdrop.dispatchEvent(new PointerEvent("pointerup", at));
          backdrop.click();
        }
      }
      return JSON.stringify({ found: !!done, picker: true });
    }
    // The row for this pack: start from each main ADD button and climb to the nearest box
    // that shows this price, as long as it holds no other main ADD button.
    var mains = pickerAdds(picker);
    function rowText(a) {
      return ((a.getAttribute("aria-label") || "") + " " + (a.innerText || "")).replace(/,/g, "");
    }
    for (var m = 0; m < mains.length; m++) {
      for (var a = mains[m].parentElement, depth = 0; a && a !== picker && depth < 8; a = a.parentElement, depth++) {
        var others = mains.filter(function (x) { return x !== mains[m] && a.contains(x); }).length;
        // A box with another row's ADD, or in a sheet another row's count, is past this row.
        if (others > 0 || (!isDialog(picker) && stepper(a))) break;
        if (!priceRe || priceRe.test(rowText(a))) {
          press(mains[m]);
          return JSON.stringify({ found: true, picker: true });
        }
      }
    }
    // The pack is already in the cart (Flipkart shows its count in the sheet): press its +.
    if (priceRe && !isDialog(picker)) {
      var pw = document.createTreeWalker(picker, NodeFilter.SHOW_TEXT);
      while (pw.nextNode()) {
        if (!priceRe.test(pw.currentNode.textContent.replace(/,/g, ""))) continue;
        for (var r = pw.currentNode.parentElement, up2 = 0; r && r !== picker && up2 < 6; r = r.parentElement, up2++) {
          if (mains.some(function (x) { return r.contains(x); })) break;
          var rs = stepper(r);
          if (rs && rs.plus) {
            press(rs.plus);
            return JSON.stringify({ found: true, picker: true });
          }
        }
      }
    }
    return JSON.stringify({ found: false, picker: true });
  }

  // Every line's stepper on a cart page, without knowing the product names.
  function cartSteppers() {
    var found = [];
    function keep(s) {
      var known = found.some(function (f) { return f.minus === s.minus; });
      if (!known) found.push(s);
    }
    // Counts that say themselves in words come first (Instamart's "Current item count is 1"):
    // its spare rolling digits would otherwise look like a list of tiny steppers.
    var said = document.querySelectorAll('[aria-label*="count" i], [aria-label*="quantity" i]');
    for (var i = 0; i < said.length; i++) {
      var sm = /\b(\d{1,2})\b/.exec(said[i].getAttribute("aria-label"));
      if (!sm || sm[1] === "0" || said[i].closest("header, nav, footer, [role=dialog], [role=alertdialog], dialog")) continue;
      for (var b = said[i].parentElement, up = 0; b && b !== document.body && up < 4; b = b.parentElement, up++) {
        var s0 = stepper(b);
        if (!s0 || !s0.minus || s0.minus.closest("[aria-hidden=true]")) continue;
        keep(s0);
        break;
      }
    }
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      var t = walker.currentNode.textContent.trim();
      var el = walker.currentNode.parentElement;
      if (!/^\d{1,2}$/.test(t) || t === "0" || !el || el.getBoundingClientRect().width === 0) continue;
      // Not the cart badge in the site's header or menu, nor anything in a prompt.
      if (el.closest("header, nav, footer, [role=dialog], [role=alertdialog], dialog")) continue;
      // The nearest box around the number that holds a stepper, never a whole list.
      for (var a = el, depth = 0; a && a !== document.body && depth < 6; a = a.parentElement, depth++) {
        if (isList(a)) break;
        var s = stepper(a);
        // Instamart rolls its count like an odometer, with spare digits in hidden boxes beside
        // it; those boxes are not controls. A real − is never hidden from screen readers.
        if (!s || !s.minus || s.minus.closest("[aria-hidden=true]")) continue;
        keep(s);
        break;
      }
    }
    return found;
  }

  // Some sites ask "Remove this item?" when the last one is taken out: say yes.
  function confirmRemoval() {
    var dialogs = document.querySelectorAll("[role=dialog], [role=alertdialog], dialog");
    for (var d = dialogs.length - 1; d >= 0; d--) {
      var box = dialogs[d];
      if (box.getBoundingClientRect().height === 0 || !/remove|delete|clear/i.test(box.innerText || "")) continue;
      var yes = Array.prototype.slice.call(box.querySelectorAll("button, [role=button]")).filter(function (b) {
        return /^(remove|remove item|yes|yes,? remove|delete|ok)$/i.test(visibleText(b));
      }).pop();
      if (yes) {
        yes.click();
        return true;
      }
    }
    return false;
  }

  // "lines" reads a cart page's lines; "removeOne" takes one item out of the first line.
  if (args.mode === "lines" || args.mode === "removeOne") {
    if (args.mode === "removeOne" && confirmRemoval()) {
      return JSON.stringify({ lines: -1, units: -1, pressed: true });
    }
    var steps = cartSteppers();
    var units = steps.reduce(function (n, s) { return n + s.count; }, 0);
    var pressed = false;
    if (args.mode === "removeOne" && steps.length) {
      press(steps[0].minus);
      pressed = true;
    }
    return JSON.stringify({ lines: steps.length, units: units, pressed: pressed });
  }

  var cards = findCards();
  function priceIn(el) {
    return !priceRe || priceRe.test((el.innerText || "").replace(/,/g, ""));
  }
  var card = null;
  for (var i = 0; i < cards.length && !card; i++) {
    // The price can sit just outside the part holding the name and button (Instamart):
    // look a little higher, while still inside this one product's card.
    for (var c = cards[i], up = 0; c && c !== document.body && up < 6; c = c.parentElement, up++) {
      if (up > 0 && (controlCount(c) > 1 || isList(c))) break;
      if (priceIn(c)) { card = c; break; }
    }
  }
  if (!card) {
    return JSON.stringify({ found: false, count: 0, canAdd: false, canPlus: false, outOfStock: false, cardText: null, candidates: cards.length });
  }

  var step = stepper(card);
  var add = step ? null : addButton(card);
  var count = step ? step.count : 0;

  // A list can be drawn again just after it appears (Flipkart), replacing every card: only press
  // a card that an earlier run saw too, so it isn't about to be thrown away.
  var seen = card.hasAttribute("data-oc-seen");
  card.setAttribute("data-oc-seen", "1");
  var ready = args.force || (seen && live(add || (step && (step.plus || step.minus))));
  if (ready) {
    if (args.mode === "add" && add) press(add);
    else if (args.mode === "plus" && step && step.plus) press(step.plus);
    else if (args.mode === "minus" && step && step.minus) press(step.minus);
  }

  return JSON.stringify({
    found: true,
    live: ready,
    count: count,
    canAdd: !!add,
    canPlus: !!(step && step.plus),
    outOfStock: !add && !step && OOS_RE.test(card.innerText || ""),
    cardText: norm(card.innerText).slice(0, 120)
  });
})(__OC_ARGS__);
