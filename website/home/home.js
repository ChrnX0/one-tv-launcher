// Sofaly home: small jobs, no libraries.
(() => {
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;

  // 1) Things rise into place as they scroll in; the side index marks the world on screen.
  const rv = document.querySelectorAll(".rv");
  if (!("IntersectionObserver" in window)) rv.forEach((x) => x.classList.add("in"));
  else {
    const io = new IntersectionObserver((es) => es.forEach((en) => {
      if (!en.isIntersecting) return;
      const el = en.target, sib = [...el.parentElement.children].indexOf(el);
      el.style.transitionDelay = Math.min(sib * 35, 280) + "ms";
      el.classList.add("in"); io.unobserve(el);
    }), { rootMargin: "0px 0px -8% 0px" });
    rv.forEach((x) => io.observe(x));
    const links = [...document.querySelectorAll("#side a")];
    const wo = new IntersectionObserver((es) => es.forEach((en) => {
      if (en.isIntersecting) links.forEach((a) => a.classList.toggle("on", a.getAttribute("href") === "#" + en.target.id));
    }), { rootMargin: "-40% 0px -55% 0px" });
    document.querySelectorAll(".world").forEach((w) => wo.observe(w));
  }

  // 2) Language: the menu changes page; the first visit goes once to the visitor's language (remembered only here).
  const root = document.documentElement;
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
    const here = root.dataset.lang;
    if (!store.get("sofaly_lang") && here === root.dataset.default) {
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

  // 3) The mascot: the TV hops once when the page opens and when clicked (no timer: it never competes with reading),
  //    waves its arms 3 times when something starts playing on it, and the one by "Coming soon" hops once on arrival.
  const lg = document.querySelector(".logo");
  if (!lg || still) return;
  const pular = () => { if (lg.classList.contains("pula")) return; lg.classList.add("pula"); setTimeout(() => lg.classList.remove("pula"), 1150); };
  lg.addEventListener("click", pular);
  const TOCA = ["player", "radio", "alarm", "games"];
  const first = document.querySelector("li[data-k]");
  if (first) {
    const olhar = () => { const on = document.querySelector("li[data-k].on"); lg.classList.toggle("toca", !!on && TOCA.includes(on.dataset.k)); };
    new MutationObserver(olhar).observe(first.parentElement, { subtree: true, attributes: true, attributeFilter: ["class"] });
    olhar();
  }
  setTimeout(pular, 1400);
  const es = document.querySelector(".espera");
  if (es && "IntersectionObserver" in window) {
    const io = new IntersectionObserver((x) => { if (x[0].isIntersecting) { es.classList.add("in"); io.disconnect(); } }, { threshold: 0.8 });
    io.observe(es);
  }
})();
