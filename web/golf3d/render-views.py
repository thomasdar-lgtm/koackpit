# Vues pré-calculées de la Golf pour Koackpit (pack d'images téléchargé à part depuis 0.20.0)
#   v<état>_<hauteur>_<angle>.webp : image transparente 1280×640 (le fond du thème se voit derrière)
#   p<état>_<hauteur>_<angle>.png  : carte des pièces 320×160 (gris = numéro de fiche + 1, 0 = rien)
#   views.json (fait par pack-views.py) : états, angles, hauteurs, tailles, fiches
# Usage : python3 render-views.py dist <sortie> <états séparés par des virgules> <hauteurs séparées par des virgules> [angles]
#   hauteur : 0 = vue de départ (3/4 avant, comme avant 0.20.0), 1 = vue de dessus
import sys, json, base64, io, os, threading, http.server, functools, socketserver, time
from PIL import Image
from playwright.sync_api import sync_playwright
d, out, names, elevs = sys.argv[1], sys.argv[2], sys.argv[3].split(','), [int(x) for x in sys.argv[4].split(',')]
N = int(sys.argv[5]) if len(sys.argv) > 5 else 72
W, H, PW, PH, Q = 1280, 640, 320, 160, 78
# États : fermé, chaque ouvrant seul, combinaisons courantes, tout ouvert
STATES = {"0": 0, "FL": 1, "FR": 2, "RL": 4, "RR": 8, "TAIL": 16, "HOOD": 32,
          "FL_TAIL": 17, "FL_FR": 3, "FL_RL": 5, "DOORS": 15, "ALL": 63}
# Hauteurs : (degrés au-dessus de l'horizon ou None = vue de départ, distance)
ELEVS = {0: (None, 0.72), 1: (45, 1.2)}
os.makedirs(out, exist_ok=True)
port = 8700 + os.getpid() % 200
Hd = functools.partial(http.server.SimpleHTTPRequestHandler, directory=d); Hd.log_message = lambda *a: None
srv = socketserver.TCPServer(("127.0.0.1", port), Hd); threading.Thread(target=srv.serve_forever, daemon=True).start()
with sync_playwright() as p:
    b = p.chromium.launch(args=["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"])
    pg = b.new_page(viewport={'width': 800, 'height': 450})
    pg.on("console", lambda m: print("console:", m.text) if m.type == "error" else None)
    pg.goto(f"http://127.0.0.1:{port}/index.html?export=1"); pg.wait_for_function("window.__ready===true", timeout=180000, polling=500)
    pg.evaluate("koackpitInitInfos()")
    t0 = time.time()
    for name in names:
        pg.evaluate(f"koackpitPose({STATES[name]})")
        for e in elevs:
            el, dist = ELEVS[e]
            for i in range(N):
                url = pg.evaluate(f"koackpitShot({i * 360 / N}, {W}, {H}, {dist}, {'null' if el is None else el})")
                im = Image.open(io.BytesIO(base64.b64decode(url.split(",", 1)[1]))).convert("RGBA")
                im.save(f"{out}/v{name}_{e}_{i}.webp", "WEBP", quality=Q, method=6)
                pm = base64.b64decode(pg.evaluate(f"koackpitPickMap({PW}, {PH})"))
                Image.frombytes("L", (PW, PH), pm).save(f"{out}/p{name}_{e}_{i}.png", optimize=True)
            print(name, e, "ok", int(time.time() - t0), "s", flush=True)
    json.dump(pg.evaluate("koackpitInfos()"), open(f"{out}/infos.json", "w"), ensure_ascii=False)
    b.close()
srv.shutdown()
