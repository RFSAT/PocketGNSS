"""Renders the Google Play icon (512x512) and feature graphic (1024x500) from icon.svg. Needs: pip install cairosvg pillow"""
import io, os, cairosvg
from PIL import Image, ImageDraw, ImageFont

here = os.path.dirname(os.path.abspath(__file__))
icon = Image.open(io.BytesIO(cairosvg.svg2png(url=os.path.join(here, "icon.svg"), output_width=512, output_height=512))).convert("RGBA")
icon.save(os.path.join(here, "play_icon_512.png"))

def font(size, bold=False):
    for f in (["DejaVuSans-Bold.ttf"] if bold else ["DejaVuSans.ttf"]):
        for d in ("/usr/share/fonts/truetype/dejavu", "C:/Windows/Fonts"):
            p = os.path.join(d, f)
            if os.path.exists(p): return ImageFont.truetype(p, size)
    return ImageFont.load_default()

fg = Image.new("RGB", (1024, 500), "#121416")
d = ImageDraw.Draw(fg)
for y in range(500):  # subtle vertical gradient
    c = int(0x16 + 0x10 * (1 - y / 500)); d.line([(0, y), (1024, y)], fill=(c, c + 4, c + 8))
fg.paste(icon.resize((300, 300)), (60, 100), icon.resize((300, 300)))
d.text((400, 140), "PocketGNSS", font=font(78, True), fill="#FF8A65")
d.text((404, 240), "RTK GNSS receiver manager", font=font(34), fill="#FFFFFF")
d.text((404, 292), "Centimetre positioning over USB and Bluetooth", font=font(24), fill="#B0BEC5")
d.text((404, 328), "NTRIP · survey · stake-out · u-blox setup", font=font(24), fill="#B0BEC5")
d.rounded_rectangle((404, 380, 584, 424), 10, fill="#2E7D32"); d.text((420, 388), "RTK fixed", font=font(24, True), fill="#FFFFFF")
d.text((600, 388), "±1 cm", font=font(26, True), fill="#FFFFFF")
fg.save(os.path.join(here, "feature_graphic_1024x500.png"))
print("written play_icon_512.png and feature_graphic_1024x500.png")
