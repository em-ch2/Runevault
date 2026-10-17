import { AfterViewInit, Component, ElementRef, Input, OnChanges, OnDestroy, ViewChild, output } from '@angular/core';
import { gsap } from 'gsap';

@Component({
  selector: 'app-folder-panel',
  host: { '[class.folder-open]': 'open' },
  template: `
    <h2 class="folder-heading">
      @if (!collapsible) {
        <span class="flow-panel-title" [id]="panelId">{{ title }}</span>
      } @else {
      <button #tab class="folder-tab" type="button" [id]="panelId"
        [attr.aria-expanded]="open" [attr.aria-controls]="panelId + '-body'" (click)="toggled.emit()">
        <span>{{ title }}</span>
        <svg class="folder-action" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
          <path d="M16 2 L20 12 L30 16 L20 20 L16 30 L12 20 L2 16 L12 12 Z"
            [attr.fill]="open ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="1.5" />
        </svg>
      </button>
      }
    </h2>
    <div #body class="folder-body" [id]="panelId + '-body'" role="region"
      [attr.aria-labelledby]="panelId" [attr.aria-hidden]="!open" [attr.inert]="open ? null : ''">
      <div class="folder-content"><ng-content /></div>
    </div>
  `,
})
export class FolderPanel implements AfterViewInit, OnChanges, OnDestroy {
  @Input({ required: true }) panelId = '';
  @Input({ required: true }) title = '';
  @Input() open = false;
  @Input() collapsible = true;
  readonly toggled = output<void>();
  @ViewChild('body') private body?: ElementRef<HTMLDivElement>;
  @ViewChild('tab') private tab?: ElementRef<HTMLButtonElement>;
  private animation?: gsap.core.Tween;

  ngAfterViewInit(): void {
    gsap.set(this.body!.nativeElement, { height: this.open ? 'auto' : 0, opacity: this.open ? 1 : 0 });
  }

  ngOnChanges(): void {
    const body = this.body?.nativeElement;
    if (!body) return;
    this.animation?.kill();
    if (!this.open && body.contains(document.activeElement)) this.tab?.nativeElement.focus();
    const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
    this.animation = gsap.to(body, {
      height: this.open ? 'auto' : 0,
      opacity: this.open ? 1 : 0,
      duration: reducedMotion ? 0 : 0.38,
      ease: 'power2.inOut',
      overwrite: true,
    });
  }

  ngOnDestroy(): void { this.animation?.kill(); }
}
