// Sofaly website: three small jobs, nothing else.
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

  // 1) The cross in the hero.
  const box = document.querySelector(".hero-tv");
  if (box) {
    const ws = [...box.querySelectorAll(".w")];
    const names = JSON.parse(box.dataset.names || "{}");
    const label = box.querySelector(".label-name");
    const n = box.querySelector(".label-n");
    const tv = box.querySelector(".tv");
    let i = 1, timer = null;
    const show = (k) => {
      i = (k + ws.length) % ws.length;
      ws.forEach((w, j) => w.classList.toggle("on", j === i));
      const w = ws[i];
      tv.style.setProperty("--c", getComputedStyle(w).getPropertyValue("--c"));
      label.textContent = names[w.dataset.w] || "";
      n.textContent = `${i + 1} ${box.dataset.of} ${ws.length}`;
    };
    const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
    const play = () => { if (!still) timer = setInterval(() => show(i + 1), 3200); };
    show(i);
    play();
    // Click a world, or use the arrow keys while the TV has focus.
    box.tabIndex = 0;
    box.addEventListener("click", (e) => {
      const w = e.target.closest(".w");
      if (w) { clearInterval(timer); show(ws.indexOf(w)); }
    });
    box.addEventListener("keydown", (e) => {
      const d = { ArrowRight: 1, ArrowLeft: -1 }[e.key] * (root.dir === "rtl" ? -1 : 1);
      if (d) { e.preventDefault(); clearInterval(timer); show(i + d); }
    });
    box.addEventListener("mouseenter", () => clearInterval(timer));
    box.addEventListener("mouseleave", () => { clearInterval(timer); play(); });
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
