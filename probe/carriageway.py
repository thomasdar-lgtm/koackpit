import json, math, sys, urllib.request, urllib.parse
PTS = [(43.857986,-0.722169),(43.85137,-0.738436),(43.85506,-0.41901),(43.884949,-0.371356),(43.910795,-0.565773)]
HW = "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|road"
def q(lat, lon):
    s = f'[out:json][timeout:20];way(around:60,{lat},{lon})[highway~"^({HW})$"];out tags geom;'
    import time
    for url in ["https://overpass.private.coffee/api/interpreter","https://overpass-api.de/api/interpreter","https://overpass.kumi.systems/api/interpreter"]*2:
        try:
            r = urllib.request.urlopen(urllib.request.Request(url, data=("data="+urllib.parse.quote(s)).encode(), headers={"User-Agent":"Koackpit-probe"}), timeout=60)
            return json.loads(r.read())
        except Exception as e: print("err", url, e); time.sleep(3)
    return {"elements": []}
for lat, lon in PTS:
    js = q(lat, lon)
    kx = 111320*math.cos(math.radians(lat)); ky = 110540
    print(f"== {lat},{lon}")
    for e in js["elements"]:
        t = e.get("tags", {}); g = e.get("geometry", [])
        best = (1e9, 0)
        for a, b in zip(g, g[1:]):
            ax=(a["lon"]-lon)*kx; ay=(a["lat"]-lat)*ky; bx=(b["lon"]-lon)*kx; by=(b["lat"]-lat)*ky
            dx=bx-ax; dy=by-ay; L=dx*dx+dy*dy
            u = 0 if L==0 else max(0,min(1,-(ax*dx+ay*dy)/L))
            d = math.hypot(ax+u*dx, ay+u*dy)
            brg = (math.degrees(math.atan2(dx, dy))+360)%360
            if d < best[0]: best = (d, brg)
        print(f"  {best[0]:6.1f} m  cap {best[1]:5.1f}  {t.get('highway')} oneway={t.get('oneway','')} ref={t.get('ref','')} name={t.get('name','')} maxspeed={t.get('maxspeed','')} junction={t.get('junction','')}")
