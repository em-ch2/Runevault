from machine import Pin
import hashlib, sys, time, binascii

KEY = b"poneglyph-dev-key-change-me-0001"
DEVICE_ID = "slab-01"
# Index order matches the dashboard: 0 = Will, 1 = Crew, 2 = Sea.
GLYPH_NAMES = ["Will", "Crew", "Sea"]
glyph_buttons = [Pin(8, Pin.IN, Pin.PULL_UP), Pin(4, Pin.IN, Pin.PULL_UP),
                 Pin(3, Pin.IN, Pin.PULL_UP)]
enter_button = Pin(2, Pin.IN, Pin.PULL_UP)
waiting_led = Pin(10, Pin.OUT)
sealed_led = Pin(11, Pin.OUT)

waiting_led.value(1)
sealed_led.value(1)
time.sleep_ms(300)
waiting_led.value(0)
sealed_led.value(0)

try:
    with open("counter.txt") as f:
        counter = int(f.read())
except OSError:
    counter = 0

print("# ready")
while True:
    line = sys.stdin.readline().strip()
    if not line.startswith("TX|"):
        continue
    parts = line.split("|")
    tx_id = parts[1]
    amount = parts[2]
    dest = parts[3]
    print("# enter glyphs for tx " + tx_id)
    waiting_led.value(1)

    glyphs = []
    while True:
        for i in range(len(glyph_buttons)):
            if glyph_buttons[i].value() == 0:
                glyphs.append(str(i))
                print("# glyph " + str(i) + " " + GLYPH_NAMES[i])
                waiting_led.value(0)
                time.sleep_ms(80)
                waiting_led.value(1)
                while glyph_buttons[i].value() == 0:
                    pass
                time.sleep_ms(30)
        if enter_button.value() == 0:
            press_start = time.ticks_ms()
            time.sleep_ms(30)
            while enter_button.value() == 0:
                pass
            held_ms = time.ticks_diff(time.ticks_ms(), press_start)
            if held_ms > 2000:
                glyphs = []
                print("# cleared")
                for blink in range(3):
                    waiting_led.value(0)
                    time.sleep_ms(100)
                    waiting_led.value(1)
                    time.sleep_ms(100)
            elif len(glyphs) > 0:
                break

    waiting_led.value(0)

    counter = counter + 1
    with open("counter.txt", "w") as f:
        f.write(str(counter))

    glyph_str = "-".join(glyphs)
    msg = "v1|" + DEVICE_ID + "|" + str(counter) + "|" + tx_id + "|" + amount + "|" + dest + "|" + glyph_str
    msg_bytes = msg.encode()

    key = KEY
    if len(key) > 64:
        key = hashlib.sha256(key).digest()
    key = key + bytes(64 - len(key))
    ipad = bytes([b ^ 0x36 for b in key])
    opad = bytes([b ^ 0x5C for b in key])
    inner = hashlib.sha256(ipad + msg_bytes).digest()
    sig = binascii.hexlify(hashlib.sha256(opad + inner).digest()).decode()

    print('{"v":1,"device":"' + DEVICE_ID + '","counter":' + str(counter) +
          ',"tx_id":"' + tx_id + '","amount":' + amount + ',"dest":"' + dest +
          '","glyphs":"' + glyph_str + '","sig":"' + sig + '"}')

    sealed_led.value(1)
    time.sleep_ms(1500)
    sealed_led.value(0)
