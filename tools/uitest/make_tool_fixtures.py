# Made-up files for tools/uitest/pdftools.js: a 6-page PDF with photos (big), a locked PDF, photos.
import io, os, random, sys
from PIL import Image, ImageDraw
import pikepdf
out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/toolfx"
os.makedirs(out, exist_ok=True)
random.seed(1)
def photo(w, h, seed):
    random.seed(seed)
    im = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(im)
    for _ in range(400):
        x, y = random.randrange(w), random.randrange(h)
        d.ellipse([x, y, x + random.randrange(20, 160), y + random.randrange(20, 160)], fill=tuple(random.randrange(256) for _ in range(3)))
    for y in range(0, h, 3):  # noise so JPEG cannot shrink it much
        for x in range(0, w, 3):
            if random.random() < 0.3: im.putpixel((x, y), tuple(random.randrange(256) for _ in range(3)))
    return im
pages = []
for i in range(6):
    p = Image.new("RGB", (1240, 1754), "white")
    d = ImageDraw.Draw(p)
    d.text((100, 80), "Test page %d" % (i + 1), fill="black")
    p.paste(photo(1000, 1200, i), (120, 300))
    pages.append(p)
pages[0].save(os.path.join(out, "report.pdf"), save_all=True, append_images=pages[1:], resolution=150, quality=95)
pdf = pikepdf.open(os.path.join(out, "report.pdf"))
pdf.save(os.path.join(out, "locked.pdf"), encryption=pikepdf.Encryption(user="RAME1985", owner="owner", R=4))
photo(3000, 2000, 7).save(os.path.join(out, "photo1.jpg"), quality=95)
photo(1600, 2400, 8).save(os.path.join(out, "photo2.jpg"), quality=95)
photo(1200, 1200, 9).save(os.path.join(out, "photo3.png"))
s = Image.new("RGB", (900, 300), "white"); ImageDraw.Draw(s).line([(60, 200), (200, 80), (340, 220), (520, 90), (800, 180)], fill=(20, 20, 90), width=12)
s.save(os.path.join(out, "sign.jpg"), quality=90)
print("ok")
