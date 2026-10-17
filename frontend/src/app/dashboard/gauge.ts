import { Component, computed, input } from '@angular/core';

// A horizontal meter with the backend's enforced limit marked on the track. Pass/fail is spelled out
// in text next to the value, so state never depends on color alone.
@Component({
  selector: 'app-gauge',
  template: `
    <div class="gauge">
      <div class="gauge-head">
        <span class="gauge-label">{{ label() }}</span>
        <span class="gauge-value">{{ display() }}</span>
      </div>
      <div class="gauge-track" role="meter" [attr.aria-label]="label()" aria-valuemin="0" [attr.aria-valuemax]="max()"
        [attr.aria-valuenow]="value()" [attr.aria-valuetext]="display() + ', ' + verdict()">
        <div class="gauge-fill" [class.gauge-fill-bad]="!passes()" [style.width.%]="fillPercent()"></div>
        <div class="gauge-limit" [style.left.%]="limitPercent()"></div>
      </div>
      <div class="gauge-foot">
        <span [class.gauge-pass]="passes()" [class.gauge-fail]="!passes()">{{ verdict() }}</span>
        <span class="muted">{{ limitText() }}</span>
      </div>
    </div>
  `,
})
export class Gauge {
  readonly label = input.required<string>();
  readonly value = input.required<number>();
  readonly max = input.required<number>();
  readonly limit = input.required<number>();
  // 'max': the value must stay at or below the limit (risk). 'min': at or above it (confidence).
  readonly kind = input.required<'max' | 'min'>();
  readonly percent = input(false);

  readonly passes = computed(() => this.kind() === 'max' ? this.value() <= this.limit() : this.value() >= this.limit());
  readonly fillPercent = computed(() => Math.max(0, Math.min(100, (this.value() / this.max()) * 100)));
  readonly limitPercent = computed(() => Math.max(0, Math.min(100, (this.limit() / this.max()) * 100)));
  readonly display = computed(() => this.format(this.value()) + (this.percent() ? '' : ` / ${this.max()}`));
  readonly verdict = computed(() => this.passes() ? 'Within policy' : 'Outside policy');
  readonly limitText = computed(() => (this.kind() === 'max' ? 'Limit ' : 'Minimum ') + this.format(this.limit()));

  private format(n: number): string {
    return this.percent() ? `${Math.round(n * 100)}%` : `${Math.round(n)}`;
  }
}
