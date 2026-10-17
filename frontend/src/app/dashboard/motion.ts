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
