import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy.signal import spectrogram, hilbert, butter, filtfilt

fs = 48000
OUT = r"C:\Users\david\dev\hbc_tone_compare.png"

# ---------- ideal synthetic AFSK1200 ----------
def synth_afsk(nflags=30, ndata=37, fs=48000):
    rng = np.random.default_rng(1)
    data = bytes([0x7E] * nflags) + bytes(rng.integers(0, 256, ndata)) + bytes([0x7E] * 2)
    # NRZI + bit stuffing (stuff only data region approximated: fine for illustration)
    bits = []
    for i, byte in enumerate(data):
        for k in range(8):
            bits.append((byte >> k) & 1)
    tone = 0
    sym = []
    ones = 0
    for i, b in enumerate(bits):
        if b == 0:
            tone ^= 1
            ones = 0
        else:
            ones += 1
        sym.append(tone)
        # stuffing not needed for illustration
    sps = fs / 1200.0
    phase = 0.0
    out = []
    for s in sym:
        f = 2200.0 if s else 1200.0
        n = int(round(sps))
        t = np.arange(n)
        seg = np.sin(phase + 2 * np.pi * f * t / fs)
        phase += 2 * np.pi * f * n / fs
        out.append(seg)
    return np.concatenate(out)

ideal = synth_afsk()

# ---------- real captures ----------
tab5 = np.fromfile(r"C:\Users\david\dev\s26_rx_tab5_f32.raw", dtype=np.float32).astype(np.float64)
s26 = np.fromfile(r"C:\Users\david\dev\updated_settings_f32.raw", dtype=np.float32).astype(np.float64)

def burst(x, fs):
    win = int(fs * 0.02)
    env = np.convolve(np.abs(x), np.ones(win) / win, "same")
    idx = np.where(env > 0.2 * env.max())[0]
    return x[max(0, idx[0] - int(0.05 * fs)):idx[-1] + int(0.05 * fs)]

tab5_b = burst(tab5, fs)
s26_b = burst(s26, fs)

def instfreq(x, fs):
    b, a = butter(4, [700 / (fs / 2), 3200 / (fs / 2)], btype="band")
    xf = filtfilt(b, a, x)
    z = hilbert(xf)
    f = np.diff(np.unwrap(np.angle(z))) * fs / (2 * np.pi)
    return np.clip(f, 0, 4000)

signals = [
    ("EXPECTED (ideal AFSK1200)", ideal),
    ("Tab Active5 TX  \u2014  DECODES (heard at S26 position)", tab5_b),
    ("S26 TX (updated settings)  \u2014  DOES NOT DECODE", s26_b),
]

fig, axes = plt.subplots(3, 2, figsize=(16, 11))
for row, (title, x) in enumerate(signals):
    # spectrogram
    f, t, S = spectrogram(x, fs, nperseg=512, noverlap=448)
    m = f < 3500
    axS = axes[row, 0]
    axS.pcolormesh(t, f[m], 10 * np.log10(S[m] + 1e-12), shading="auto", cmap="magma")
    axS.axhline(1200, color="cyan", lw=0.8, ls="--")
    axS.axhline(2200, color="lime", lw=0.8, ls="--")
    axS.set_title(title + "  \u2014 spectrogram (dashed = ideal 1200/2200 Hz)")
    axS.set_ylabel("Hz")
    if row == 2:
        axS.set_xlabel("time (s)")

    # zoomed instantaneous frequency ~60 ms mid-burst
    fi = instfreq(x, fs)
    mid = len(fi) // 2
    span = int(0.06 * fs)
    seg = fi[mid - span // 2: mid + span // 2]
    tt = np.arange(len(seg)) / fs * 1000
    axF = axes[row, 1]
    axF.plot(tt, seg, lw=0.6, color="tab:blue")
    axF.axhline(1200, color="cyan", lw=1, ls="--", label="mark 1200")
    axF.axhline(2200, color="green", lw=1, ls="--", label="space 2200")
    axF.set_ylim(400, 3400)
    axF.set_title("instantaneous frequency, 60 ms zoom (should be clean steps 1200\u21942200)")
    axF.set_ylabel("Hz")
    if row == 0:
        axF.legend(loc="upper right", fontsize=8)
    if row == 2:
        axF.set_xlabel("time (ms)")

plt.tight_layout()
plt.savefig(OUT, dpi=110)
print("saved", OUT)
