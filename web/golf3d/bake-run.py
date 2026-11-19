import sys,base64,threading,http.server,functools,socketserver
d=sys.argv[1]
H=functools.partial(http.server.SimpleHTTPRequestHandler, directory=d); H.log_message=lambda *a:None
srv=socketserver.TCPServer(("127.0.0.1",8722),H); threading.Thread(target=srv.serve_forever,daemon=True).start()
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b=p.chromium.launch(); pg=b.new_page(); pg.on("console",lambda m:print(m.text))
    pg.goto("http://127.0.0.1:8722/index.html")
    r=pg.evaluate("bake('golf5.glb')")
    open(sys.argv[2],"wb").write(base64.b64decode(r["b64"]))
    print("sommets avant",r["before"],"après",r["after"])
    b.close()
srv.shutdown()
