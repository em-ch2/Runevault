import { Injectable, signal } from '@angular/core';

// Minimal Web Serial typings (Chrome and Edge); TypeScript's DOM library does not ship them yet.
interface SerialPortLike {
  readable: ReadableStream<Uint8Array> | null;
  writable: WritableStream<Uint8Array> | null;
  open(options: { baudRate: number }): Promise<void>;
  close(): Promise<void>;
}
interface SerialLike extends EventTarget {
  getPorts(): Promise<SerialPortLike[]>;
  requestPort(options?: { filters: { usbVendorId: number }[] }): Promise<SerialPortLike>;
}

export type PicoState = 'unsupported' | 'disconnected' | 'connecting' | 'ready';

const RASPBERRY_PI_VENDOR = 0x2e8a;
const encoder = new TextEncoder();

// Speaks the same line protocol as hardware/bridge.py, from the browser: send TX|id|amount|dest, read the
// firmware's "# glyph N" progress lines, and resolve with the Pico's signed JSON line.
@Injectable({ providedIn: 'root' })
export class PicoSerial {
  private readonly serial = (navigator as Navigator & { serial?: SerialLike }).serial;
  readonly state = signal<PicoState>(this.serial ? 'disconnected' : 'unsupported');
  readonly presses = signal<number[]>([]);
  readonly error = signal<string | null>(null);
  private port: SerialPortLike | null = null;
  private reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
  private onReady: (() => void) | null = null;
  private signing: { txId: string; resolve: (line: string) => void; reject: (error: Error) => void } | null = null;

  constructor() {
    this.serial?.addEventListener('disconnect', () => this.dropped('The Pico was unplugged.'));
    this.serial?.addEventListener('connect', () => void this.connect(false));
  }

  // request=false only reopens a port this site was already granted, so it needs no click.
  async connect(request: boolean): Promise<void> {
    if (!this.serial || this.state() === 'connecting' || this.state() === 'ready') return;
    this.state.set('connecting');
    this.error.set(null);
    try {
      const port = (await this.serial.getPorts())[0]
        ?? (request ? await this.serial.requestPort({ filters: [{ usbVendorId: RASPBERRY_PI_VENDOR }] }) : null);
      if (!port) { this.state.set('disconnected'); return; }
      await port.open({ baudRate: 115200 });
      this.port = port;
      void this.readLines(port);
      if (!(await this.restart())) throw new Error('no-ready');
      this.state.set('ready');
    } catch (error) {
      await this.close();
      this.state.set('disconnected');
      if (error instanceof Error && error.name === 'NotFoundError') return; // the picker was dismissed
      this.error.set(error instanceof Error && error.message === 'no-ready'
        ? 'The Pico did not start main.py. Check that the firmware is saved on it as main.py.'
        : 'Could not open the Pico. Close Thonny or the terminal bridge, then try again.');
    }
  }

  // Hands the transfer to the Pico and resolves with its signed approval once Enter is pressed.
  sign(txId: string, amount: number, dest: string): Promise<string> {
    this.signing?.reject(new Error('replaced'));
    this.presses.set([]);
    return new Promise<string>((resolve, reject) => {
      this.signing = { txId, resolve, reject };
      this.write(`TX|${txId}|${amount}|${dest}\n`).catch(() => this.dropped('Lost the connection to the Pico.'));
    });
  }

  // Drops a half-entered transfer, for example when its approval window expires.
  async cancel(): Promise<void> {
    this.signing?.reject(new Error('cancelled'));
    this.signing = null;
    this.presses.set([]);
    if (this.state() === 'ready') await this.restart();
  }

  // Ctrl-C stops main.py, Ctrl-B leaves any raw REPL Thonny left behind, Ctrl-D soft-reboots into main.py.
  private async restart(): Promise<boolean> {
    const ready = new Promise<boolean>((resolve) => {
      const timer = setTimeout(() => { this.onReady = null; resolve(false); }, 6000);
      this.onReady = () => { clearTimeout(timer); this.onReady = null; resolve(true); };
    });
    await this.write('\x03\x03');
    await pause(200);
    await this.write('\x02');
    await pause(200);
    await this.write('\x04');
    return ready;
  }

  private async write(text: string): Promise<void> {
    const writer = this.port?.writable?.getWriter();
    if (!writer) throw new Error('closed');
    try { await writer.write(encoder.encode(text)); } finally { writer.releaseLock(); }
  }

  private async readLines(port: SerialPortLike): Promise<void> {
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      while (port.readable && this.port === port) {
        this.reader = port.readable.getReader();
        try {
          for (;;) {
            const { value, done } = await this.reader.read();
            if (done) return;
            buffer += decoder.decode(value, { stream: true });
            let newline: number;
            while ((newline = buffer.indexOf('\n')) >= 0) {
              this.handle(buffer.slice(0, newline).trim());
              buffer = buffer.slice(newline + 1);
            }
          }
        } finally {
          this.reader.releaseLock();
          this.reader = null;
        }
      }
    } catch {
      if (this.port === port) this.dropped('Lost the connection to the Pico.');
    }
  }

  private handle(line: string): void {
    if (line === '# ready') { this.onReady?.(); return; }
    const glyph = /^# glyph (\d)/.exec(line);
    if (glyph) { this.presses.update((presses) => [...presses, Number(glyph[1])]); return; }
    if (line === '# cleared') { this.presses.set([]); return; }
    if (!line.startsWith('{') || !this.signing) return;
    try {
      if ((JSON.parse(line) as { tx_id?: unknown }).tx_id !== this.signing.txId) return;
    } catch {
      return;
    }
    const signing = this.signing;
    this.signing = null;
    signing.resolve(line);
  }

  private dropped(message: string): void {
    if (this.state() === 'disconnected') return;
    this.signing?.reject(new Error('disconnected'));
    this.signing = null;
    this.error.set(message);
    this.state.set('disconnected');
    void this.close();
  }

  private async close(): Promise<void> {
    const port = this.port;
    this.port = null;
    try { await this.reader?.cancel(); } catch { /* already closed */ }
    try { await port?.close(); } catch { /* already closed */ }
  }
}

function pause(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
