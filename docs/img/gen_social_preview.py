#!/usr/bin/env python3
"""Social-Preview-Banner (1280x640) für das GitHub-Repo FlipsiBridge.

Farben aus der App-UI abgeleitet: Hintergrund #0E1013/#1A1D23, Akzent "#F07140".
Aufruf: python3 gen_social_preview.py   -> schreibt social-preview.png neben sich.
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1280, 640
BG0, BG1 = (11, 13, 16), (23, 26, 32)
ACC = (240, 113, 64)          # App-Akzent
ACC_DIM = (150, 82, 52)
TXT = (245, 247, 250)
MUT = (140, 148, 158)
LINE = (58, 64, 74)

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_R = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

def font(sz, bold=True):
    return ImageFont.truetype(FONT if bold else FONT_R, sz)

# ---------- Basis: Verlauf + Punkt-Raster ----------
base = Image.new("RGB", (W, H), BG0)
dr = ImageDraw.Draw(base)
for y in range(H):
    t = y / H
    c = tuple(int(a + (b - a) * (0.35 + 0.3 * t)) for a, b in zip(BG0, BG1))
    dr.line([(0, y), (W, y)], fill=c)

dots = Image.new("RGBA", (W, H), (0, 0, 0, 0))
dd = ImageDraw.Draw(dots)
for gx in range(0, W, 44):
    for gy in range(0, H, 44):
        dd.ellipse([gx, gy, gx + 2, gy + 2], fill=(255, 255, 255, 9))
base = Image.alpha_composite(base.convert("RGBA"), dots)

# ---------- Linker Text-Block ----------
t1 = Image.new("RGBA", (W, H), (0, 0, 0, 0))
t1d = ImageDraw.Draw(t1)
t1d.text((70, 128), "FlipsiBridge", font=font(116), fill=TXT + (255,))
t1d.text((74, 286), "Remote Android device control", font=font(40, False), fill=MUT + (255,))
t1d.text((74, 340), "for AI agents.", font=font(40, False), fill=MUT + (255,))

# Chips unten
chips = ["Kotlin · Compose", "Python relay", "Self-hosted", "MIT"]
cx = 74
for c in chips:
    f = font(24, False)
    w = t1d.textlength(c, font=f)
    t1d.rounded_rectangle([cx, 560, cx + w + 34, 604], 20, outline=LINE + (255,), fill=(255, 255, 255, 8), width=2)
    t1d.text((cx + 17, 569), c, font=f, fill=MUT + (255,))
    cx += int(w) + 52

# ---------- Rechte Motiv-Ebene: Phone + E2E-Bogen + AI-Knoten ----------
base = Image.alpha_composite(base, t1)   # Text-Layer VOR der Motiv-Ebene einblenden
mot = Image.new("RGBA", (W, H), (0, 0, 0, 0))
md = ImageDraw.Draw(mot)

# E2E-Bogen: dashed Linie von AI-Knoten zum Phone, mit Schloss mittig
def dashed_line(d, p0, p1, dash=10, gap=8, fill=(120, 130, 142, 160), width=3):
    import math
    x0, y0 = p0; x1, y1 = p1
    L = math.hypot(x1 - x0, y1 - y0)
    n = max(1, int(L // (dash + gap)))
    for i in range(n):
        s = (dash + gap) * i / L
        e = min(L, s + dash)
        if s >= L:
            break
        d.line([(x0 + (x1 - x0) * s, y0 + (y1 - y0) * s),
                (x0 + (x1 - x0) * e, y0 + (y1 - y0) * e)], fill=fill, width=width)

AX, AY = 760, 210      # AI-Knoten
PX, PY = 1090, 210     # Phone-Mitte oben
dashed_line(md, (AX, AY), (PX, PY))
md.ellipse([AX - 46, AY - 46, AX + 46, AY + 46], outline=LINE + (255,), width=3, fill=(20, 23, 29, 255))
md.text((AX, AY), "AI", anchor="mm", font=font(34), fill=TXT + (255,))
md.text((AX, AY + 62), "agent", anchor="mm", font=font(20, False), fill=MUT + (255,))

# Schloss mittig auf der Verbindung
Lx = (AX + PX) / 2
md.ellipse([Lx - 26, AY - 26, Lx + 26, AY + 26], fill=(15, 17, 21, 255), outline=ACC + (255,), width=3)
# Schloss-Glyph: Bügel + Körper
md.rounded_rectangle([Lx - 11, AY - 2, Lx + 11, AY + 15], 5, fill=ACC + (255,))
md.arc([Lx - 9, AY - 16, Lx + 9, AY + 2], start=180, end=360, fill=ACC + (255,), width=4)
md.text((Lx, AY + 40), "E2E", anchor="mm", font=font(18, False), fill=ACC_DIM + (255,))

# Phone (Rechts)
ph = [980, 120, 1200, 560]
md.rounded_rectangle(ph, 40, outline=(120, 130, 142, 255), fill=(22, 25, 31, 255), width=4)
md.rounded_rectangle([1030, 132, 1150, 146], 7, fill=(30, 34, 41, 255))  # Kameraleiste
# Chat in Phone: verschluesselter + orange Bubble mit Schloss
md.rounded_rectangle([996, 170, 1170, 214], 22, fill=(30, 34, 41, 255))
md.text((1010, 192), "▮▯▯▮ ▯▮·▮▮", font=font(18, False), fill=(120, 130, 142, 255), anchor="lm")
md.rounded_rectangle([996, 228, 1136, 286], 22, fill=ACC + (255,))
# kleines Schloss in der Bubble
md.rounded_rectangle([1012, 250, 1032, 272], 6, fill=(15, 17, 21, 255))
md.arc([1015, 240, 1029, 256], start=180, end=360, fill=(15, 17, 21, 255), width=3)
md.text((1046, 258), "screen", font=font(18, False), fill=(18, 14, 12, 255), anchor="lm")
md.rounded_rectangle([996, 300, 1170, 344], 22, fill=(30, 34, 41, 255))
md.text((1010, 322), "▮·▯▮ ▯▮▮▮", font=font(18, False), fill=(120, 130, 142, 255), anchor="lm")
# Tool-Zeilen unten im Phone
for i, t in enumerate(["camera", "files", "notifications", "voice"]):
    y = 372 + i * 38
    md.rounded_rectangle([1000, y, 1030, y + 24], 7, fill=(36, 40, 48, 255))
    md.ellipse([1013, y + 9, 1017, y + 13], fill=ACC_DIM + (255,))
    md.text((1040, y + 12), t, font=font(17, False), fill=(160, 168, 178, 255), anchor="lm")

base = Image.alpha_composite(base, mot)

# ---------- Glow (Accent) separat, krisp Text zuletzt ----------
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
gd.ellipse([1010, 240, 1140, 286], fill=ACC + (90,))
gd.ellipse([Lx - 40, AY - 40, Lx + 40, AY + 40], fill=ACC + (60,))
glow = glow.filter(ImageFilter.GaussianBlur(22))
base = Image.alpha_composite(base, glow)

# ---------- Balance: dezenter Anker rechts (Ring + weicher Glow hinter Phone) ----------
bal = Image.new("RGBA", (W, H), (0, 0, 0, 0))
bd = ImageDraw.Draw(bal)
bd.ellipse([1090 - 250, 340 - 250, 1090 + 250, 340 + 250], outline=(120, 130, 142, 26), width=2)
bd.ellipse([1090 - 180, 340 - 180, 1090 + 180, 340 + 180], outline=(120, 130, 142, 20), width=2)
bd.ellipse([1090 - 300, 340 - 300, 1090 + 300, 340 + 300], outline=(120, 130, 142, 12), width=2)
# weicher rechter Corner-Glow
cg = Image.new("RGBA", (W, H), (0, 0, 0, 0))
cd = ImageDraw.Draw(cg)
cd.ellipse([1150, 380, 1500, 760], fill=(240, 113, 64, 26))
cg = cg.filter(ImageFilter.GaussianBlur(90))
base = Image.alpha_composite(base, cg)
base = Image.alpha_composite(base, bal)
base = Image.alpha_composite(base, mot)  # krisp darüber

# ---------- Export ----------
out = base.convert("RGB")
out.save(__file__.replace("gen_social_preview.py", "social-preview.png"), "PNG")
print("ok:", __file__.replace("gen_social_preview.py", "social-preview.png"), out.size)
