#!/usr/bin/env python3
"""Builds the drum-kit samples shipped with the app (android/drums/src/main/assets/kits/<KIT>/*.wav).

    tools/build-kits.py [--uploads DIR]

1. Master-levels the factory 606 / 808 / 909 kits (in place, idempotent). Every sample used to be peak-normalised
   to 0 dBFS, so a kick, a snare and a hat together went well past full scale before any knob was touched.
   Each sample now gets a loudness target that depends on its role (kick, snare, tom, clap, rim, hats,
   cymbal, bass hit): a peak ceiling and an RMS ceiling, whichever is lower, so a whole pattern at the default
   settings peaks around -6 dBFS and every kit sits at a similar level.
2. Builds the six extra kits that DRUMS 2 runs (FRC, HTK, DNB, FCK, BAS, MIX) from the user's sample packs
   (kicks, a snare, bass hits and hat / shaker / percussion loops that are sliced into one-shots), converted to
   mono 16-bit 44.1 kHz, trimmed, faded and levelled by the same rules.

Needs ffmpeg and numpy. Without --uploads only step 1 runs.
"""
import argparse, glob, os, subprocess, sys, wave
import numpy as np

FS = 44100
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "android", "drums", "src", "main", "assets", "kits")

# role -> (peak ceiling dBFS, RMS ceiling dBFS measured over the first 250 ms)
ROLES = {
    "BD": (-3.0, -12.5),
    "SD": (-6.0, -16.0),
    "TM": (-7.0, -16.5),
    "CP": (-7.5, -18.0),
    "RS": (-10.0, -17.0),
    "CH": (-13.0, -21.0),
    "OH": (-12.0, -22.0),
    "CY": (-13.5, -24.0),
    "BASS": (-6.0, -17.0),
}
FACTORY = {  # file stem -> role
    "Bass": "BD", "Snare": "SD", "Tom": "TM", "Tom_Lo": "TM", "Tom_Hi": "TM", "Claps": "CP", "Rim": "RS",
    "HH_Closed": "CH", "HH_Open": "OH", "Cymbal": "CY", "Ride": "CY",
}


def db(x):
    return 20 * np.log10(max(x, 1e-9))


def level(a, role):
    """Scales [a] so that it respects the role's peak and RMS ceilings."""
    peak_t, rms_t = ROLES[role]
    peak = np.abs(a).max()
    head = a[: int(0.25 * FS)]
    rms = np.sqrt(np.mean(head ** 2))
    gain_db = min(peak_t - db(peak), rms_t - db(rms))
    return a * 10 ** (gain_db / 20)


def write(path, a):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    pcm = np.clip(np.round(a * 32767), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(FS)
        w.writeframes(pcm.tobytes())


def read_factory(path):
    with wave.open(path) as w:
        assert w.getsampwidth() == 2 and w.getframerate() == FS, path
        a = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768
        if w.getnchannels() == 2:
            a = a.reshape(-1, 2).mean(1)
    return a


def master_factory():
    for kit in ("606", "808", "909"):
        for f in sorted(glob.glob(os.path.join(ROOT, kit, "*.wav"))):
            stem = os.path.splitext(os.path.basename(f))[0]
            a = read_factory(f)
            b = level(a, FACTORY[stem])
            write(f, b)
            print("%s/%-10s %-2s  peak %6.1f -> %6.1f dBFS" % (kit, stem, FACTORY[stem], db(np.abs(a).max()), db(np.abs(b).max())))


# ─── The extra kits ───────────────────────────────────────

def load(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(FS), "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f4").astype(np.float64)


def find(uploads, name, nth=0):
    """The nth distinct file called [name] (uploads are prefixed with a hash; the same name can hold two takes)."""
    seen, out = set(), []
    for f in sorted(glob.glob(os.path.join(uploads, "*-" + name + ".wav"))):
        a = load(f)
        key = round(float(np.abs(a).sum()), 3)
        if key not in seen:
            seen.add(key); out.append(a)
    return out[nth]


def start_at_attack(a):
    """Drops the silence before the first sound."""
    idx = np.where(np.abs(a) > np.abs(a).max() * 0.02)[0]
    s = max(0, (idx[0] if len(idx) else 0) - 24)
    return a[s:]


def cut(a, seconds, fade=0.02, start=0.0):
    """[seconds] of [a] from [start], with a short fade in and a fade out of [fade] seconds."""
    s = int(start * FS)
    b = a[s: s + int(seconds * FS)].copy()
    n_in = min(len(b), int(0.002 * FS))
    b[:n_in] *= np.linspace(0, 1, n_in)
    n_out = min(len(b), int(fade * FS))
    b[len(b) - n_out:] *= np.linspace(1, 0, n_out) ** 1.5
    return b


def pitch(a, semitones):
    """Resamples so the sample sounds [semitones] higher (and shorter)."""
    ratio = 2 ** (semitones / 12)
    x = np.arange(0, len(a) - 1, ratio)
    return np.interp(x, np.arange(len(a)), a)


def kicks(a, seconds):
    return cut(start_at_attack(a), seconds, fade=min(0.08, seconds / 3))


def build_extra(uploads):
    L = lambda n, i=0: find(uploads, n, i)
    hat, shaker = L("Hi_Hat_Loop_150bpm"), L("Shaker_Loop_140bpm")
    para, blank = L("PARASITE_Perc_Loop_160bpm"), L("BLANK_Perc_Loop_160bpm")
    snare = start_at_attack(L("Snare_-_U_Lied"))
    # One-shots sliced from the loops (positions found by onset detection).
    ch1, ch2 = cut(hat, 0.08, 0.03, 0.801), cut(hat, 0.08, 0.03, 1.596)
    oh1, oh2 = cut(hat, 0.40, 0.12, 0.401), cut(hat, 0.40, 0.12, 4.0)
    shk1, shk2 = cut(shaker, 0.16, 0.06, 0.319), cut(shaker, 0.22, 0.08, 0.639)
    pb1, pb2 = cut(blank, 0.20, 0.06, 0.284), cut(blank, 0.16, 0.05, 0.842)
    pp1, pp2, pp3 = cut(para, 0.12, 0.04, 0.755), cut(para, 0.12, 0.04, 0.940), cut(para, 0.25, 0.09, 2.438)

    def kick(n, sec, i=0):
        return kicks(L(n, i), sec)

    def bass(n, sec=1.5):
        return cut(start_at_attack(L(n)), sec, fade=0.25)

    # kit -> (label per track, role per track, sample per track); tracks: BD SD TM CP RS CH OH CY
    kits = {
        "FRC": (["F1", "SD", "E1", "PC1", "PC2", "CH", "OH", "SHK"], ["BD", "SD", "TM", "CP", "RS", "CH", "OH", "CY"],
                [kick("Laur_Frenchcore_Kick_F1", 1.0), snare, kick("Laur_Frenchcore_Kick_E1", 0.8), pp3, pb2, ch1, oh1, shk2]),
        "HTK": (["K36", "SD", "K48", "PC1", "PC2", "CH", "OH", "SUB"], ["BD", "SD", "TM", "CP", "RS", "CH", "OH", "CY"],
                [kick("Laur_Kick_036_F", 0.4), pitch(snare, 2), kick("Laur_Kick_048_F", 0.4), pb1, pp1, ch2, oh2, kick("Laur_Kick_034_F", 1.2)]),
        "DNB": (["DMN", "SD", "BRK", "CRM", "RCK", "CH", "OH", "SHK"], ["BD", "SD", "TM", "CP", "RS", "CH", "OH", "CY"],
                [kick("Kick_-_Damn", 0.9), pitch(snare, -1), kick("Kick_-_Breakbeat", 0.3), kick("Kick_-_Crimewave", 0.2),
                 kick("Kick_-_Rack", 0.15), ch1, oh1, shk1]),
        "FCK": (["F1A", "F1B", "G1A", "G1B", "E1", "E2", "D2", "L10"], ["BD"] * 8,
                [kick("Laur_Frenchcore_Kick_F1", 1.0, 0), kick("Laur_Frenchcore_Kick_F1", 1.0, 1),
                 kick("Laur_Frenchcore_Kick_G1", 1.0, 0), kick("Laur_Frenchcore_Kick_G1", 1.0, 1),
                 kick("Laur_Frenchcore_Kick_E1", 1.0), kick("Laur_Frenchcore_Kick_E2", 1.0),
                 kick("Laur_Frenchcore_Kick_D2", 1.0), kick("Lauc_Kick_010_F", 0.4)]),
        "BAS": (["REES", "DARK", "CERB", "CRYS", "DRO", "VANI", "SAW", "SPNZ"], ["BASS"] * 8,
                [bass("Bass_-_Deep_Reese"), bass("Bass_-_Dark_State"), bass("Bass_-_Cerbera"), bass("Bass_-_Crystal"),
                 bass("Bass_-_Dro"), bass("Bass_-_Vanished"), bass("Bass_-_Saw_1"), bass("Bass_-_Spinz")]),
        "MIX": (["BUBL", "LIVE", "ACOU", "L11", "K49", "K50", "KAC", "RACK"], ["BASS", "BASS", "BASS", "BD", "BD", "BD", "BD", "BD"],
                [bass("Bass_-_Bubblegum"), bass("Bass_-_Live"), bass("Bass_-_Acoustic"), kick("Lauc_Kick_011_A", 0.4),
                 kick("Laur_Kick_049_A", 0.3), kick("Laur_Kick_050_E", 0.4), kick("Kick_-_Acoustic", 0.5), kick("Kick_-_Rack", 0.15)]),
    }
    for kit, (names, roles, data) in kits.items():
        for name, role, a in zip(names, roles, data):
            b = level(a, role)
            write(os.path.join(ROOT, kit, name + ".wav"), b)
            print("%s/%-5s %-4s %5.2fs  peak %6.1f dBFS" % (kit, name, role, len(b) / FS, db(np.abs(b).max())))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--uploads", help="folder holding the sample packs (the files are named <hash>-<Name>.wav)")
    args = ap.parse_args()
    master_factory()
    if args.uploads:
        build_extra(args.uploads)
    else:
        print("(no --uploads: extra kits left as they are)", file=sys.stderr)
