import { Component, computed, input, signal } from '@angular/core';
import { MinutePoint } from '../transactions/transaction-api.service';

const W = 640;
const H = 190;
const PAD = { left: 30, right: 10, top: 14, bottom: 24 };

interface Bar { d: string; value: number }
interface Group { index: number; x: number; width: number; approved: Bar; blocked: Bar; label: string; point: MinutePoint }

// Bar with 4px rounded data-end, flat on the baseline.
function bar(x: number, width: number, top: number, base: number, value: number): Bar {
  if (value <= 0) return { d: '', value };
  const r = Math.min(4, width / 2, base - top);
  return { d: `M${x},${base} V${top + r} Q${x},${top} ${x + r},${top} H${x + width - r} Q${x + width},${top} ${x + width},${top + r} V${base} Z`, value };
}

function minuteLabel(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
}

// Approvals versus blocks per minute (Tiger Data continuous aggregate via /api/metrics/pipeline).
@Component({
  selector: 'app-approvals-chart',
  template: `
    <div class="chart-legend" aria-hidden="true">
      <span><i class="swatch swatch-approved"></i>Approved <strong>{{ totals().approved }}</strong></span>
      <span><i class="swatch swatch-blocked"></i>Blocked <strong>{{ totals().blocked }}</strong></span>
      <span class="muted chart-readout">
        @if (hovered(); as g) { {{ g.label }} · {{ g.point.approved }} approved, {{ g.point.blocked }} blocked }
        @else { Per minute, last {{ points().length }} minutes }
      </span>
    </div>
    <div class="chart-scroll">
      <svg class="chart" [attr.viewBox]="'0 0 ' + width + ' ' + height" role="img"
        [attr.aria-label]="'Approved versus blocked transfers per minute: ' + totals().approved + ' approved, ' + totals().blocked + ' blocked'"
        (mouseleave)="hoverIndex.set(null)">
        @for (t of ticks(); track t.value) {
          <line class="chart-grid" [attr.x1]="pad.left" [attr.x2]="width - pad.right" [attr.y1]="t.y" [attr.y2]="t.y" />
          <text class="chart-tick" [attr.x]="pad.left - 6" [attr.y]="t.y + 3" text-anchor="end">{{ t.value }}</text>
        }
        @for (g of groups(); track g.index) {
          <g [class.chart-dim]="hoverIndex() !== null && hoverIndex() !== g.index">
            @if (g.approved.d) { <path class="chart-bar-approved" [attr.d]="g.approved.d" /> }
            @if (g.blocked.d) { <path class="chart-bar-blocked" [attr.d]="g.blocked.d" /> }
          </g>
          @if (g.index % 5 === 0 || g.index === groups().length - 1) {
            <text class="chart-tick" [attr.x]="g.x + g.width / 2" [attr.y]="height - 6" text-anchor="middle">{{ g.label }}</text>
          }
          <rect class="chart-hit" [attr.x]="g.x" [attr.y]="pad.top" [attr.width]="g.width"
            [attr.height]="height - pad.top - pad.bottom" (mouseenter)="hoverIndex.set(g.index)" />
        }
        <line class="chart-axis" [attr.x1]="pad.left" [attr.x2]="width - pad.right" [attr.y1]="baseline" [attr.y2]="baseline" />
      </svg>
    </div>
    @if (totals().approved + totals().blocked === 0) {
      <p class="muted small">No approvals or blocks recorded in this window yet.</p>
    }
    <details class="chart-table">
      <summary>Show as table</summary>
      <table>
        <thead><tr><th scope="col">Minute</th><th scope="col">Approved</th><th scope="col">Blocked</th></tr></thead>
        <tbody>
          @for (g of activeGroups(); track g.index) {
            <tr><td>{{ g.label }}</td><td>{{ g.point.approved }}</td><td>{{ g.point.blocked }}</td></tr>
          } @empty {
            <tr><td colspan="3">No activity in this window.</td></tr>
          }
        </tbody>
      </table>
    </details>
  `,
})
export class ApprovalsChart {
  readonly points = input.required<MinutePoint[]>();
  readonly hoverIndex = signal<number | null>(null);
  readonly width = W;
  readonly height = H;
  readonly pad = PAD;
  readonly baseline = H - PAD.bottom;

  readonly yMax = computed(() => {
    const peak = Math.max(1, ...this.points().map((p) => Math.max(p.approved, p.blocked)));
    return peak <= 4 ? peak : Math.ceil(peak / 2) * 2;
  });

  readonly ticks = computed(() => {
    const max = this.yMax();
    const step = max <= 4 ? 1 : max / 2;
    const ticks: { value: number; y: number }[] = [];
    for (let v = 0; v <= max; v += step) ticks.push({ value: v, y: this.y(v) });
    return ticks;
  });

  readonly groups = computed<Group[]>(() => {
    const pts = this.points();
    const plot = W - PAD.left - PAD.right;
    const groupWidth = plot / Math.max(1, pts.length);
    const barWidth = Math.max(2, Math.min(10, (groupWidth - 6) / 2));
    return pts.map((point, index) => {
      const x = PAD.left + index * groupWidth;
      const left = x + (groupWidth - (barWidth * 2 + 2)) / 2;
      return {
        index, x, width: groupWidth, point, label: minuteLabel(point.minute),
        approved: bar(left, barWidth, this.y(point.approved), this.baseline, point.approved),
        blocked: bar(left + barWidth + 2, barWidth, this.y(point.blocked), this.baseline, point.blocked),
      };
    });
  });

  readonly activeGroups = computed(() => this.groups().filter((g) => g.point.approved + g.point.blocked > 0));
  readonly hovered = computed(() => { const i = this.hoverIndex(); return i === null ? null : this.groups()[i] ?? null; });
  readonly totals = computed(() => this.points().reduce(
    (t, p) => ({ approved: t.approved + p.approved, blocked: t.blocked + p.blocked }), { approved: 0, blocked: 0 }));

  private y(value: number): number {
    return this.baseline - (value / this.yMax()) * (this.baseline - PAD.top);
  }
}
