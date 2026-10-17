import { Component, computed, input } from '@angular/core';

export const GLYPH_NAMES = ['Dawn', 'Will', 'Freedom'];

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
        <!-- Dawn: a sun on the horizon with five rays. -->
        <path
          class="stroke"
          d="M20 42 A10 10 0 1 1 40 42 A10 10 0 1 1 20 42 Z M11 44 H49 M30 22 V28 M17 29 L20.5 32.5 M43 29 L39.5 32.5 M17 55 L20.5 51.5 M43 55 L39.5 51.5"
        />
      }
      @if (id() === 1) {
        <!-- Will: a spiral fruit whose stem curls into a small loop. -->
        <path
          class="stroke"
          d="M30 35 A13 13 0 0 1 30 61 A10.5 10.5 0 0 1 30 40 A8 8 0 0 1 30 56 A5.5 5.5 0 0 1 30 45 M30 35 C30 28 32 24 35.5 22.5 M41.5 21.5 A3 3 0 1 1 35.5 21.5 A3 3 0 1 1 41.5 21.5 Z"
        />
      }
      @if (id() === 2) {
        <!-- Freedom: a triskelion, one spiral arm drawn three times around the centre. -->
        @for (turn of [0, 120, 240]; track turn) {
          <path
            class="stroke"
            [attr.transform]="'rotate(' + turn + ' 30 44)'"
            d="M30 44 C24.5 39 23 34.5 24.5 29.5 A6 6 0 0 1 36.5 29.5 A4 4 0 0 1 28.5 29.5"
          />
        }
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
