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

/**
 * The sidebar compass: on hover it swings round like a needle finding north and wobbles to rest at 45°,
 * settles back when the pointer leaves, and makes a quick full turn when clicked.
 */
export function swingCompass(compass: Element, kind: 'hover' | 'rest' | 'click'): void {
  if (reducedMotion()) return;
  gsap.killTweensOf(compass);
  if (kind === 'click') {
    gsap.fromTo(compass, { rotation: Number(gsap.getProperty(compass, 'rotation')) || 0 },
      { rotation: '+=360', duration: 0.7, ease: 'power2.inOut', onComplete: () => { gsap.set(compass, { rotation: 0 }); } });
    return;
  }
  gsap.to(compass, kind === 'hover'
    ? { rotation: 45, duration: 1.4, ease: 'elastic.out(1, 0.35)' }
    : { rotation: 0, duration: 1.1, ease: 'elastic.out(1, 0.45)' });
}

/**
 * A wave band far wider than the 400-wide drawing (it spills past the frame and is clipped by its container),
 * so its ends never show even at hover speed; sliding it by 400, four whole wavelengths, loops seamlessly.
 * The water runs down past the drawing so it can fill to the container's bottom edge.
 */
export function wavePath(level: number, height: number): string {
  let d = `M-1600 ${level}`;
  for (let x = -1600; x < 2400; x += 100) d += ` Q${x + 25} ${level - height} ${x + 50} ${level} T${x + 100} ${level}`;
  return `${d} V400 H-1600 Z`;
}

export interface BoatMotion { stop(): void; pace(factor: number): void; }

/** Rocks the landing ship and rolls the waves past it; `pace` eases the whole scene faster or slower. */
export function rockBoat(svg: Element | null): BoatMotion {
  if (!svg || reducedMotion()) return { stop: () => {}, pace: () => {} };
  const boat = svg.querySelector('.boat'), back = svg.querySelector('.wave-back'), front = svg.querySelector('.wave-front');
  // A scene may be waves only (the login screen), so the ship's rocking is added only when it is drawn.
  const tweens = [
    ...(boat ? [gsap.fromTo(boat, { rotation: -3, y: 0 }, { rotation: 3, y: 4, svgOrigin: '200 160', duration: 2.4, ease: 'sine.inOut', yoyo: true, repeat: -1 })] : []),
    gsap.fromTo(back, { x: 0 }, { x: -400, duration: 14, ease: 'none', repeat: -1 }),
    gsap.fromTo(front, { x: -400 }, { x: 0, duration: 9, ease: 'none', repeat: -1 }),
  ];
  return {
    stop: () => tweens.forEach((tween) => tween.kill()),
    pace: (factor) => tweens.forEach((tween) => gsap.to(tween, { timeScale: factor, duration: 0.8, ease: 'power2.out', overwrite: true })),
  };
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
