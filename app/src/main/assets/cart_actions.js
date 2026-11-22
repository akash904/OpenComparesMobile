/*
 * Open Compares cart actions.
 *
 * Runs inside a marketplace page that lists a product, finds that product's card by name and
 * price, and reads or presses its ADD button or quantity stepper. One action per run: the app
 * waits between runs so the site can update. It never goes near checkout or payment.
 *
 * Called with {mode: "find" | "add" | "plus" | "minus" | "variant" | "confirm", name, price}.
 * "variant" and "confirm" work in a pack-size picker some sites open after ADD (Instamart):
 * the first adds the row with the right price, the second closes the picker with Confirm. An empty price matches
 * any price, for cart pages that show line totals.
 * Returns: JSON string {found, count, canAdd, canPlus, outOfStock, cardText}
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
      // Icons on either side of a bare number (Blinkit's cart page).
      var before = siblingElement(node, false);
      var after = siblingElement(node, true);
      if (smallControl(before) && smallControl(after)) {
        return { count: parseInt(t, 10), plus: inc ? clickable(inc) : after, minus: dec ? clickable(dec) : before };
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
    return null;
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
      return JSON.stringify({ found: !!done, picker: true });
    }
    // The row for this pack: start from each main ADD button and climb to the nearest box
    // that shows this price, as long as it holds no other main ADD button.
    var mains = Array.prototype.slice.call(picker.querySelectorAll("button, [role=button]")).filter(function (b) {
      return ADD_RE.test(visibleText(b)) || /^add$/i.test(b.getAttribute("aria-label") || "");
    });
    for (var m = 0; m < mains.length; m++) {
      for (var a = mains[m].parentElement, depth = 0; a && a !== picker && depth < 8; a = a.parentElement, depth++) {
        var others = mains.filter(function (x) { return x !== mains[m] && a.contains(x); }).length;
        if (others > 0) break;
        var text = ((a.getAttribute("aria-label") || "") + " " + (a.innerText || "")).replace(/,/g, "");
        if (!priceRe || priceRe.test(text)) {
          mains[m].click();
          return JSON.stringify({ found: true, picker: true });
        }
      }
    }
    return JSON.stringify({ found: false, picker: true });
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
      if (up > 0 && controlCount(c) > 1) break;
      if (priceIn(c)) { card = c; break; }
    }
  }
  if (!card) {
    return JSON.stringify({ found: false, count: 0, canAdd: false, canPlus: false, outOfStock: false, cardText: null, candidates: cards.length });
  }

  var step = stepper(card);
  var add = step ? null : addButton(card);
  var count = step ? step.count : 0;

  if (args.mode === "add" && add) add.click();
  else if (args.mode === "plus" && step && step.plus) step.plus.click();
  else if (args.mode === "minus" && step && step.minus) step.minus.click();

  return JSON.stringify({
    found: true,
    count: count,
    canAdd: !!add,
    canPlus: !!(step && step.plus),
    outOfStock: !add && !step && OOS_RE.test(card.innerText || ""),
    cardText: norm(card.innerText).slice(0, 120)
  });
})(__OC_ARGS__);
