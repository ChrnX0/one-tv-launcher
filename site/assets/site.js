// Sofaly website: two small jobs the kit (system.js, motion.js, layout.js) does not do. No libraries.
// 1) The cross on the TV walks by itself like the remote would (▼ down the column, ▶ to the next world), only
//    from the page's load, never under reduced motion or on a phone, with a pause button; the visitor can
//    take over with a click or the arrow keys. A TV without data-walk stays on its first picture (Watch open).
// 2) The language list changes page; the first visit is sent once to the visitor's language.
(() => {
  const root = document.documentElement;
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches || matchMedia("(max-width: 639px)").matches;
  const rtl = root.dir === "rtl";

  document.querySelectorAll(".hero-tv").forEach((box) => {
    const ws = [...box.querySelectorAll(".w")];
    if (!ws.length) return;
    const names = JSON.parse(box.dataset.names || "{}");
    const label = box.querySelector(".label-name");
    const n = box.querySelector(".label-n");
    const tv = box.querySelector(".tv");
    const key = box.querySelector(".key");
    let i = 1, sub = 0, timer = null, wait = null, paused = false;
    const press = (k) => { if (!key || still) return; key.textContent = k; key.classList.remove("press"); void key.offsetWidth; key.classList.add("press"); };
    const mark = () => ws[i].querySelectorAll(".w-col li").forEach((li, j) => li.classList.toggle("sel", j === sub));
    const show = (k) => {
      i = (k + ws.length) % ws.length; sub = 0;
      ws.forEach((w, j) => w.classList.toggle("on", j === i));
      const w = ws[i];
      tv.style.setProperty("--c", getComputedStyle(w).getPropertyValue("--c"));
      label.textContent = names[w.dataset.w] || "";
      n.textContent = `${i + 1} ${box.dataset.of} ${ws.length}`;
      mark();
    };
    show(i);
    if (!box.hasAttribute("data-walk")) return;
    const step = () => {
      const col = ws[i].querySelectorAll(".w-col li").length;
      if (sub < Math.min(col, 3) - 1) { sub++; mark(); press("▼"); }
      else { show(i + 1); press(rtl ? "◀" : "▶"); }
    };
    // it walks from the page's load (never started by scrolling to it) and rests while the tab is hidden
    const play = () => { clearInterval(timer); if (!still && !paused && !document.hidden) timer = setInterval(step, 1600); };
    const hold = () => { clearInterval(timer); clearTimeout(wait); wait = setTimeout(play, 9000); };
    play();
    document.addEventListener("visibilitychange", () => (document.hidden ? clearInterval(timer) : play()));
    // the pause button (the kit's look: a 32px disc at the frame's lower right)
    const host = box.closest("[data-ui-pause-host]");
    if (host && !still) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "loop-pause";
      const draw = () => {
        btn.innerHTML = paused
          ? '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M5.25 3.7v8.6c0 .62.68 1 1.2.66l6.5-4.3c.47-.31.47-1 0-1.32l-6.5-4.3c-.52-.34-1.2.04-1.2.66Z"/></svg>'
          : '<svg viewBox="0 0 16 16" aria-hidden="true"><rect x="4.25" y="3.25" width="2.75" height="9.5" rx="0.9"/><rect x="9" y="3.25" width="2.75" height="9.5" rx="0.9"/></svg>';
        btn.setAttribute("aria-label", paused ? "Play animation" : "Pause animation");
        btn.title = btn.getAttribute("aria-label");
        btn.classList.toggle("is-paused", paused);
      };
      draw();
      btn.addEventListener("click", () => { paused = !paused; draw(); paused ? clearInterval(timer) : play(); });
      host.append(btn);
    }
    box.tabIndex = 0;
    box.addEventListener("click", (e) => { const w = e.target.closest(".w"); if (w) { hold(); show(ws.indexOf(w)); press("OK"); } });
    box.addEventListener("keydown", (e) => {
      const d = { ArrowRight: 1, ArrowLeft: -1 }[e.key] * (rtl ? -1 : 1);
      if (d) { e.preventDefault(); hold(); show(i + d); press(e.key === "ArrowRight" ? "▶" : "◀"); }
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault(); hold();
        const col = ws[i].querySelectorAll(".w-col li").length;
        sub = Math.max(0, Math.min(col - 1, sub + (e.key === "ArrowDown" ? 1 : -1))); mark(); press(e.key === "ArrowDown" ? "▼" : "▲");
      }
    });
  });

  // 3) Still screens: each TV drawing plays a 7 s choreography; a still one is stopped at its best moment (data-t),
  //    the same picture every time, so nothing on the page moves but the one loop. A tab's screen is stopped when shown.
  const freeze = (tv) => {
    if (tv.hasAttribute("data-frozen") || !tv.getClientRects().length || !tv.getAnimations) return;
    tv.setAttribute("data-frozen", "");
    const t = +tv.dataset.t || 0;
    tv.getAnimations({ subtree: true }).forEach((a) => { a.pause(); a.currentTime = t; });
  };
  const stills = [...document.querySelectorAll(".tv--still[data-t]")];
  stills.forEach(freeze);
  if ("MutationObserver" in window) {
    new MutationObserver(() => stills.forEach(freeze)).observe(document.body, { subtree: true, attributes: true, attributeFilter: ["hidden"] });
  }

  // 2) Language. Remembered only in this browser; the site works the same without it.
  const store = {
    get: (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: (k, v) => { try { localStorage.setItem(k, v); } catch (e) {} },
  };
  const sel = document.querySelector("[data-lang-pick]");
  if (sel) {
    const code = (o) => o.dataset.code;
    sel.addEventListener("change", () => { const o = sel.selectedOptions[0]; store.set("sofaly_lang", code(o)); location.href = o.value; });
    const chosen = store.get("sofaly_lang");
    const here = root.dataset.lang;
    if (!chosen && here === root.dataset.default) {
      const want = (navigator.languages || [navigator.language || ""]).map((l) => l.toLowerCase());
      const opts = [...sel.options].map((o) => [code(o), o]);
      let hit = null;
      for (const l of want) {
        hit = opts.find(([c]) => c.toLowerCase() === l) || opts.find(([c]) => c.toLowerCase() === l.split("-")[0]);
        if (hit) break;
      }
      store.set("sofaly_lang", hit ? hit[0] : here);
      if (hit && hit[0] !== here) location.replace(hit[1].value + location.hash);
    }
  }
})();
