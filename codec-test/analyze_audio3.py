import numpy as np
import soundfile as sf
from scipy.signal import spectrogram

BASE = r"\\Primary\David\CoT Project\8-19-26 HBC ATAK Plugin"
FILES = {"tab5": BASE + r"\ActiveTab5 PLI.mp3", "s26": BASE + r"\S26 PLI.mp3"}

results = {}
for name, path in FILES.items():
    d, fs = sf.read(path)
    x = (d[:, 0] if d.ndim > 1 else d).astype(np.float64)

    # envelope + burst segmentation
    win = int(fs * 0.02)
    env = np.convolve(np.abs(x), np.ones(win) / win, 'same')
    on = env > 0.15 * env.max()
    # close small gaps
    di = np.diff(on.astype(int))
    starts = list(np.where(di == 1)[0])
    ends = list(np.where(di == -1)[0])
    if on[0]: starts = [0] + starts
    if on[-1]: ends = ends + [len(on) - 1]
    bursts = []
    for s, e in zip(starts, ends):
        if (e - s) / fs > 0.2:
            if bursts and (s - bursts[-1][1]) / fs < 0.1:
                bursts[-1] = (bursts[-1][0], e)
            else:
                bursts.append((s, e))
    print(f"== {name} ==  file {len(x)/fs:.2f}s, {len(bursts)} burst(s): "
          + ", ".join(f"{(e-s)/fs:.3f}s" for s, e in bursts))

    s, e = max(bursts, key=lambda b: b[1] - b[0])
    seg = x[s:e]

    # spectrogram dominant tone per frame
    f, t, S = spectrogram(seg, fs, nperseg=256, noverlap=192)
    band = (f > 600) & (f < 3200)
    fb = f[band]
    dom = fb[np.argmax(S[band], axis=0)]
    hist, edges = np.histogram(dom, bins=fb.size, range=(600, 3200))
    c = (edges[:-1] + edges[1:]) / 2
    order = np.argsort(hist)[::-1]
    # report top clusters
    tops = []
    used = np.zeros(len(c), bool)
    for i in order:
        if used[i] or hist[i] == 0: continue
        m = (c > c[i] - 120) & (c < c[i] + 120)
        tops.append((float(np.average(c[m], weights=hist[m] + 1e-9)), int(hist[m].sum())))
        used |= m
        if len(tops) == 3: break
    print("   dominant tone clusters (Hz, frames): "
          + ", ".join(f"{f0:.0f} ({n})" for f0, n in tops))

    # average log-frequency spectrum for cross-device comparison
    n = len(seg)
    W = np.abs(np.fft.rfft(seg * np.hanning(n))) ** 2
    fr = np.fft.rfftfreq(n, 1 / fs)
    logf = np.linspace(np.log(500), np.log(3500), 4000)
    Wl = np.interp(logf, np.log(fr[1:]), W[1:])
    Wl = np.log10(Wl + 1e-12)
    Wl -= Wl.mean()
    results[name] = (Wl, logf, (e - s) / fs)

# cross-correlate log spectra: shift in log f = log(scale)
a, la, dur_a = results["tab5"]
b, lb, dur_b = results["s26"]
xc = np.correlate(b, a, 'full')
lag = np.argmax(xc) - (len(a) - 1)
dlog = lag * (la[1] - la[0])
print(f"\nSpectral scale s26 vs tab5: x{np.exp(dlog):.4f} "
      f"(1.0 = same pitch; 1.088 would be 48000/44100)")
print(f"Burst duration tab5 {dur_a:.3f}s vs s26 {dur_b:.3f}s "
      f"(ratio {dur_b/dur_a:.4f}; same payload+dwell should be 1.0)")
