import { Component, computed, input } from '@angular/core';

export const GLYPH_NAMES = ['Will', 'Crew', 'Sea'];

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
        <!-- Will: a spiral fruit whose stem curls into a small loop. -->
        <path
          class="stroke"
          d="M30 35 A13 13 0 0 1 30 61 A10.5 10.5 0 0 1 30 40 A8 8 0 0 1 30 56 A5.5 5.5 0 0 1 30 45 M30 35 C30 28 32 24 35.5 22.5 M41.5 21.5 A3 3 0 1 1 35.5 21.5 A3 3 0 1 1 41.5 21.5 Z"
        />
      }
      @if (id() === 1) {
        <!-- Crew: a horizon-crossed circle over crossbones; one bone drawn on each diagonal. -->
        <path class="stroke" d="M21 42 A9 9 0 1 1 39 42 A9 9 0 1 1 21 42 Z M12 42 H48" />
        @for (turn of [0, 90, 180, 270]; track turn) {
          <path
            class="stroke"
            [attr.transform]="'rotate(' + turn + ' 30 42)'"
            d="M22.9 34.9 L16.6 28.6 M19.2 26.3 A1.7 1.7 0 1 0 15.8 26.3 A1.7 1.7 0 1 0 19.2 26.3 M16 29.5 A1.7 1.7 0 1 0 12.6 29.5 A1.7 1.7 0 1 0 16 29.5"
          />
        }
      }
      @if (id() === 2) {
        <!-- Sea: a curling wave with a swell beneath it. -->
        <path
          class="stroke"
          d="M11 45 C18 45 22 33 32 32 C40 31.5 44.5 37.5 41 41.5 C38 44.5 33.5 42 35.5 38.5 M30 43 C35 49 42 50 49 46.5 M12 51 C18 51 22 48 25.5 44"
        />
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
