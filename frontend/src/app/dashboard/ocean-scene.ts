import { Component, ElementRef, OnDestroy, OnInit, afterNextRender, inject, input } from '@angular/core';
import { BoatMotion, rockBoat, wavePath } from './motion';

// The rocking ship on rolling waves, for screens outside the dashboard (login, sign-up). Same drawing and
// motion as the landing page's hero; `pace` speeds the scene up, for example while it is hovered.
@Component({
  selector: 'app-ocean-scene',
  template: `
    <svg class="landing-boat ocean-scene" viewBox="0 0 400 230" preserveAspectRatio="xMidYMax meet" role="img" aria-label="A ship rocking on the waves">
      <g class="wave wave-back"><path [attr.d]="back" /></g>
      @if (boat()) {
        <g class="boat">
          <path class="boat-flag" d="M200 44 L220 50 L200 56 Z" />
          <line class="boat-mast" x1="200" y1="44" x2="200" y2="134" />
          <path class="boat-sail" d="M205 58 L205 126 L258 126 Z" />
          <path class="boat-sail" d="M195 64 L195 126 L150 126 Z" />
          <path class="boat-hull" d="M124 134 H276 L252 166 H148 Z" />
          <circle class="boat-port" cx="176" cy="150" r="3.5" /><circle class="boat-port" cx="200" cy="150" r="3.5" /><circle class="boat-port" cx="224" cy="150" r="3.5" />
        </g>
      }
      <g class="wave wave-front"><path [attr.d]="front" /></g>
    </svg>
  `,
})
export class OceanScene implements OnInit, OnDestroy {
  readonly boat = input(true);
  // With no ship to sit in them, the waves rise higher and swell taller so the sea fills the scene.
  back = wavePath(156, 7);
  front = wavePath(166, 8);
  private motion: BoatMotion | null = null;

  ngOnInit(): void {
    if (this.boat()) return;
    this.back = wavePath(84, 10);
    this.front = wavePath(112, 12);
  }

  constructor() {
    const host: ElementRef<HTMLElement> = inject(ElementRef);
    afterNextRender(() => { this.motion = rockBoat(host.nativeElement.querySelector('svg')); });
  }

  pace(factor: number): void { this.motion?.pace(factor); }

  ngOnDestroy(): void { this.motion?.stop(); }
}
