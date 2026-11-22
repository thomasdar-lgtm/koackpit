import sys, json, base64, threading, http.server, functools, socketserver
d=sys.argv[1]; out=sys.argv[2]
H=functools.partial(http.server.SimpleHTTPRequestHandler, directory=d); H.log_message=lambda *a:None
srv=socketserver.TCPServer(("127.0.0.1",8799),H); threading.Thread(target=srv.serve_forever,daemon=True).start()
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b=p.chromium.launch(args=["--use-gl=angle","--use-angle=swiftshader","--enable-unsafe-swiftshader"]); pg=b.new_page(viewport={'width':816,'height':390})
    pg.on("console", lambda m: print("console:", m.text) if m.type in ("error","warning") else None)
    pg.goto("http://127.0.0.1:8799/index.html?export=1"); pg.wait_for_function("window.__ready===true",timeout=120000,polling=500)
    r=pg.evaluate("koackpitExport()")
    open(out+".glb","wb").write(base64.b64decode(r["glb"]))
    json.dump(r["meta"], open(out+".json","w"), ensure_ascii=False, indent=1)
    print("ok", len(r["meta"]["infos"]), "fiches")
    b.close()
srv.shutdown()
