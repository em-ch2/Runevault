import { gsap } from 'gsap';

export function reducedMotion(): boolean {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
}

/** Slides elements up into place, staggered, replacing any reveal already running on them. */
export function slideUp(targets: Element[], options: { distance?: number; stagger?: number } = {}): void {
  if (!targets.length) return;
  gsap.killTweensOf(targets);
  if (reducedMotion()) {
    gsap.set(targets, { clearProps: 'opacity,transform' });
    return;
  }
  gsap.fromTo(targets,
    { autoAlpha: 0, y: options.distance ?? 28 },
    { autoAlpha: 1, y: 0, duration: 0.5, ease: 'power3.out', stagger: options.stagger ?? 0.06, clearProps: 'opacity,visibility,transform' });
}

/** Slides content in from the side it is coming from: +1 from the right (older), -1 from the left (newer). */
export function slideAcross(target: Element, direction: number): void {
  gsap.killTweensOf(target);
  if (reducedMotion()) return;
  gsap.fromTo(target, { autoAlpha: 0, x: 32 * Math.sign(direction) },
    { autoAlpha: 1, x: 0, duration: 0.4, ease: 'power3.out', clearProps: 'opacity,visibility,transform' });
}

/** Rocks the landing ship and rolls the waves past it; returns a function that stops them. */
export function rockBoat(svg: Element | null): () => void {
  if (!svg || reducedMotion()) return () => {};
  const boat = svg.querySelector('.boat'), back = svg.querySelector('.wave-back'), front = svg.querySelector('.wave-front');
  const tweens = [
    gsap.fromTo(boat, { rotation: -3, y: 0 }, { rotation: 3, y: 4, svgOrigin: '200 160', duration: 2.4, ease: 'sine.inOut', yoyo: true, repeat: -1 }),
    gsap.fromTo(back, { x: 0 }, { x: -400, duration: 14, ease: 'none', repeat: -1 }),
    gsap.fromTo(front, { x: -400 }, { x: 0, duration: 9, ease: 'none', repeat: -1 }),
  ];
  return () => tweens.forEach((tween) => tween.kill());
}

/** Moves a highlight so it covers `target`, measured relative to their shared positioned parent. */
export function glideTo(indicator: HTMLElement, target: HTMLElement | null, instant = false): void {
  if (!target) {
    gsap.to(indicator, { autoAlpha: 0, duration: instant || reducedMotion() ? 0 : 0.2 });
    return;
  }
  gsap.to(indicator, {
    x: target.offsetLeft,
    y: target.offsetTop,
    width: target.offsetWidth,
    height: target.offsetHeight,
    autoAlpha: 1,
    duration: instant || reducedMotion() ? 0 : 0.35,
    ease: 'power3.out',
    overwrite: true,
  });
}
