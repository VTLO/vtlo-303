#!/usr/bin/env python3
"""Generates assets/banner.svg: the animated VTLO 303 banner.

Black-and-white tribal sound-system totem (robot masks, speaker stacks, sunburst,
circuit nodes) with a single red LED accent. Pure SVG + CSS animation, no scripts or
fonts, so it animates inside <img> tags (web page and GitHub README) and respects
prefers-reduced-motion.

    python3 tools/make-banner.py [output.svg]
"""
import math
import sys
from pathlib import Path

W, H = 1200, 280
CX = 600
HEAD_Y = 150
BG = "#0e0d0c"
INK = "#f4f1ea"
DIM = "#3a3633"
RED = "#ff3b2a"

out = []
add = out.append


def f(v):
    return f"{v:.1f}".rstrip("0").rstrip(".")


# ─── Styles ────────────────────────────────────────────────

add(f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" role="img" aria-label="VTLO 303: a robot totem between two speaker stacks">
<title>VTLO 303</title>
<style>
  .ink {{ stroke: {INK}; stroke-width: 3; fill: none; stroke-linejoin: round; stroke-linecap: round; }}
  .fillbg {{ fill: {BG}; }}
  .solid {{ fill: {INK}; }}
  .fx {{ transform-box: fill-box; transform-origin: center; }}
  .spin {{ transform-origin: {CX}px {HEAD_Y}px; animation: spin 90s linear infinite; }}
  .cone {{ animation: thump .96s ease-out infinite; }}
  .cone.off {{ animation-delay: .48s; }}
  .ring {{ animation: shock .96s ease-out infinite; opacity: 0; }}
  .ring.off {{ animation-delay: .48s; }}
  .eye {{ animation: blink 4.2s infinite; }}
  .pupil {{ animation: look 2.8s ease-in-out infinite alternate; }}
  .tooth {{ fill: {RED}; opacity: 0; animation: glow 2.4s ease-out infinite; }}
  .node {{ fill: {INK}; animation: node 1.92s steps(1, end) infinite; }}
  .draw {{ stroke-dasharray: 100; stroke-dashoffset: 100; animation: draw 6s ease-in-out infinite; }}
  .pulse {{ stroke: {RED}; stroke-dasharray: 22 78; animation: run 1.6s linear infinite; }}
  .step {{ fill: {DIM}; animation: step 1.92s steps(1, end) infinite; }}
  .flick {{ animation: flick 5s steps(1, end) infinite; }}
  @keyframes spin {{ to {{ transform: rotate(360deg); }} }}
  @keyframes thump {{ 0% {{ transform: scale(1); }} 7% {{ transform: scale(.84); }} 38% {{ transform: scale(1); }} }}
  @keyframes shock {{ 0% {{ transform: scale(1); opacity: .7; }} 70%, 100% {{ transform: scale(1.35); opacity: 0; }} }}
  @keyframes blink {{ 0%, 93%, 100% {{ transform: scaleY(1); }} 96% {{ transform: scaleY(.08); }} }}
  @keyframes look {{ from {{ transform: translateX(-4px); }} to {{ transform: translateX(4px); }} }}
  @keyframes glow {{ 0% {{ opacity: 0; }} 10% {{ opacity: .95; }} 42%, 100% {{ opacity: 0; }} }}
  @keyframes node {{ 0% {{ fill: {RED}; }} 25%, 100% {{ fill: {INK}; }} }}
  @keyframes draw {{ 0% {{ stroke-dashoffset: 100; }} 45%, 75% {{ stroke-dashoffset: 0; }} 100% {{ stroke-dashoffset: -100; }} }}
  @keyframes run {{ from {{ stroke-dashoffset: 100; }} to {{ stroke-dashoffset: 0; }} }}
  @keyframes step {{ 0% {{ fill: {RED}; }} 6.25%, 100% {{ fill: {DIM}; }} }}
  @keyframes flick {{ 0%, 100% {{ opacity: 1; }} 90% {{ opacity: .35; }} 92% {{ opacity: 1; }} 95% {{ opacity: .5; }} 96% {{ opacity: 1; }} }}
  @media (prefers-reduced-motion: reduce) {{ * {{ animation: none !important; }} }}
</style>
<defs>
  <clipPath id="frame"><rect width="{W}" height="{H}" rx="10"/></clipPath>
  <clipPath id="grin"><path id="grinshape" d="M{CX-70} {HEAD_Y+14} Q{CX} {HEAD_Y+38} {CX+70} {HEAD_Y+14} Q{CX+66} {HEAD_Y+48} {CX} {HEAD_Y+56} Q{CX-66} {HEAD_Y+48} {CX-70} {HEAD_Y+14} Z"/></clipPath>
</defs>
<g clip-path="url(#frame)">
<rect width="{W}" height="{H}" fill="{BG}"/>''')

# ─── Sunburst rays (slowly rotating) ───────────────────────

add('<g class="spin" opacity=".13">')
rays = 40
for i in range(rays):
    if i % 2:
        continue
    a0 = math.radians(i * 360 / rays)
    a1 = math.radians((i + 1) * 360 / rays)
    r = 900
    pts = [(CX, HEAD_Y), (CX + r * math.cos(a0), HEAD_Y + r * math.sin(a0)), (CX + r * math.cos(a1), HEAD_Y + r * math.sin(a1))]
    add(f'<polygon class="solid" points="{" ".join(f"{f(x)},{f(y)}" for x, y in pts)}"/>')
add('</g>')

# Dark vignette so the figure reads clearly over the rays.
add(f'<ellipse cx="{CX}" cy="{HEAD_Y}" rx="330" ry="150" fill="{BG}" opacity=".78"/>')

# ─── Wordmark: geometric block letters, no font needed ─────

def letter(ch, x, y, w, h, sw=9):
    s = f'class="ink" style="stroke-width:{sw}; stroke-linecap:square; stroke-linejoin:miter"'
    m = y + h / 2
    if ch == "V":
        return f'<path {s} d="M{f(x)} {f(y)} L{f(x + w / 2)} {f(y + h)} L{f(x + w)} {f(y)}"/>'
    if ch == "T":
        return f'<path {s} d="M{f(x)} {f(y)} H{f(x + w)} M{f(x + w / 2)} {f(y)} V{f(y + h)}"/>'
    if ch == "L":
        return f'<path {s} d="M{f(x)} {f(y)} V{f(y + h)} H{f(x + w)}"/>'
    if ch == "O":
        return f'<rect {s} x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="{f(w * .32)}"/>'
    if ch == "0":
        return (f'<rect {s} x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="{f(w * .32)}"/>'
                f'<path {s} d="M{f(x + w * .3)} {f(y + h * .72)} L{f(x + w * .7)} {f(y + h * .28)}"/>')
    if ch == "3":
        return f'<path {s} d="M{f(x)} {f(y)} H{f(x + w)} V{f(y + h)} H{f(x)} M{f(x + w * .28)} {f(m)} H{f(x + w)}"/>'
    raise ValueError(ch)


def word(text, x0, w, gap, y=98, h=84):
    x = x0
    for ch in text:
        add(letter(ch, x, y, w, h))
        x += w + gap


add('<g class="flick">')
word("VTLO", 44, 46, 14)
word("303", 944, 60, 16)
add('</g>')

# ─── Speaker stacks with horns ─────────────────────────────

SP = 96
SPK_X = 162


def speaker(cx, cy, size, off):
    s = size
    add(f'<rect class="ink fillbg" x="{f(cx - s / 2)}" y="{f(cy - s / 2)}" width="{s}" height="{s}" rx="6"/>')
    for dx in (-1, 1):
        for dy in (-1, 1):
            add(f'<circle class="solid" cx="{f(cx + dx * (s / 2 - 8))}" cy="{f(cy + dy * (s / 2 - 8))}" r="2.4"/>')
    o = " off" if off else ""
    add(f'<circle class="ink ring fx{o}" cx="{cx}" cy="{cy}" r="{f(s * .36)}" stroke-width="2"/>')
    add(f'<g class="cone fx{o}">'
        f'<circle class="ink" cx="{cx}" cy="{cy}" r="{f(s * .36)}"/>'
        f'<circle class="ink" cx="{cx}" cy="{cy}" r="{f(s * .22)}" stroke-width="2"/>'
        f'<circle class="solid" cx="{cx}" cy="{cy}" r="{f(s * .09)}"/></g>')


def horn(side, y0, y1):
    """A flared horn on the outer side of a speaker (side -1 = left, +1 = right)."""
    x_in = CX + side * (SPK_X + SP // 2)
    x_out = x_in + side * 60
    add(f'<path class="ink fillbg" d="M{x_in} {y0 + 14} L{x_out} {y0 - 8} L{x_out} {y1 + 8} L{x_in} {y1 - 14} Z"/>')
    for k in (1, 2, 3):
        t = k / 4
        yy0 = (y0 + 14) + (y0 - 8 - (y0 + 14)) * t
        yy1 = (y1 - 14) + (y1 + 8 - (y1 - 14)) * t
        add(f'<path class="ink" stroke-width="1.6" opacity=".7" d="M{f(x_in + side * 60 * t)} {f(yy0)} V{f(yy1)}"/>')


for side in (-1, 1):
    x = CX + side * SPK_X
    horn(side, 38, 238)
    speaker(x, 90, SP, False)
    speaker(x, 190, SP, True)

# ─── Circuit arms: arrows, nodes and spirals ───────────────

def arm(side):
    x = CX + side * 100
    add(f'<path class="ink" stroke-width="2.4" d="M{x} 112 V56"/>')
    add(f'<path class="ink" stroke-width="2.4" d="M{x - 7} 68 L{x} 54 L{x + 7} 68"/>')
    add(f'<circle class="ink fillbg" cx="{x}" cy="40" r="9"/>')
    add(f'<circle class="node" cx="{x}" cy="40" r="3.6" style="animation-delay:{0 if side < 0 else .96}s"/>')
    add(f'<circle class="ink fillbg" cx="{x}" cy="120" r="6"/>')
    add(f'<circle class="node" cx="{x}" cy="120" r="2.4" style="animation-delay:{.48 if side < 0 else 1.44}s"/>')


def spiral(cx, cy, side):
    # A square spiral glyph, drawn and erased in a loop.
    pts = [(0, 0), (30, 0), (30, 30), (6, 30), (6, 12), (20, 12), (20, 22), (14, 22)]
    pts = [(cx + side * px, cy + py) for px, py in pts]
    d = "M" + " L".join(f"{f(px)} {f(py)}" for px, py in pts)
    add(f'<path class="ink draw" pathLength="100" stroke-width="3" d="{d}"/>')


for side in (-1, 1):
    arm(side)
spiral(CX - 58, 208, -1)
spiral(CX + 58, 208, 1)

# ─── Totem: helmet, pendant, grinning head, display ────────

# Helmet with a single watching eye.
add(f'<path class="ink fillbg" stroke-width="3.5" d="M{CX} 16 L{CX + 42} 50 V92 H{CX - 42} V50 Z"/>')
add(f'<path class="ink" stroke-width="2.4" d="M{CX} 16 V32 M{CX - 28} 58 L{CX - 12} 52 M{CX + 28} 58 L{CX + 12} 52"/>')
add(f'<circle class="ink fillbg" cx="{CX}" cy="70" r="14"/>')
add(f'<g class="eye fx"><circle class="solid" cx="{CX}" cy="70" r="9"/>'
    f'<circle class="pupil fx" cx="{CX}" cy="70" r="4.2" fill="{BG}"/></g>')
# Pendant ring joining helmet and head.
add(f'<circle class="ink fillbg" cx="{CX}" cy="101" r="8"/><circle class="solid" cx="{CX}" cy="101" r="2.8"/>')

# Head.
hy = HEAD_Y
add(f'<path class="ink fillbg" stroke-width="3.5" d="M{CX - 78} {hy - 26} Q{CX} {hy - 52} {CX + 78} {hy - 26} L{CX + 92} {hy + 10} L{CX + 74} {hy + 52} L{CX - 74} {hy + 52} L{CX - 92} {hy + 10} Z"/>')
# Eyes (almond, slanted) that blink and glance.
for side in (-1, 1):
    ex = CX + side * 38
    add(f'<g class="eye fx">'
        f'<path class="solid" d="M{f(ex - side * 24)} {hy - 4} Q{f(ex - side * 4)} {hy - 24} {f(ex + side * 22)} {hy - 2} Q{f(ex - side * 2)} {hy + 2} {f(ex - side * 24)} {hy - 4} Z"/>'
        f'<circle class="pupil fx" cx="{f(ex)}" cy="{hy - 9}" r="4.6" fill="{BG}"/></g>')
# Grin: white band cut into teeth; each tooth flashes red in a wave from the centre.
add(f'<use href="#grinshape" fill="{INK}"/>')
add('<g clip-path="url(#grin)">')
teeth = 9
x0, x1 = CX - 70, CX + 70
tw = (x1 - x0) / teeth
for i in range(teeth):
    d = abs(i - teeth // 2) * 0.12
    add(f'<rect class="tooth" x="{f(x0 + i * tw)}" y="{hy + 8}" width="{f(tw)}" height="54" style="animation-delay:{f(d)}s"/>')
for i in range(1, teeth):
    add(f'<rect fill="{BG}" x="{f(x0 + i * tw - 1.6)}" y="{hy + 8}" width="3.2" height="54"/>')
add(f'<rect fill="{BG}" x="{x0}" y="{hy + 29}" width="{x1 - x0}" height="3.4"/>')
add('</g>')
add(f'<use href="#grinshape" class="ink" stroke-width="3"/>')

# Collar display with a running waveform.
dy = hy + 66
add(f'<rect class="ink fillbg" x="{CX - 42}" y="{dy}" width="84" height="28" rx="5" stroke-width="2.6"/>')
ecg = f'M{CX - 34} {dy + 14} H{CX - 20} L{CX - 14} {dy + 5} L{CX - 6} {dy + 23} L{CX + 2} {dy + 8} L{CX + 8} {dy + 14} H{CX + 34}'
add(f'<path class="ink" pathLength="100" stroke-width="2" opacity=".5" d="{ecg}"/>')
add(f'<path class="ink pulse" pathLength="100" stroke-width="2.6" d="{ecg}"/>')

# ─── Step-sequencer LEDs along the bottom edge ─────────────

steps = 16
sw, sg = 14, 10
total = steps * sw + (steps - 1) * sg
sx = (W - total) / 2
for i in range(steps):
    add(f'<rect class="step" x="{f(sx + i * (sw + sg))}" y="{H - 14}" width="{sw}" height="5" rx="1.5" style="animation-delay:{f(i * 0.12)}s"/>')

# Frame.
add(f'<rect x="1.5" y="1.5" width="{W - 3}" height="{H - 3}" rx="9" fill="none" stroke="#3a3733" stroke-width="3"/>')
add('</g>\n</svg>\n')

target = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "assets" / "banner.svg"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text("\n".join(out))
print(target, target.stat().st_size, "bytes")
