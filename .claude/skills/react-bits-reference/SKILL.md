---
name: react-bits-reference
description: How to use React Bits (reactbits.dev, free animated React components) in this project, which has no React. Use when looking for an animated background, text effect, micro-interaction or card effect for the website (website/), the TV web panel (app/src/main/assets/painel/) or as a visual reference for a TV screen, or when someone mentions React Bits.
---

# React Bits in Sofaly

React Bits (https://reactbits.dev) is a catalogue of ~217 free animated React components
(text animations, animations, components, micro, backgrounds). There is nothing to install here:
no part of this project uses React.

| Surface | Stack | How React Bits fits |
|---|---|---|
| TV app (`app/`) | Kotlin, plain Views | Visual reference only. Rebuild the idea with Views/animators, following the cross (cruz) look. |
| Website (`website/` -> `site/`) | `build.py`, static HTML + `assets/site.css` + `assets/site.js` | Port the effect to vanilla CSS/JS. Never add React or a bundler to the site. |
| Web panel (`app/src/main/assets/painel/`) | vanilla JS served by the TV | Port only light CSS effects. No three.js/ogl/gsap: the panel runs on the TV's own server and on phones. |

## Reading a component's source

- Catalogue for agents: `curl -sL https://reactbits.dev/llms.txt` (names and doc links per category).
- Source of one component, plain JS + CSS flavour:
  `curl -sL https://reactbits.dev/r/<Name>-JS-CSS.json` (field `files[].content`), e.g. `SplitText-JS-CSS`.
- Check its dependencies first (gsap, motion, three, ogl). A component that needs WebGL or gsap
  is a reference, not a port.

## Rules

- License: MIT + Commons Clause. Using an effect inside our site or app is fine (commercial too);
  never copy the components themselves into anything we distribute as components or a kit.
  Keep a one-line comment `/* adapted from React Bits <Name> (reactbits.dev) */` on a port.
- React Bits **Pro** (pro.reactbits.dev, paid: from US$79/year) is not used. Don't suggest it
  without Paulo deciding to pay.
- Project look wins over the demo: brand orange #FF6A2B on dark, #CE3D00 on light, never pink or
  purple defaults (most demos ship #5227FF / #FF9FFC: replace them), large type, respect
  `prefers-reduced-motion`, and keep the same visual reading as the cross (cruz) home.
