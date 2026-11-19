// Sofaly website: small jobs, no libraries.
// 1) The hero TV walks through the cross like the remote would (◀ ▶), and the visitor can do it too.
// 2) The language menu changes page; the first visit is sent once to the visitor's language.
// 3) On the features page, the index marks the world on screen.
(() => {
  const root = document.documentElement;

  // 2) Language. Remembered only in this browser; the site works the same without it.
  const store = {
    get: (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: (k, v) => { try { localStorage.setItem(k, v); } catch (e) {} },
  };
  const sel = document.getElementById("lang");
  if (sel) {
    sel.addEventListener("change", () => {
      const o = sel.selectedOptions[0];
      store.set("sofaly_lang", o.dataset.code);
      location.href = o.value;
    });
    const chosen = store.get("sofaly_lang");
    const here = root.dataset.lang;
    if (!chosen && here === root.dataset.default) {
      const want = (navigator.languages || [navigator.language || ""]).map((l) => l.toLowerCase());
      const codes = [...sel.options].map((o) => o.dataset.code);
      let hit = null;
      for (const l of want) {
        hit = codes.find((c) => c.toLowerCase() === l) || codes.find((c) => c.toLowerCase() === l.split("-")[0]);
        if (hit) break;
      }
      store.set("sofaly_lang", hit || here);
      if (hit && hit !== here) location.replace([...sel.options].find((o) => o.dataset.code === hit).value + location.hash);
    }
  }

  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  root.classList.add("js");

  // 1) The cross in the hero: it walks by itself like the remote would (▼ down the column, ▶ to the next world),
  //    showing the key it "pressed". A click or an arrow key takes over for a while, then it walks again.
  const box = document.querySelector(".hero-tv");
  if (box) {
    const ws = [...box.querySelectorAll(".w")];
    const names = JSON.parse(box.dataset.names || "{}");
    const label = box.querySelector(".label-name");
    const n = box.querySelector(".label-n");
    const tv = box.querySelector(".tv");
    const key = box.querySelector(".key");
    const rtl = root.dir === "rtl";
    let i = 1, sub = 0, timer = null, wait = null;
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
    const step = () => {
      const col = ws[i].querySelectorAll(".w-col li").length;
      if (sub < Math.min(col, 3) - 1) { sub++; mark(); press("▼"); }
      else { show(i + 1); press(rtl ? "◀" : "▶"); }
    };
    const play = () => { clearInterval(timer); if (!still) timer = setInterval(step, 1500); };
    const hold = () => { clearInterval(timer); clearTimeout(wait); wait = setTimeout(play, 9000); };
    show(i);
    play();
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
  }

  // 1b) The showcase: one screen at a time, by itself, only while it is on screen. A tab stops on that screen.
  const show = document.querySelector(".show");
  if (show) {
    const DUR = 7000;
    const tabs = [...show.querySelectorAll(".tab")];
    const scrs = [...show.querySelectorAll(".scr")];
    const caps = [...show.querySelectorAll(".cap")];
    const band = show.closest(".showcase");
    let at = 0, timer = null, visible = false, pinned = false;
    show.style.setProperty("--dur", DUR + "ms");
    const go = (k) => {
      at = (k + tabs.length) % tabs.length;
      const id = tabs[at].dataset.k;
      tabs.forEach((t, j) => { t.classList.toggle("on", j === at); t.setAttribute("aria-pressed", j === at ? "true" : "false"); });
      scrs.forEach((s) => { s.hidden = s.dataset.k !== id; });
      caps.forEach((c) => { c.hidden = c.dataset.k !== id; });
      const k2 = getComputedStyle(tabs[at]).getPropertyValue("--k");
      band.style.setProperty("--k", k2); show.querySelector(".tv").style.setProperty("--k", k2);
      // restart the progress bar
      const bar = tabs[at].querySelector("i"); bar.style.animation = "none"; void bar.offsetWidth; bar.style.animation = "";
    };
    const run = () => { clearInterval(timer); if (!still && visible && !pinned) timer = setInterval(() => go(at + 1), DUR); };
    tabs.forEach((t, j) => t.addEventListener("click", () => { pinned = true; show.classList.add("still"); go(j); clearInterval(timer);
      clearTimeout(t._w); t._w = setTimeout(() => { pinned = false; show.classList.remove("still"); run(); }, 20000); }));
    if (still) show.classList.add("still");
    go(0);
    if ("IntersectionObserver" in window) {
      new IntersectionObserver((es) => { visible = es[0].isIntersecting; if (visible) { go(at); run(); } else clearInterval(timer); }, { threshold: .35 }).observe(show);
    } else { visible = true; run(); }
  }

  // 1c) Things rise into place as they scroll in.
  if (!still && "IntersectionObserver" in window) {
    const els = document.querySelectorAll(".worlds > li, .cards > li, .chapters > li, .panel, .feat, .plan, .minis > li, .steps > li, .faq details, .compare tbody tr, .show");
    const io = new IntersectionObserver((es) => es.forEach((e) => { if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); } }), { rootMargin: "0px 0px -8% 0px" });
    els.forEach((el) => {
      const sibs = el.parentElement ? [...el.parentElement.children] : [];
      el.style.setProperty("--d", Math.min(sibs.indexOf(el), 8));
      el.classList.add("rv"); io.observe(el);
    });
  }

  // 3) Features index.
  const toc = document.querySelector(".toc");
  if (toc && "IntersectionObserver" in window) {
    const links = new Map([...toc.querySelectorAll("a")].map((a) => [a.hash.slice(1), a]));
    const io = new IntersectionObserver((es) => {
      es.forEach((e) => {
        if (!e.isIntersecting) return;
        links.forEach((a) => a.classList.remove("here"));
        const a = links.get(e.target.id);
        if (a) { a.classList.add("here"); if (toc.scrollWidth > toc.clientWidth + 4) toc.scrollTo({ left: a.offsetLeft - 16, behavior: "smooth" }); }
      });
    }, { rootMargin: "-30% 0px -60% 0px" });
    document.querySelectorAll(".chapter").forEach((s) => io.observe(s));
  }
})();
