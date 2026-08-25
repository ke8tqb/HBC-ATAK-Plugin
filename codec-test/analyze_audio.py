import sys
import numpy as np
import soundfile as sf

BASE = r"\\Primary\David\CoT Project\8-19-26 HBC ATAK Plugin"
FILES = {"tab5": BASE + r"\ActiveTab5 PLI.mp3", "s26": BASE + r"\S26 PLI.mp3"}


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
    """NRZI already removed; bits are logical (1=no transition). Find 0x7E flags, unstuff."""
    frames = []
    flag = [0, 1, 1, 1, 1, 1, 1, 0]
    i = 0
    n = len(bits)
    # find flag positions
    flags = []
    for i in range(n - 8):
        if list(bits[i:i + 8]) == flag:
            flags.append(i)
    for a, b in zip(flags, flags[1:]):
        seg = bits[a + 8:b]
        if len(seg) < 8 * 17:
            continue
        # unstuff: drop 0 after five 1s
        out = []
        ones = 0
        ok = True
        j = 0
        while j < len(seg):
            bit = seg[j]
            if ones == 5:
                if bit == 1:
                    ok = False  # abort/flag inside
                    break
                ones = 0
                j += 1
                continue
            out.append(bit)
            ones = ones + 1 if bit == 1 else 0
            j += 1
        if not ok or len(out) % 8 != 0:
            continue
        by = bytearray()
        for k in range(0, len(out), 8):
            v = 0
            for m in range(8):  # LSB first
                v |= out[k + m] << m
            by.append(v)
        if len(by) >= 17 and crc_ok(by):
            frames.append(bytes(by[:-2]))
    return frames


def demod(x, fs, baud=1200.0, f_mark=1200.0, f_space=2200.0):
    """Non-coherent correlator AFSK demod, returns logical bit array after NRZI decode."""
    sps = fs / baud
    t = np.arange(len(x)) / fs
    win = int(round(sps))
    k = np.ones(win) / win

    def corr(f):
        i = x * np.cos(2 * np.pi * f * t)
        q = x * np.sin(2 * np.pi * f * t)
        return np.convolve(i, k, 'same') ** 2 + np.convolve(q, k, 'same') ** 2

    d = corr(f_mark) - corr(f_space)  # >0 => mark
    tone = d > 0
    # sample at symbol centers using transitions to sync
    trans = np.where(np.diff(tone.astype(int)) != 0)[0]
    if len(trans) < 10:
        return []
    bits = []
    # clock recovery: start at first transition, sample every sps with resync at transitions
    pos = trans[0] + sps / 2
    last = None
    ti = 0
    while pos < len(tone):
        s = tone[int(pos)]
        if last is not None:
            bits.append(1 if s == last else 0)  # NRZI: no change = 1
        last = s
        # resync to nearest transition
        while ti < len(trans) and trans[ti] < pos:
            ti += 1
        if ti < len(trans) and abs(trans[ti] - (pos + sps / 2)) < sps / 4:
            pos = trans[ti] + sps / 2
        else:
            pos += sps
    return bits


def analyze(name, path):
    d, fs = sf.read(path)
    if d.ndim > 1:
        chans = [d[:, c] for c in range(d.shape[1])]
        # pick louder channel
        x = max(chans, key=lambda c: np.max(np.abs(c)))
        stereo_diff = float(np.max(np.abs(d[:, 0] - d[:, 1]))) if d.shape[1] == 2 else 0
    else:
        x = d
        stereo_diff = 0
    x = x.astype(np.float64)

    # locate burst via envelope
    env = np.convolve(np.abs(x), np.ones(int(fs * 0.01)) / (fs * 0.01), 'same')
    thr = 0.2 * env.max()
    idx = np.where(env > thr)[0]
    burst = x[idx[0]:idx[-1]]
    dur = len(burst) / fs

    # clipping check
    peak = np.max(np.abs(burst))
    clip_frac = np.mean(np.abs(burst) > 0.985 * peak) if peak > 0 else 0

    # spectrum of burst
    n = len(burst)
    w = np.abs(np.fft.rfft(burst * np.hanning(n))) ** 2
    freqs = np.fft.rfftfreq(n, 1 / fs)
    # peaks near 1200/2200
    def peak_near(f0, bw=300):
        m = (freqs > f0 - bw) & (freqs < f0 + bw)
        return freqs[m][np.argmax(w[m])]

    p1200 = peak_near(1200)
    p2200 = peak_near(2200)

    # energy above 3 kHz (distortion indicator)
    e_total = w.sum()
    e_hi = w[freqs > 3000].sum()
    e_lo = w[freqs < 700].sum()

    print(f"== {name} ==")
    print(f"  fs={fs} dur_burst={dur:.2f}s peak={peak:.3f} clip_frac={clip_frac*100:.2f}%")
    print(f"  stereo L-R max diff: {stereo_diff:.4f}")
    print(f"  tone peaks: mark~{p1200:.1f} Hz  space~{p2200:.1f} Hz")
    print(f"  energy: <700Hz {100*e_lo/e_total:.1f}%  >3kHz {100*e_hi/e_total:.1f}%")

    # try decode across candidate bauds (sample-rate/speed drift check)
    best = None
    for scale in [1.0, 22050/24000, 24000/22050, 44100/48000, 48000/44100,
                  0.98, 0.99, 1.01, 1.02]:
        baud = 1200 * scale
        for fm, fsp in [(1200 * scale, 2200 * scale), (1200, 2200)]:
            bits = demod(burst, fs, baud, fm, fsp)
            frames = hdlc_deframe(bits)
            if frames:
                info = (scale, fm, fsp, len(frames), frames[0])
                if best is None:
                    best = info
                print(f"  DECODED at baud-scale {scale:.4f} tones {fm:.0f}/{fsp:.0f}: "
                      f"{len(frames)} frame(s)")
    if best is None:
        print("  NO DECODE at any tested baud/tone scale")
    else:
        fr = best[4]
        print(f"  first frame {len(fr)} bytes: {fr.hex(' ')}")
    print()
    return best


for name, path in FILES.items():
    analyze(name, path)
