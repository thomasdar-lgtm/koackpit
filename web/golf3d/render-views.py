# Vues pré-calculées de la Golf pour Koackpit (depuis 0.17.5) → app/src/main/assets/golf/
#   v<état>_<angle>.webp : image transparente (le fond du thème se voit derrière)
#   p<état>_<angle>.png  : carte des pièces en petit (gris = numéro de fiche + 1, 0 = rien)
#   views.json           : états, angles, tailles, fiches
# Usage : python3 render-views.py dist <dossier de sortie> [angles]
import sys, json, base64, io, os, threading, http.server, functools, socketserver, time
from PIL import Image
from playwright.sync_api import sync_playwright
d, out = sys.argv[1], sys.argv[2]
# Angles : 72 (tous les 5°) voiture fermée, 36 ouvrant ouvert
N0 = int(sys.argv[3]) if len(sys.argv) > 3 else 72
W, H, PW, PH, DIST, Q = 1280, 640, 320, 160, 0.72, 78
# États : fermé, chaque ouvrant seul, tout ouvert (plusieurs ouvrants → « tout »)
STATES = {"0": 0, "FL": 1, "FR": 2, "RL": 4, "RR": 8, "TAIL": 16, "HOOD": 32, "ALL": 63}
os.makedirs(out, exist_ok=True)
Hd = functools.partial(http.server.SimpleHTTPRequestHandler, directory=d); Hd.log_message = lambda *a: None
srv = socketserver.TCPServer(("127.0.0.1", 8798), Hd); threading.Thread(target=srv.serve_forever, daemon=True).start()
with sync_playwright() as p:
    b = p.chromium.launch(args=["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"])
    pg = b.new_page(viewport={'width': 800, 'height': 450})
    pg.on("console", lambda m: print("console:", m.text) if m.type == "error" else None)
    pg.goto("http://127.0.0.1:8798/index.html?export=1"); pg.wait_for_function("window.__ready===true", timeout=180000, polling=500)
    t0 = time.time(); angles = {}
    for name, mask in STATES.items():
        pg.evaluate(f"koackpitPose({mask})")
        N = N0 if mask == 0 else N0 // 2
        angles[name] = N
        for i in range(N):
            az = i * 360 / N
            url = pg.evaluate(f"koackpitShot({az}, {W}, {H}, {DIST})")
            im = Image.open(io.BytesIO(base64.b64decode(url.split(",", 1)[1]))).convert("RGBA")
            im.save(f"{out}/v{name}_{i}.webp", "WEBP", quality=Q, method=6)
            pm = base64.b64decode(pg.evaluate(f"koackpitPickMap({PW}, {PH})"))
            Image.frombytes("L", (PW, PH), pm).save(f"{out}/p{name}_{i}.png", optimize=True)
        print(name, "ok", int(time.time() - t0), "s", flush=True)
    infos = pg.evaluate("koackpitInfos()")
    json.dump({"angles": angles, "w": W, "h": H, "pw": PW, "ph": PH, "states": list(STATES.keys()), "infos": infos},
              open(f"{out}/views.json", "w"), ensure_ascii=False)
    b.close()
srv.shutdown()
