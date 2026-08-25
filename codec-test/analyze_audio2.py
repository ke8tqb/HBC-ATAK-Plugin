import numpy as np
import soundfile as sf
from scipy.signal import hilbert, butter, filtfilt

BASE = r"\\Primary\David\CoT Project\8-19-26 HBC ATAK Plugin"
FILES = {"tab5": BASE + r"\ActiveTab5 PLI.mp3", "s26": BASE + r"\S26 PLI.mp3"}

for name, path in FILES.items():
    d, fs = sf.read(path)
    x = d[:, 0] if d.ndim > 1 else d
    x = x.astype(np.float64)

    env = np.convolve(np.abs(x), np.ones(int(fs * 0.01)) / (fs * 0.01), 'same')
    thr = 0.25 * env.max()
    idx = np.where(env > thr)[0]
    x = x[idx[0]:idx[-1]]

    # bandpass 700-3000 Hz to clean up before Hilbert
    b, a = butter(4, [700 / (fs / 2), 3000 / (fs / 2)], btype='band')
    xf = filtfilt(b, a, x)

    z = hilbert(xf)
    phase = np.unwrap(np.angle(z))
    instf = np.diff(phase) * fs / (2 * np.pi)
    amp = np.abs(z)[:-1]
    m = amp > 0.3 * np.median(amp[amp > 0])
    f = instf[m]
    f = f[(f > 500) & (f < 3500)]

    hist, edges = np.histogram(f, bins=300, range=(500, 3500))
    centers = (edges[:-1] + edges[1:]) / 2
    # two biggest clusters: below and above 1700
    lo = centers[centers < 1750][np.argmax(hist[centers < 1750])]
    hi = centers[centers >= 1750][np.argmax(hist[centers >= 1750])]
    lo_mean = np.mean(f[(f > lo - 150) & (f < lo + 150)])
    hi_mean = np.mean(f[(f > hi - 150) & (f < hi + 150)])

    # baud estimate: durations between mark/space switches
    tone = instf > 1700
    tone = np.convolve(tone.astype(float), np.ones(9) / 9, 'same') > 0.5
    tr = np.where(np.diff(tone.astype(int)) != 0)[0]
    dt = np.diff(tr) / fs
    dt = dt[(dt > 0.0004) & (dt < 0.01)]
    # shortest common interval ~ 1 symbol
    if len(dt):
        base = np.percentile(dt, 10)
        # refine: round each dur to nearest multiple of base and average
        k = np.round(dt / base)
        k[k == 0] = 1
        base_ref = np.mean(dt / k)
        baud = 1.0 / base_ref
    else:
        baud = float('nan')

    print(f"== {name} ==")
    print(f"  mark tone  ~ {lo_mean:7.1f} Hz (expect 1200)")
    print(f"  space tone ~ {hi_mean:7.1f} Hz (expect 2200)")
    print(f"  tone ratios vs nominal: mark x{lo_mean/1200:.4f}  space x{hi_mean/2200:.4f}")
    print(f"  est. baud  ~ {baud:7.1f} (expect 1200)  scale x{baud/1200:.4f}")
    print()
