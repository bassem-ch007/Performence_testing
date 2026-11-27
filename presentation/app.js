(() => {
  "use strict";
  const slides = Array.from(document.querySelectorAll(".page"));
  const navLinks = Array.from(document.querySelectorAll("#navigation a"));
  const crumb = document.getElementById("crumb");
  const progress = document.getElementById("progress");
  const previous = document.getElementById("prev");
  const next = document.getElementById("next");
  const dots = document.getElementById("dots");

  const dotButtons = slides.map((slide, i) => {
    const dot = document.createElement("span");
    dot.setAttribute("aria-label", "Slide " + (i + 1));
    dots.appendChild(dot);
    return dot;
  });

  function indexFromHash() {
    const key = decodeURIComponent(location.hash.slice(1));
    const index = slides.findIndex(slide => slide.id === key);
    return index < 0 ? 0 : index;
  }
  function render() {
    const active = indexFromHash();
    slides.forEach((slide, i) => {
      const selected = i === active;
      slide.classList.toggle("active", selected);
      slide.setAttribute("aria-hidden", selected ? "false" : "true");
    });
    navLinks.forEach(link => {
      const selected = link.dataset.page === slides[active].id;
      link.classList.toggle("active", selected);
      if (selected) link.setAttribute("aria-current", "page");
      else link.removeAttribute("aria-current");
    });
    dotButtons.forEach((dot, i) => dot.classList.toggle("active", i === active));
    crumb.textContent = slides[active].dataset.title;
    progress.textContent = String(active + 1).padStart(2, "0") + " / " + String(slides.length).padStart(2, "0");
    previous.disabled = active === 0;
    next.disabled = active === slides.length - 1;
    document.title = slides[active].dataset.title + " | Performance Lab";
    window.scrollTo(0, 0);
  }
  function move(delta) {
    const target = Math.max(0, Math.min(slides.length - 1, indexFromHash() + delta));
    location.hash = slides[target].id;
  }
  previous.addEventListener("click", () => move(-1));
  next.addEventListener("click", () => move(1));
  window.addEventListener("hashchange", render);
  window.addEventListener("keydown", event => {
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    const tag = event.target && event.target.tagName;
    if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
    if (event.key === "ArrowRight" || event.key === "PageDown") { event.preventDefault(); move(1); }
    if (event.key === "ArrowLeft" || event.key === "PageUp") { event.preventDefault(); move(-1); }
    if (event.key === "Home") { event.preventDefault(); location.hash = slides[0].id; }
    if (event.key === "End") { event.preventDefault(); location.hash = slides[slides.length - 1].id; }
  });
  render();
})();