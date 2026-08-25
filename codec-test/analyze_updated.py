import numpy as np
from scipy.signal import spectrogram

fs = 48000
x = np.load(r'C:\Users\david\dev\updated_settings.npy')
x = x[0] if x.ndim > 1 else x
x = x.astype(np.float64)

# ---- burst segmentation ----
win = int(fs * 0.02)
env = np.convolve(np.abs(x), np.ones(win) / win, 'same')
on = env > 0.15 * env.max()
di = np.diff(on.astype(int))
starts = list(np.where(di == 1)[0]); ends = list(np.where(di == -1)[0])
if on[0]: starts = [0] + starts
if on[-1]: ends = ends + [len(on) - 1]
bursts = []
for s, e in zip(starts, ends):
    if bursts and (s - bursts[-1][1]) / fs < 0.15:
        bursts[-1] = (bursts[-1][0], e)
    else:
        bursts.append((s, e))
bursts = [(s, e) for s, e in bursts if (e - s) / fs > 0.2]
print(f"file {len(x)/fs:.2f}s, {len(bursts)} burst(s): "
      + ", ".join(f"{(e-s)/fs:.3f}s" for s, e in bursts))

# ---- decode helpers (same as before) ----
def crc_ok(frame):
    crc = 0xFFFF
    tab = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ 0x8408 if c & 1 else c >> 1
        tab.append(c)
    for b in frame:
        crc = (crc >> 8) ^ tab[(crc ^ b) & 0xFF]
    return crc == 0xF0B8

def hdlc_deframe(bits):
    frames = []
    flag = [0, 1, 1, 1, 1, 1, 1, 0]
    flags = [i for i in range(len(bits) - 8) if list(bits[i:i+8]) == flag]
    for a, b in zip(flags, flags[1:]):
        seg = bits[a+8:b]
        if len(seg) < 8 * 17: continue
        out, ones, ok, j = [], 0, True, 0
        while j < len(seg):
            bit = seg[j]
            if ones == 5:
                if bit == 1: ok = False; break
                ones = 0; j += 1; continue
            out.append(bit); ones = ones + 1 if bit == 1 else 0; j += 1
        if not ok or len(out) % 8 != 0: continue
        by = bytearray()
        for k in range(0, len(out), 8):
            v = 0
            for m in range(8): v |= out[k+m] << m
            by.append(v)
        if len(by) >= 17 and crc_ok(by):
            frames.append(bytes(by[:-2]))
    return frames

def demod(xx, baud=1200.0, fm=1200.0, fsp=2200.0):
    sps = fs / baud
    t = np.arange(len(xx)) / fs
    k = np.ones(int(round(sps))) / int(round(sps))
    def corr(f):
        i = xx * np.cos(2*np.pi*f*t); q = xx * np.sin(2*np.pi*f*t)
        return np.convolve(i, k, 'same')**2 + np.convolve(q, k, 'same')**2
    d = corr(fm) - corr(fsp)
    tone = d > 0
    tr = np.where(np.diff(tone.astype(int)) != 0)[0]
    if len(tr) < 10: return []
    bits, pos, last, ti = [], tr[0] + sps/2, None, 0
    while pos < len(tone):
        s = tone[int(pos)]
        if last is not None: bits.append(1 if s == last else 0)
        last = s
        while ti < len(tr) and tr[ti] < pos: ti += 1
        if ti < len(tr) and abs(tr[ti] - (pos + sps/2)) < sps/4:
            pos = tr[ti] + sps/2
        else:
            pos += sps
    return bits

for bi, (s, e) in enumerate(bursts):
    pad = int(fs * 0.05)
    seg = x[max(0, s-pad):e+pad]
    # dominant tone clusters
    f, t, S = spectrogram(seg, fs, nperseg=256, noverlap=192)
    band = (f > 600) & (f < 3200); fb = f[band]
    dom = fb[np.argmax(S[band], axis=0)]
    mark_frames = int(np.sum((dom > 900) & (dom < 1400)))
    space_frames = int(np.sum((dom > 1900) & (dom < 2500)))
    frame_dt = t[1] - t[0]
    print(f"burst {bi}: dur {(e-s)/fs:.3f}s  mark-tone time {mark_frames*frame_dt*1000:.0f}ms "
          f" space-tone time {space_frames*frame_dt*1000:.0f}ms")
    decoded = False
    for scale in [1.0, 0.99, 1.01, 0.98, 1.02, 0.97, 1.03]:
        bits = demod(seg, 1200*scale, 1200, 2200)
        fr = hdlc_deframe(bits)
        if fr:
            print(f"   DECODED (baud scale {scale}): {len(fr)} frame(s), "
                  f"first {len(fr[0])}B: {fr[0].hex(' ')}")
            decoded = True
            break
    if not decoded:
        print("   no CRC-valid decode (offline demod)")
