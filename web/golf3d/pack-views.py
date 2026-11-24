# Assemble le pack d'images de la Golf (rendus parallèles de render-views.py) : views.json + golf-vues.zip
# Usage : python3 pack-views.py <dossier des rendus> <numéro du pack> <zip de sortie>
import sys, os, json, zipfile, glob
src, num, zout = sys.argv[1], int(sys.argv[2]), sys.argv[3]
files = sorted(glob.glob(f"{src}/**/v*.webp", recursive=True))
states, elevs, angles = {}, set(), {}
for f in files:
    st, e, i = os.path.basename(f)[1:-5].rsplit('_', 2)
    elevs.add(int(e)); angles[st] = max(angles.get(st, 0), int(i) + 1)
infos = json.load(open(glob.glob(f"{src}/**/infos.json", recursive=True)[0]))
for f in glob.glob(f"{src}/**/infos.json", recursive=True):
    assert json.load(open(f)) == infos, "fiches différentes entre les rendus : " + f
meta = {"pack": num, "w": 1280, "h": 640, "pw": 320, "ph": 160, "angles": angles, "elevs": sorted(elevs), "infos": infos}
with zipfile.ZipFile(zout, "w", zipfile.ZIP_STORED) as z:
    z.writestr("views.json", json.dumps(meta, ensure_ascii=False))
    for f in files + sorted(glob.glob(f"{src}/**/p*.png", recursive=True)):
        z.write(f, os.path.basename(f))
print(len(files), "vues,", os.path.getsize(zout) // 1024 // 1024, "Mo")
