import { Component, computed, input } from '@angular/core';

export const GLYPH_NAMES = ['Key', 'Eye', 'Gate'];

@Component({
  selector: 'app-glyph',
  template: `
    <svg
      viewBox="0 0 60 80"
      [attr.width]="size()"
      [attr.height]="(size() * 4) / 3"
      role="img"
      [attr.aria-label]="name()"
    >
      <title>{{ name() }}</title>
      <path class="cartouche" d="M8 2 H52 L58 8 V36 H55 V44 H58 V72 L52 78 H8 L2 72 V44 H5 V36 H2 V8 Z" />
      <path class="cartouche-inner" d="M11 7 H49 L53 11 V69 L49 73 H11 L7 69 V11 Z" />
      @if (id() === 0) {
        <path class="stroke" d="M30 16 L38 24 L30 32 L22 24 Z M27 24 H33 M30 32 V62 M30 52 H38 M30 60 H36" />
      }
      @if (id() === 1) {
        <path
          class="stroke"
          d="M14 40 L22 32 H38 L46 40 L38 48 H22 Z M30 35 L35 40 L30 45 L25 40 Z M30 19 V26 M20 23 L23 28 M40 23 L37 28 M30 54 V61"
        />
      }
      @if (id() === 2) {
        <path class="stroke" d="M14 21 H46 M18 27 H42 M21 27 V62 M39 27 V62 M14 62 H46 M26 62 V40 H34 V62" />
      }
    </svg>
  `,
  host: { class: 'glyph' },
})
export class Glyph {
  readonly id = input.required<number>();
  readonly size = input<number>(56);
  readonly name = computed(() => GLYPH_NAMES[this.id()] ?? 'Unknown');
}
