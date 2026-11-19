
import * as THREE from 'three';
const EMBED = /[?&]embed/.test(location.search);
if (EMBED) { document.body.classList.add('embed'); document.documentElement.style.background = 'transparent'; }
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { MeshoptDecoder } from 'three/addons/libs/meshopt_decoder.module.js';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';

const renderer = new THREE.WebGLRenderer({ antialias:true, alpha:true });
renderer.setPixelRatio(EMBED ? 1 : Math.min(devicePixelRatio, 2));
renderer.setSize(innerWidth, innerHeight);
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.0;
renderer.shadowMap.enabled = true;
// Allègement (écran voiture) : ombre recalculée seulement quand un ouvrant bouge (le soleil est fixe)
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
if (EMBED) renderer.shadowMap.autoUpdate = false;
window.__renderer = renderer;
document.body.prepend(renderer.domElement);

const scene = new THREE.Scene();
window.__scene = scene;
const pmrem = new THREE.PMREMGenerator(renderer);
scene.environment = pmrem.fromScene(new RoomEnvironment(renderer), 0.04).texture;

const camera = new THREE.PerspectiveCamera(35, innerWidth/innerHeight, 0.01, 1000);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.08;
controls.enablePan = false;
controls.autoRotate = !EMBED;
controls.autoRotateSpeed = 1.2;
controls.maxPolarAngle = Math.PI * 0.495;

const sun = new THREE.DirectionalLight(0xffffff, 2.2);
sun.castShadow = true;
sun.shadow.mapSize.set(EMBED ? 1024 : 2048, EMBED ? 1024 : 2048);
scene.add(sun, new THREE.HemisphereLight(0xdfe8ff, 0x20242c, 0.6));

const ground = new THREE.Mesh(new THREE.CircleGeometry(1, 64), new THREE.ShadowMaterial({ opacity:0.45 }));
ground.rotation.x = -Math.PI/2;
ground.receiveShadow = true;
scene.add(ground);

const doors = {};
const zAxis = new THREE.Vector3(0, 0, 1), xAxis = new THREE.Vector3(1, 0, 0);
let size = 1, center = new THREE.Vector3(), bodyMats = [], bodyMeshes = [], origBody = null, paint = null;
const prog = document.querySelector('#prog i');

function onLoaded(gltf) {
  const car = gltf.scene;
  // Center & sit on ground
  let box = new THREE.Box3().setFromObject(car);
  const s = box.getSize(new THREE.Vector3());
  const c = box.getCenter(new THREE.Vector3());
  car.position.sub(new THREE.Vector3(c.x, box.min.y, c.z));
  scene.add(car);
  box = new THREE.Box3().setFromObject(car);
  size = Math.max(s.x, s.y, s.z);
  center.set(0, s.y * 0.42, 0);
  car.traverse(o => {
    if (o.isMesh) {
      o.castShadow = true; o.receiveShadow = true;
      const ms = Array.isArray(o.material) ? o.material : [o.material];
      ms.forEach(m => { if (m.name === 'GolfV-main_body' && !bodyMats.includes(m)) bodyMats.push(m); });
    }
  });
  car.traverse(o => { if (o.isMesh) {
    const ms = Array.isArray(o.material) ? o.material : [o.material];
    ms.forEach(m => {
      if (m.name === 'GolfV-chrome') { m.color.set('#d9dde2'); m.metalness = 1; m.roughness = 0.12; }
      if (m.name === 'GolfV-alu') { m.metalness = 0.8; m.roughness = 0.3; }
      if (m.name === 'GolfV-light_mirr') { m.color.set('#c9ced4'); m.metalness = 1; m.roughness = 0.18; }
      if (m.name === 'GolfV-glass') { m.roughness = 0.05; }
    });
  }});
  // Normales « arêtes vives » déjà calculées dans le fichier (bake.js), plus rien à faire au chargement
  // Doors: pivot nodes built at the hinge (rotation about local Z = vertical)
  // Opening parts: pivot nodes built at each hinge (doors: vertical axis, hood/tailgate: transverse axis)
  const OPEN = { FL:[zAxis,-1,62], FR:[zAxis,1,62], RL:[zAxis,-1,62], RR:[zAxis,1,62], HOOD:[xAxis,-1,52], TAIL:[xAxis,1,72] };
  Object.entries(OPEN).forEach(([k, [axis, sign, deg]]) => {
    const n = car.getObjectByName('open_' + k);
    if (n) doors[k] = { node: n, base: n.quaternion.clone(), t: 0, target: 0, axis, sign, max: THREE.MathUtils.degToRad(deg) };
  });
  applyInterior(car);
  buildTyres(car);
  car.updateMatrixWorld(true);
  buildEngineBay(car);
  buildTrunk(car);
  applyLightsAndWheels(car);
  bodyMeshes = [];
  car.traverse(o => { if (o.isMesh && o.material && o.material.name === 'GolfV-main_body') { bodyMeshes.push(o); origBody = o.material; } });
  paint = new THREE.MeshPhysicalMaterial({ name:'paint', color:'#a3121a', metalness:0.45, roughness:0.4, clearcoat:0.8, clearcoatRoughness:0.1, envMapIntensity:0.7, side: origBody.side });
  // Long axis decides "front"
  window.__longX = s.x > s.z;
  ground.scale.setScalar(size * 0.9);
  sun.position.set(size, size * 2, size * 0.8);
  const sc = sun.shadow.camera; sc.left = sc.bottom = -size; sc.right = sc.top = size; sc.far = size * 6; sc.updateProjectionMatrix();
  camera.near = size / 100; camera.far = size * 50; camera.updateProjectionMatrix();
  controls.target.copy(center);
  controls.minDistance = size * 0.6; homeMinDist = controls.minDistance;
  layout();
  controls.maxDistance = Math.max(size * 3, homeDist() * 1.6);
  setView('front3q', false);
  buildColors();
  mergeStatic(car);
  renderer.shadowMap.needsUpdate = true;
  document.getElementById('load').remove();
  window.__ready = true; if (window.KoackpitHost) { try { window.koackpitDoors(window.KoackpitHost.doors()); } catch (e) {} } window.__cam = camera; window.__controls = controls; window.__center = center;
}
function loader() { return new GLTFLoader().setMeshoptDecoder(MeshoptDecoder); }
/**
 * Pièces fixes regroupées par matière : beaucoup moins d'objets à dessiner à chaque image. Ne touche ni aux
 * ouvrants (open_*), ni aux roues, ni aux pièces qu'on peut toucher pour leur fiche, ni aux pièces cachées.
 */
// Copie en nombres à virgule « simples » (le fichier compressé stocke certaines données en entiers entrelacés)
function floatGeo(src) {
  const g = new THREE.BufferGeometry();
  for (const [k, a] of Object.entries(src.attributes)) {
    const n = a.count, size = a.itemSize, out = new Float32Array(n * size);
    for (let i = 0; i < n; i++) for (let c = 0; c < size; c++) out[i * size + c] = c === 0 ? a.getX(i) : c === 1 ? a.getY(i) : c === 2 ? a.getZ(i) : a.getW(i);
    g.setAttribute(k, new THREE.BufferAttribute(out, size));
  }
  if (src.index) g.setIndex(Array.from(src.index.array));
  return g;
}
function mergeStatic(car) {
  car.updateMatrixWorld(true);
  const groups = new Map();
  const skip = o => { for (let p = o; p; p = p.parent) { if (/^(open_|RAD_)/.test(p.name || '') || p === bayGroup) return true; } return false; };
  car.traverse(o => {
    if (!o.isMesh || o.isInstancedMesh || Array.isArray(o.material) || o.userData.info || !o.visible || skip(o)) return;
    if (o.material.transparent || o.material === paint || bodyMeshes.includes(o)) return;
    const key = o.material.uuid + '|' + Object.keys(o.geometry.attributes).sort().join(',') + '|' + (o.geometry.index ? 'i' : 'n');
    (groups.get(key) || groups.set(key, []).get(key)).push(o);
  });
  let removed = 0;
  groups.forEach(list => {
    if (list.length < 2) return;
    const geos = list.map(o => { const g = floatGeo(o.geometry); g.applyMatrix4(o.matrixWorld); return g; });
    const merged = mergeGeometries(geos, false);
    if (!merged) return;
    const m = new THREE.Mesh(merged, list[0].material);
    m.castShadow = list.some(o => o.castShadow); m.receiveShadow = list.some(o => o.receiveShadow);
    list.forEach(o => { o.parent.remove(o); }); removed += list.length - 1;
    // repère du monde → repère de la voiture
    m.applyMatrix4(car.matrixWorld.clone().invert()); car.add(m);
  });
  console.log('Golf3D : ' + removed + ' objets en moins');
}
function loadErr(err) { document.querySelector('#load div').textContent = 'Erreur de chargement du modèle'; console.error(err); }
if (EMBED) {
  prog.style.width = '30%';
  fetch('golf5.glb').then(r => r.arrayBuffer()).then(buf => { prog.style.width = '100%'; loader().parse(buf, '', onLoaded, loadErr); }).catch(loadErr);
} else {
const ms = document.createElement('script');
ms.src = 'golf5-model.js';
ms.onload = () => {
  prog.style.width = '100%';
  try {
    const bin = atob(window.GOLF_GLB); const buf = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
    window.GOLF_GLB = null;
    loader().parse(buf.buffer, '', onLoaded, loadErr);
  } catch (e) { loadErr(e); }
};
ms.onerror = loadErr;
prog.style.width = '30%';
document.head.appendChild(ms);
}

// Views: direction vectors in model space (along long axis)
function dir(name) {
  const L = window.__longX ? new THREE.Vector3(1,0,0) : new THREE.Vector3(0,0,1);
  const W = window.__longX ? new THREE.Vector3(0,0,1) : new THREE.Vector3(1,0,0);
  switch (name) {
    case 'front':  return L.clone().add(new THREE.Vector3(0,0.18,0));
    case 'back':   return L.clone().negate().add(new THREE.Vector3(0,0.18,0));
    case 'side':   return W.clone().add(new THREE.Vector3(0,0.12,0));
    case 'top':    return new THREE.Vector3(0,1,0).add(W.clone().multiplyScalar(0.02));
    default:       return L.clone().multiplyScalar(0.8).add(W.clone().multiplyScalar(0.75)).add(new THREE.Vector3(0,0.35,0));
  }
}
let anim = null;
function setView(name, smooth = true) {
  const to = center.clone().add(dir(name).normalize().multiplyScalar(name === 'top' ? homeDist(1.9) * 1.15 : homeDist()));
  if (!smooth) { camera.position.copy(to); return; }
  if (document.getElementById('info').classList.contains('show')) hideInfo(false);
  controls.minDistance = homeMinDist || controls.minDistance;
  focusAnim = { p0: camera.position.clone(), t0: controls.target.clone(), p1: to, t1: center.clone(), t: 0 };
}
document.querySelectorAll('[data-view]').forEach(b => b.onclick = () => { setRot(false); setView(b.dataset.view); });

const rotBtn = document.getElementById('rot');
function setRot(v) { controls.autoRotate = v; rotBtn.classList.toggle('on', v); }
rotBtn.onclick = () => setRot(!controls.autoRotate);
controls.addEventListener('start', () => { anim = null; focusAnim = null; setRot(false); const h = document.getElementById('hint'); if (h) h.style.opacity = 0; });

function buildColors() {
  if (!bodyMeshes.length) return;
  // « Ma Golf » d'office : le bleu nuit de la voiture de Thomas (relevé sur sa photo)
  const list = [['Ma Golf', '#0b1c4f'], ['Origine', null, '#8b9097'], ['Noir', '#08090b'], ['Gris', '#5d636b'], ['Argent', '#b9bec4'], ['Blanc', '#e9ecef'], ['Rouge', '#8e0f16']];
  const box = document.getElementById('colors');
  list.forEach(([n, hex, show], i) => {
    const b = document.createElement('button');
    b.className = 'sw' + (i === 0 ? ' on' : ''); b.title = n; b.style.background = show || hex;
    if (!hex) b.style.backgroundImage = 'linear-gradient(135deg,#9aa0a7,#5f646b)';
    b.onclick = () => {
      if (hex) paint.color.set(hex);
      bodyMeshes.forEach(o => o.material = hex ? paint : origBody);
      box.querySelectorAll('.sw').forEach(x => x.classList.remove('on')); b.classList.add('on');
    };
    box.appendChild(b);
    if (i === 0) b.onclick();
  });
}

// ---------- Interior materials: procedural fabric / grain / carpet textures (UVs + AO baked in the model)
function canvasTex(size, draw, srgb = true) {
  const c = document.createElement('canvas'); c.width = c.height = size;
  const g = c.getContext('2d'); draw(g, size);
  const t = new THREE.CanvasTexture(c);
  t.wrapS = t.wrapT = THREE.RepeatWrapping; t.anisotropy = 8;
  if (srgb) t.colorSpace = THREE.SRGBColorSpace;
  return t;
}
function rnd(seed) { let s = seed; return () => (s = (s * 16807) % 2147483647) / 2147483647; }
function noiseFill(g, n, base, amp, seed, cell = 1) {
  const r = rnd(seed), img = g.createImageData(n, n), d = img.data;
  for (let y = 0; y < n; y += cell) for (let x = 0; x < n; x += cell) {
    const v = (r() - 0.5) * amp;
    for (let yy = 0; yy < cell; yy++) for (let xx = 0; xx < cell; xx++) {
      const i = ((y + yy) * n + (x + xx)) * 4;
      d[i] = base[0] + v; d[i+1] = base[1] + v; d[i+2] = base[2] + v; d[i+3] = 255;
    }
  }
  g.putImageData(img, 0, 0);
}
// woven fabric: alternating warp/weft threads with fibre noise
function weave(g, n, base, amp, seed) {
  noiseFill(g, n, base, amp * 0.5, seed);
  const T = 8, r = rnd(seed + 3);
  for (let y = 0; y < n; y += T) for (let x = 0; x < n; x += T) {
    const over = ((x / T + y / T) % 2) === 0;
    const sh = (r() - 0.5) * amp * 0.6;
    const grd = over ? g.createLinearGradient(x, y, x, y + T) : g.createLinearGradient(x, y, x + T, y);
    const c = (k) => `rgb(${base[0] + k + sh},${base[1] + k + sh},${base[2] + k + sh})`;
    grd.addColorStop(0, c(-amp)); grd.addColorStop(0.5, c(amp * 0.4)); grd.addColorStop(1, c(-amp));
    g.globalAlpha = 0.55; g.fillStyle = grd; g.fillRect(x, y, T, T);
  }
  g.globalAlpha = 1;
}
function applyInterior(root) {
  const beige = [201, 182, 168];
  const fabricMap  = canvasTex(512, (g, n) => weave(g, n, [212, 194, 172], 22, 11));
  const fabricBump = canvasTex(512, (g, n) => weave(g, n, [128,128,128], 70, 11), false);
  const grainMap   = canvasTex(256, (g, n) => noiseFill(g, n, beige, 10, 5, 2));
  const grainBump  = canvasTex(256, (g, n) => noiseFill(g, n, [128,128,128], 90, 5, 2), false);
  const greyGrain  = canvasTex(256, (g, n) => noiseFill(g, n, [235,235,235], 18, 9, 2));
  const carpetMap  = canvasTex(256, (g, n) => noiseFill(g, n, [228,228,228], 60, 21));
  const carpetBump = canvasTex(256, (g, n) => noiseFill(g, n, [128,128,128], 160, 21), false);
  const seen = new Set();
  root.traverse(o => {
    if (!o.isMesh) return;
    const m = o.material; if (seen.has(m)) return; seen.add(m);
    const set = (map, bump, bs, rough, env) => {
      m.map = map; m.bumpMap = bump || null; m.bumpScale = bs; m.roughness = rough; m.metalness = 0; m.envMapIntensity = env; m.needsUpdate = true;
    };
    switch (m.name) {
      case 'Int-fabric':  set(fabricMap, fabricBump, 1.4, 1.0, 0.35); break;
      case 'GolfV-inside': set(grainMap, grainBump, 0.4, 0.82, 0.45); break;
      case 'Int-carpet':  set(carpetMap, carpetBump, 1.2, 1.0, 0.25); break;
      case 'Int-dash':    set(greyGrain, grainBump, 0.5, 0.7, 0.5); break;
      case 'Int-wheel':   set(greyGrain, grainBump, 0.35, 0.55, 0.6); break;
    }
  });
}

// ---------- Tyres: high-definition lathe tyres (96 segments) with tread + sidewall markings
function tyreTextures() {
  const W = 2048, H = 512;
  const col = document.createElement('canvas'); col.width = W; col.height = H;
  const bmp = document.createElement('canvas'); bmp.width = W; bmp.height = H;
  const c = col.getContext('2d'), b = bmp.getContext('2d');
  c.fillStyle = '#1b1c1e'; c.fillRect(0, 0, W, H);
  b.fillStyle = '#808080'; b.fillRect(0, 0, W, H);
  // profile (v): 0..0.33 sidewall A, 0.33..0.67 tread, 0.67..1 sidewall B
  const t0 = H * 0.36, t1 = H * 0.64;
  // tread blocks: 4 circumferential grooves + lateral sipes
  const grooves = [0.12, 0.36, 0.64, 0.88];
  for (let x = 0; x < W; x += 16) {
    const sh = (x / 16) % 2 ? 4 : -4;
    b.fillStyle = '#5a5a5a'; b.fillRect(x, t0, 3, t1 - t0);
    c.fillStyle = '#151618'; c.fillRect(x + sh, t0, 2, t1 - t0);
  }
  grooves.forEach(g => {
    const y = t0 + (t1 - t0) * g;
    b.fillStyle = '#2a2a2a'; b.fillRect(0, y - 5, W, 10);
    c.fillStyle = '#0d0e0f'; c.fillRect(0, y - 5, W, 10);
  });
  // sidewall: raised ring + lettering, both sides
  c.font = 'bold 30px Arial, sans-serif'; b.font = c.font; c.textAlign = b.textAlign = 'center';
  [[H * 0.17, 1], [H * 0.83, -1]].forEach(([y, flip]) => {
    b.fillStyle = '#9a9a9a'; b.fillRect(0, y - 34 * flip - 2, W, 4);
    for (let k = 0; k < 2; k++) {
      const x0 = W * (0.25 + k * 0.5);
      [[x0 - 260, 'TUBELESS'], [x0, '205/55 R16 91V'], [x0 + 260, 'RADIAL']].forEach(([x, t]) => {
        c.save(); b.save(); c.translate(x, y); b.translate(x, y); if (flip < 0) { c.scale(1, -1); b.scale(1, -1); }
        c.fillStyle = '#2c2d30'; c.fillText(t, 0, 10); b.fillStyle = '#b0b0b0'; b.fillText(t, 0, 10);
        c.restore(); b.restore();
      });
    }
  });
  const mk = (cv, srgb) => { const t = new THREE.CanvasTexture(cv); t.wrapS = THREE.RepeatWrapping; t.anisotropy = 8; if (srgb) t.colorSpace = THREE.SRGBColorSpace; return t; };
  return { map: mk(col, true), bump: mk(bmp, false) };
}
function buildTyres(car) {
  const tex = tyreTextures();
  const mat = new THREE.MeshStandardMaterial({ name: 'tyre-hd', map: tex.map, bumpMap: tex.bump, bumpScale: 2.2, roughness: 0.92, metalness: 0, envMapIntensity: 0.4 });
  // right-hand side: mirror the texture so the sidewall lettering reads the right way
  const texR = { map: tex.map.clone(), bump: tex.bump.clone() };
  [texR.map, texR.bump].forEach(t => { t.repeat.x = -1; t.needsUpdate = true; });
  const matR = mat.clone(); matR.map = texR.map; matR.bumpMap = texR.bump;
  const tyres = [];
  car.traverse(o => { if (o.isMesh && o.material && o.material.name === 'GolfV-tire') tyres.push(o); });
  tyres.forEach(t => {
    t.geometry.computeBoundingBox();
    const bb = t.geometry.boundingBox, c = bb.getCenter(new THREE.Vector3());
    const R = (bb.max.y - bb.min.y) / 2 * 1.04, w = (bb.max.x - bb.min.x) / 2;   // axle = local X
    const rIn = R * 0.70;
    // half profile (r, axial) from bead to tread centre, mirrored
    const half = [[rIn, 0.84], [rIn + (R - rIn) * 0.25, 0.95], [rIn + (R - rIn) * 0.55, 1.0], [rIn + (R - rIn) * 0.8, 0.96], [R * 0.985, 0.86], [R, 0.72], [R, 0.0]];
    const raw = [...half.map(([r, a]) => [r, -a * w]), ...half.slice(0, -1).reverse().map(([r, a]) => [r, a * w])];
    const curve = new THREE.CatmullRomCurve3(raw.map(([r, a]) => new THREE.Vector3(r, a, 0)));
    // Écran voiture : profil et tour un peu moins fins (le relief vient de la texture), ~3× moins de triangles
    const pts = curve.getPoints(EMBED ? 30 : 60).map(p => new THREE.Vector2(p.x, p.y));
    const g = new THREE.LatheGeometry(pts, EMBED ? 64 : 96);
    g.rotateZ(-Math.PI / 2);      // lathe axis Y -> local X (axle)
    g.translate(c.x, c.y, c.z);
    const m = new THREE.Mesh(g, c.x < 0 ? matR : mat);
    m.castShadow = m.receiveShadow = true;
    m.name = 'Pneumatique'; m.userData.info = { title: 'Pneumatique', text: 'Dimension 205/55 R16 91V. Pression : voir l\'étiquette dans la feuillure de porte conducteur ou la trappe à carburant.' };
    m.position.copy(t.position); m.quaternion.copy(t.quaternion); m.scale.copy(t.scale);
    t.parent.add(m);
  });
  car.traverse(o => { if (o.isMesh && o.material && /GolfV-tire/.test(o.material.name)) o.visible = false; });
}
// ---------- Wheels + lights materials, and lights on/off
const lightMats = { head: [], tail: [], stop: [], fog: [] };
const PART_INFO = {
  'GolfV-light_glas': ['Phare avant', 'Projecteurs halogènes avec feux de route et de croisement séparés. Le bouton Feux les allume.'],
  'GolfV-light_bl': ['Phare avant', 'Projecteurs halogènes avec feux de route et de croisement séparés. Le bouton Feux les allume.'],
  'GolfV-light_mirr': ['Phare avant', 'Projecteurs halogènes avec feux de route et de croisement séparés. Le bouton Feux les allume.'],
  'GolfV-light_lens': ['Antibrouillard avant', 'Antibrouillards intégrés au bouclier.'],
  'GolfV-rear_red': ['Feu arrière', 'Feux de position, stop, clignotant et recul dans le même bloc.'],
  'GolfV-3stop': ['3e feu stop', 'Intégré à l\'aileron du hayon.'],
  'GolfV-alu': ['Enjoliveur', 'Enjoliveur 16 pouces avec logo VW central.'],
};
function applyLightsAndWheels(car) {
  const seen = new Set();
  car.traverse(o => {
    if (!o.isMesh) return;
    const pi = PART_INFO[o.material && o.material.name];
    if (pi && !o.userData.info) o.userData.info = { title: pi[0], text: pi[1] }; const m = o.material; if (seen.has(m)) return; seen.add(m);
    switch (m.name) {
      case 'GolfV-alu':        m.color.set('#cfd3d8'); m.metalness = 0.9; m.roughness = 0.22; m.envMapIntensity = 1.2; break;
      case 'GolfV-wheel-insi': m.color.set('#2b2d31'); m.metalness = 0.6; m.roughness = 0.5; break;
      case 'GolfV-wheel-disc': m.color.set('#8d9096'); m.metalness = 0.95; m.roughness = 0.38; break;
      case 'GolfV-alu_logo':   m.metalness = 0.9; m.roughness = 0.15; break;
      case 'GolfV-light_glas': case 'GolfV-light_lens':
        m.color.set('#ffffff'); m.roughness = 0.02; m.metalness = 0; m.envMapIntensity = 2.2; m.opacity = 0.18; m.depthWrite = false; break;
      case 'GolfV-light_fram': m.color.set('#3a3d42'); m.metalness = 0.5; m.roughness = 0.45; break;
      case 'GolfV-light_bl':   m.metalness = 0.4; m.roughness = 0.12; m.envMapIntensity = 1.4; lightMats.head.push(m); break;
      case 'GolfV-light_oran': m.metalness = 0.7; m.roughness = 0.2; break;
      case 'GolfV-light_bilb': m.color.set('#f4f6fa'); m.metalness = 0.2; m.roughness = 0.2; lightMats.fog.push(m); break;
      case 'GolfV-rear_red':   m.roughness = 0.12; m.metalness = 0.05; m.envMapIntensity = 1.3; lightMats.tail.push(m); break;
      case 'GolfV-3stop':      m.roughness = 0.15; lightMats.stop.push(m); break;
    }
  });
}
let lightsOn = false;
function setLights(on) {
  wake();
  lightsOn = on; document.getElementById('lights').classList.toggle('on', on);
  lightMats.head.forEach(m => { m.emissive.set(on ? '#e8f0ff' : '#000'); m.emissiveIntensity = on ? 2.4 : 0; });
  lightMats.fog.forEach(m => { m.emissive.set(on ? '#fff6e0' : '#000'); m.emissiveIntensity = on ? 1.5 : 0; });
  lightMats.tail.forEach(m => { m.emissiveMap = on ? m.map : null; m.emissive.set(on ? '#ff2a20' : '#000'); m.emissiveIntensity = on ? 1.8 : 0; m.needsUpdate = true; });
  lightMats.stop.forEach(m => { m.emissiveMap = on ? m.map : null; m.emissive.set(on ? '#ff3030' : '#000'); m.emissiveIntensity = on ? 1.2 : 0; m.needsUpdate = true; });
}
document.getElementById('lights').onclick = () => setLights(!lightsOn);
window.setLights = setLights;

// ---------- Shared helpers for hand-built parts (local car frame: +x = left/driver, -y = front, +z = up; 1 unit ≈ 2.6 cm)
function localGroup(car) {
  const ref = car.getObjectByName('bay_tray');
  const G = new THREE.Group();
  G.position.copy(ref.position); G.quaternion.copy(ref.quaternion); G.scale.copy(ref.scale);
  ref.parent.add(G); G.updateMatrixWorld(true);
  return G;
}
function grainTex(base, amp, seed, size = 256) {
  return canvasTex(size, (g, n) => noiseFill(g, n, base, amp, seed, 1));
}
let _M;
function M() {
  if (_M) return _M;
  const bumpFine = canvasTex(256, (g, n) => noiseFill(g, n, [128,128,128], 70, 31, 1), false);
  bumpFine.repeat.set(3, 3);
  const std = (o) => new THREE.MeshStandardMaterial(Object.assign({ roughness: 0.6, metalness: 0 }, o));
  _M = {
    blackPlastic: std({ color: '#1c1d20', roughness: 0.55, bumpMap: bumpFine, bumpScale: 0.25 }),
    greyPlastic:  std({ color: '#3b3e43', roughness: 0.6, bumpMap: bumpFine, bumpScale: 0.25 }),
    rubber:       std({ color: '#121315', roughness: 0.85 }),
    alu:          std({ color: '#a7acb2', metalness: 0.85, roughness: 0.38 }),
    steel:        std({ color: '#55595f', metalness: 0.75, roughness: 0.45 }),
    chrome:       std({ color: '#dfe3e8', metalness: 1, roughness: 0.12 }),
    red:          std({ color: '#b3161c', roughness: 0.45 }),
    orange:       std({ color: '#ff7a00', roughness: 0.4 }),
    blue:         std({ color: '#1f5fd6', roughness: 0.4 }),
    yellow:       std({ color: '#f2c200', roughness: 0.45 }),
    tank:         new THREE.MeshPhysicalMaterial({ color: '#f1eee4', roughness: 0.35, transparent: true, opacity: 0.55, depthWrite: false }),
    coolant:      std({ color: '#ff4fa3', roughness: 0.2, transparent: true, opacity: 0.85 }),
    brakeFluid:   std({ color: '#d1a531', roughness: 0.2, transparent: true, opacity: 0.85 }),
    felt:         std({ color: '#141416', roughness: 1, bumpMap: bumpFine, bumpScale: 0.8 }),
    trunkCarpet:  std({ color: '#2a2b2e', roughness: 1, map: grainTex([200,200,200], 70, 41), bumpMap: bumpFine, bumpScale: 0.9 }),
    trunkTrim:    std({ color: '#34363a', roughness: 0.75, bumpMap: bumpFine, bumpScale: 0.35 }),
    lamp:         std({ color: '#fff7e6', emissive: '#fff1d0', emissiveIntensity: 0 }),
  };
  return _M;
}
function part(G, geo, mat, x, y, z, info) {
  const m = new THREE.Mesh(geo, mat); m.position.set(x, y, z);
  m.castShadow = m.receiveShadow = true;
  if (info) { m.name = info[0]; m.userData.info = { title: info[0], text: info[1] }; }
  G.add(m); return m;
}
const rbox = (w, d, h, r = 0.6, seg = 3) => new RoundedBoxGeometry(w, d, h, seg, r);       // x, y, z sizes
const cylZ = (r, h, seg = 28) => new THREE.CylinderGeometry(r, r, h, seg).rotateX(Math.PI / 2); // axis = z (vertical)
function hose(G, pts, r, mat, info) {
  const c = new THREE.CatmullRomCurve3(pts.map(p => new THREE.Vector3(...p)));
  return part(G, new THREE.TubeGeometry(c, 48, r, 14, false), mat, 0, 0, 0, info);
}
function labelPlane(G, w, h, draw, x, y, z, rotZ = 0) {
  const c = document.createElement('canvas'); c.width = 512; c.height = Math.round(512 * h / w);
  draw(c.getContext('2d'), c.width, c.height);
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 8;
  const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshStandardMaterial({ map: t, transparent: true, roughness: 0.4, metalness: 0.3 }));
  m.position.set(x, y, z); m.rotation.z = rotZ; G.add(m); return m;
}

// ---------- Engine bay: VW 1.9 TDI PD (BKC) — layout taken from a reference photo of a Golf 5 1.9 TDI
function coverTex() {
  // light grey engine cover: fine grain + "TDI" lettering + round VW badge (top face)
  const c = document.createElement('canvas'); c.width = 1024; c.height = 326; const g = c.getContext('2d');
  noiseFill(g, 1024, [118, 123, 128], 12, 77, 2);
  g.fillStyle = 'rgba(70,74,80,0.85)'; g.font = 'bold 120px Arial Black, Arial, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle';
  g.fillText('TDI', 430, 168);
  g.fillStyle = '#2a2d31'; g.beginPath(); g.arc(720, 163, 92, 0, 7); g.fill();
  g.strokeStyle = '#c9cdd2'; g.lineWidth = 12; g.beginPath(); g.arc(720, 163, 80, 0, 7); g.stroke();
  g.lineWidth = 14; g.beginPath(); g.moveTo(668, 113); g.lineTo(700, 213); g.lineTo(720, 153); g.lineTo(740, 213); g.lineTo(772, 113); g.stroke();
  g.beginPath(); g.moveTo(690, 123); g.lineTo(720, 188); g.lineTo(750, 123); g.stroke();
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 8; return t;
}
let bayGroup = null;
function buildEngineBay(car) {
  const G = localGroup(car), m = M();
  bayGroup = G;
  const grey = new THREE.MeshStandardMaterial({ color: '#6c7176', roughness: 0.6, metalness: 0.1 });
  const greyTop = new THREE.MeshStandardMaterial({ map: coverTex(), roughness: 0.55, metalness: 0.1 });
  const milky = new THREE.MeshPhysicalMaterial({ color: '#efece2', roughness: 0.4, transparent: true, opacity: 0.88 });
  const felt = new THREE.MeshStandardMaterial({ color: '#1b1c1e', roughness: 1, bumpMap: M().felt.bumpMap, bumpScale: 1.2 });
  const coolant = new THREE.MeshStandardMaterial({ color: '#f28ab8', roughness: 0.3, transparent: true, opacity: 0.6 });

  // engine block + surround (mostly hidden under the cover)
  part(G, rbox(28, 15, 7, 0.8), m.steel, -5, -56.5, 2.5, ['Bloc moteur', '1.9 TDI PD (code BKC), 4 cylindres, 105 ch, injecteurs-pompes.']);
  part(G, rbox(22, 14.5, 1.2, 0.5), m.blackPlastic, -4.2, -56.6, 5.9, ['Cache moteur', 'Cache insonorisant gris du 1.9 TDI, avec le logo TDI et le sigle VW. Se retire à la main (clips).']);
  // grey engine cover: flat rear part with TDI + VW badge, 4 raised fingers at the front
  const coverRear = part(G, rbox(18.6, 6.4, 4.2, 1.1, 4), grey, -4.0, -53.3, 8.4, ['Cache moteur', 'Cache insonorisant gris du 1.9 TDI, avec le logo TDI et le sigle VW. Se retire à la main (clips).']);
  const lab = new THREE.Mesh(new THREE.PlaneGeometry(17.6, 5.6), greyTop); lab.position.set(-4.0, -53.3, 10.52); G.add(lab);
  [-7.4, -3.7, 0.0, 3.7].forEach(x => part(G, rbox(3.0, 7.2, 4.6, 1.2, 4), grey, x, -59.3, 8.3, ['Cache moteur', 'Cache insonorisant gris du 1.9 TDI, avec le logo TDI et le sigle VW. Se retire à la main (clips).']));
  part(G, rbox(15, 7.2, 2.8, 0.8), grey, -2.0, -59.3, 7.0);
  part(G, rbox(15, 1.6, 2.2, 0.4), m.blackPlastic, -2.0, -63.4, 6.6, ['Grille de cache', 'Partie avant ajourée du cache moteur.']);
  // oil filler cap (rear right of the cover) + dipstick (front, under the cover)
  part(G, cylZ(1.6, 1.0), m.blackPlastic, -11.0, -53.6, 10.6, ['Bouchon de remplissage d\'huile', 'Huile moteur norme VW 505 01 (injecteurs-pompes), 5W-40. Contenance env. 4,3 L avec filtre.']);
  labelPlane(G, 2.4, 2.4, (g, w, h) => { g.fillStyle = '#f2c200'; g.beginPath(); g.arc(w/2, h/2, w*0.17, 0, 7); g.fill(); g.fillRect(w*0.18, h*0.44, w*0.64, h*0.12); }, -11.0, -53.6, 11.12);
  part(G, new THREE.TorusGeometry(0.85, 0.32, 10, 24).rotateX(Math.PI / 2.4), m.orange, -9.4, -63.9, 7.0, ['Jauge d\'huile', 'Poignée orange. Contrôle moteur froid, voiture à plat : niveau entre MIN et MAX.']);
  part(G, new THREE.CylinderGeometry(0.22, 0.22, 4, 10).rotateX(Math.PI / 2), m.steel, -9.4, -63.6, 4.6);
  // alternator (front right of the engine) + coolant hose
  part(G, new THREE.CylinderGeometry(2.3, 2.3, 4.2, 28).rotateZ(Math.PI / 2), m.alu, -9.0, -64.6, 3.4, ['Alternateur', 'Recharge la batterie moteur tournant.']);
  for (let k = 0; k < 7; k++) part(G, new THREE.CylinderGeometry(2.45, 2.45, 0.25, 28).rotateZ(Math.PI / 2), m.steel, -10.8 + k * 0.6, -64.6, 3.4);
  hose(G, [[-14, -62.5, 5.5], [-12, -65.5, 5], [-6, -66, 4.5], [-2, -64.5, 5]], 0.9, m.rubber, ['Durite de refroidissement', 'Circuit du liquide de refroidissement.']);

  // RIGHT side (passenger): coolant expansion tank, washer filler, fuel filter, intercooler hose
  part(G, rbox(6.4, 6.6, 7.6, 1.6, 5), milky, -20.9, -54.6, 6.6, ['Vase d\'expansion', 'Liquide de refroidissement G12+ (rose). Niveau à froid entre MIN et MAX. Ne jamais ouvrir moteur chaud.']);
  part(G, rbox(6.0, 6.2, 4.4, 1.4, 4), coolant, -20.9, -54.6, 5.0);
  part(G, cylZ(1.9, 1.4, 32), m.blue, -21.3, -53.6, 11.0);
  part(G, cylZ(1.4, 0.5, 32), m.blue, -21.3, -53.6, 11.9);
  part(G, cylZ(0.9, 5, 20), m.blackPlastic, -20.5, -58.4, 5.6);
  part(G, new THREE.CylinderGeometry(1.7, 1.5, 0.9, 32).rotateX(Math.PI / 2 - 0.35), m.blue, -20.5, -58.6, 8.4, ['Remplissage lave-glace', 'Bouchon bleu, juste devant le vase d\'expansion. Réservoir d\'environ 3 L.']);
  part(G, cylZ(2.2, 4.5, 32), m.alu, -20.3, -61.6, 4.6, ['Filtre à gasoil', 'Filtre à carburant. Remplacement environ tous les 60 000 km ; purger l\'eau régulièrement.']);
  part(G, cylZ(2.3, 0.8, 32), m.blackPlastic, -20.3, -61.6, 7.1);
  [[-21.8, -60.4], [-18.9, -62.5]].forEach(([x, y]) => part(G, rbox(0.6, 0.6, 1.4, 0.2), m.steel, x, y, 7.6));
  hose(G, [[-20.3, -61.6, 7.6], [-17.5, -60, 8.5], [-14.5, -57, 7.5]], 0.35, m.rubber);
  const ic = [[-14.5, -56, 6.5], [-15.2, -59, 6.8], [-15.6, -62, 6.0], [-15.4, -65, 3.5], [-15.2, -67, -0.5]];
  hose(G, ic, 1.35, m.rubber, ['Durite d\'intercooler', 'Air comprimé refroidi par l\'intercooler, renvoyé vers l\'admission.']);
  const icCurve = new THREE.CatmullRomCurve3(ic.map(p => new THREE.Vector3(...p)));
  for (let k = 1; k < 12; k++) { const p = icCurve.getPoint(k / 12), t = icCurve.getTangent(k / 12);
    const ring = part(G, new THREE.TorusGeometry(1.42, 0.12, 6, 20), m.rubber, p.x, p.y, p.z); ring.lookAt(p.clone().add(t)); ring.position.copy(p); }
  [0.08, 0.92].forEach(u => { const p = icCurve.getPoint(u), t = icCurve.getTangent(u);
    const cl = part(G, new THREE.TorusGeometry(1.45, 0.18, 6, 20), m.blue, p.x, p.y, p.z); cl.lookAt(p.clone().add(t)); cl.position.copy(p); });

  // LEFT side (driver): battery in its felt jacket at the bulkhead, ribbed air filter box in front of it
  part(G, rbox(10.4, 7.8, 10.2, 0.7), felt, 16.0, -53.9, 5.0, ['Batterie', 'Batterie 12 V dans sa housse isolante, côté gauche contre le tablier.']);
  part(G, rbox(9.6, 7.2, 0.5, 0.2), m.blackPlastic, 16.0, -53.9, 10.3);
  const st = labelPlane(G, 5.5, 3.4, (g, w, h) => { g.fillStyle = '#f3f3f0'; g.fillRect(0, 0, w, h); g.fillStyle = '#c0282d'; g.fillRect(0, 0, w, h * 0.22);
    g.fillStyle = '#222'; g.font = `bold ${h*0.13}px Arial`; for (let r = 0; r < 5; r++) g.fillRect(w*0.08, h*(0.34 + r*0.12), w*(0.84 - (r%2)*0.25), h*0.05); }, 21.3, -53.9, 6.0);
  st.rotation.set(Math.PI / 2, Math.PI / 2, 0);
  part(G, rbox(3.2, 2.6, 2.0, 0.4), m.blackPlastic, 9.0, -49.4, 10.0, ['Boîte à fusibles / relais', 'Alimentation principale près de la batterie.']);
  hose(G, [[9, -48.2, 10.4], [4, -47.3, 11.2], [-3, -47.2, 11.6], [-10, -47.5, 11.0]], 0.35, m.rubber);
  hose(G, [[10, -48.6, 10.2], [12.5, -48.2, 11.6], [16, -50, 10.6]], 0.35, m.red);
  const air = part(G, rbox(16, 5.6, 7.6, 1.0, 4), m.blackPlastic, 16.2, -60.2, 5.2, ['Boîtier de filtre à air', 'Filtre à air à remplacer environ tous les 60 000 km.']);
  for (let k = 0; k < 6; k++) part(G, rbox(9.5, 0.45, 0.6, 0.15), m.greyPlastic, 18.6, -58.0 - k * 0.85, 9.15);
  labelPlane(G, 2.4, 2.4, (g, w, h) => { g.strokeStyle = '#3b3e43'; g.lineWidth = 18; g.beginPath(); g.arc(w/2, h/2, w*0.38, 0, 7); g.stroke();
    g.lineWidth = 16; g.beginPath(); g.moveTo(w*0.3, h*0.3); g.lineTo(w*0.43, h*0.72); g.lineTo(w*0.5, h*0.5); g.lineTo(w*0.57, h*0.72); g.lineTo(w*0.7, h*0.3); g.stroke(); }, 11.4, -59.6, 9.02);
  [8.6, 23.8].forEach(x => part(G, rbox(0.7, 1.2, 2.2, 0.2), m.greyPlastic, x, -60.2, 6.6));
  // air path: corrugated duct up the left of the cover, big smooth intake pipe across the front
  hose(G, [[9.2, -50.5, 9.5], [9.0, -54, 9.2], [8.8, -58, 8.2], [9.0, -61.5, 6.0]], 1.25, m.rubber, ['Conduit d\'air (débitmètre)', 'Air filtré vers le turbo ; le débitmètre mesure la quantité d\'air aspirée.']);
  part(G, new THREE.CylinderGeometry(1.4, 1.4, 2.6, 20).rotateX(Math.PI / 2), m.blackPlastic, 9.0, -52.5, 9.6, ['Débitmètre d\'air', 'Capteur de masse d\'air (MAF).']);
  hose(G, [[13, -63.4, 3.6], [8, -64.6, 3.6], [3, -64.3, 4.4], [0, -62.6, 5.0]], 1.75, m.rubber, ['Durite d\'admission', 'Gros conduit d\'air entre le filtre et le moteur.']);
  part(G, new THREE.CylinderGeometry(2.4, 2.4, 3, 24).rotateZ(Math.PI / 2), m.steel, -2, -48.6, 3.6, ['Turbocompresseur', 'Turbo à géométrie variable, côté tablier.']);

  // FRONT: large black carrier panel with A/C stickers and the bonnet latch
  part(G, rbox(30.6, 7.6, 1.2, 0.5), m.blackPlastic, 0, -70.0, 7.4, ['Traverse avant', 'Porte la serrure de capot, le haut du radiateur et les étiquettes de climatisation (R134a).']);
  for (let k = 0; k < 11; k++) [-1, 1].forEach(sx => part(G, rbox(0.35, 3.4, 0.4, 0.1), m.greyPlastic, sx * (8 + k * 0.62), -71.6, 8.05));
  part(G, rbox(3.6, 2.2, 1.4, 0.3), m.steel, 0, -72.4, 8.3, ['Serrure de capot', 'Ouverture par la manette côté conducteur, puis le crochet de sécurité.']);
  const sticker = (w, h, x, y, draw) => labelPlane(G, w, h, draw, x, y, 8.03);
  sticker(3.0, 2.2, -12, -67.7, (g, w, h) => { g.fillStyle = '#f2efe6'; g.fillRect(0, 0, w, h); g.fillStyle = '#333'; g.font = `${h*0.16}px Arial`; g.fillText('AC   R134a', w*0.08, h*0.3); g.fillText('525 g', w*0.45, h*0.7); });
  sticker(2.6, 1.8, 0.4, -67.6, (g, w, h) => { g.fillStyle = '#ffffff'; g.fillRect(0, 0, w, h); g.fillStyle = '#1a66c9'; g.fillRect(w*0.06, h*0.1, w*0.88, h*0.8); g.fillStyle = '#fff'; g.fillRect(w*0.15, h*0.45, w*0.7, h*0.12); });
  sticker(5.2, 3.0, 10.6, -67.9, (g, w, h) => { g.fillStyle = '#ece9df'; g.fillRect(0, 0, w, h); g.strokeStyle = '#333'; g.lineWidth = 6; g.beginPath(); g.arc(w*0.14, h*0.5, h*0.25, 0, 7); g.stroke();
    g.fillStyle = '#444'; for (let r = 0; r < 6; r++) g.fillRect(w*0.3, h*(0.2 + r*0.11), w*0.62, h*0.04); });

  // bonnet insulation pad, attached to the bonnet so it opens with it
  const hood = car.getObjectByName('open_HOOD');
  if (hood) {
    const zAt = y => [[-74, 8.6], [-70, 9.6], [-66, 11.1], [-62, 12.6], [-58, 13.3], [-54, 13.7], [-50, 14.6], [-46, 15.2], [-38, 17.6]].reduce((a, [yy, z], i, arr) => {
      if (i === 0) return a; const [y0, z0] = arr[i - 1]; return (y >= y0 && y <= yy) ? z0 + (z - z0) * (y - y0) / (yy - y0) : a; }, 8.6);
    const geo = new THREE.PlaneGeometry(44, 30, 22, 30);
    const pos = geo.attributes.position;
    for (let i = 0; i < pos.count; i++) { const y = -68 + (pos.getY(i) + 15) / 30 * 29; const x = pos.getX(i); pos.setXYZ(i, x, y, zAt(y) - 0.35 - 0.0025 * x * x); }
    geo.computeVertexNormals();
    const pad = part(G, geo, m.felt, 0, 0, 0, ['Isolant de capot', 'Tapis insonorisant sous le capot.']);
    pad.material = m.felt.clone(); pad.material.side = THREE.DoubleSide;
    G.updateMatrixWorld(true); hood.attach(pad);
  }
}

// ---------- Boot: carpeted floor, side trims over the wheel arches, sill, hooks, lamp, net, tailgate trim
let trunkLamp = null;
function buildTrunk(car) {
  const G = localGroup(car), m = M();
  part(G, rbox(51, 25, 0.8, 0.3), m.trunkCarpet, 0, 61.2, 2.0, ['Plancher de coffre', 'Moquette sur plancher amovible ; roue de secours ou kit anti-crevaison dessous.']);
  part(G, rbox(52, 0.8, 18, 0.3), m.trunkCarpet, 0, 48.9, 11, ['Dossier de banquette', 'Banquette rabattable 1/3 – 2/3 pour agrandir le coffre.']);
  [1, -1].forEach(sx => {
    // side trim follows the body: full height up to the light clusters, then steps inwards around them
    part(G, rbox(1.2, 13.5, 18.5, 0.4), m.trunkTrim, sx * 26.3, 55.6, 11, ['Garniture latérale', 'Habillage du coffre.']);
    part(G, rbox(3.4, 0.8, 18.5, 0.3), m.trunkTrim, sx * 25.0, 62.6, 11);
    part(G, rbox(1.0, 10.0, 8.4, 0.35), m.trunkTrim, sx * 23.6, 67.6, 6.0);
    part(G, rbox(6.2, 15, 8.8, 2.2, 5), m.trunkTrim, sx * 23.2, 56.5, 6.0, ['Passage de roue', 'Habillage du passage de roue arrière.']);
    part(G, rbox(5.0, 12, 0.6, 0.2), m.trunkTrim, sx * 23.9, 55.8, 19.3);
  });
  part(G, rbox(52, 2.2, 4.4, 0.5), m.blackPlastic, 0, 73.8, 2.8, ['Seuil de chargement', 'Protège la peinture lors du chargement.']);
  part(G, rbox(46, 1.6, 0.25, 0.1), m.chrome, 0, 73.6, 5.05);
  [[-19, 51], [19, 51], [-19, 71.5], [19, 71.5]].forEach(([x, y]) => {
    part(G, new THREE.TorusGeometry(0.7, 0.16, 8, 20, Math.PI), m.chrome, x, y, 2.4, ['Anneau d\'arrimage', 'Pour fixer un filet ou des sangles.']);
  });
  part(G, rbox(5, 0.8, 0.3, 0.15), m.blackPlastic, 0, 72.4, 2.5, ['Poignée de plancher', 'Soulève le plancher pour accéder à la roue de secours.']);
  trunkLamp = part(G, rbox(2.8, 0.25, 1.2, 0.1), m.lamp.clone(), 25.6, 57, 15.5, ['Éclairage de coffre', 'S\'allume à l\'ouverture du hayon.']);
  trunkLamp.rotation.z = Math.PI / 2;
  const net = labelPlane(G, 9, 6, (g, w, h) => {
    g.strokeStyle = 'rgba(20,20,22,0.95)'; g.lineWidth = 3;
    for (let i = -h; i < w; i += 22) { g.beginPath(); g.moveTo(i, 0); g.lineTo(i + h, h); g.stroke(); g.beginPath(); g.moveTo(i + h, 0); g.lineTo(i, h); g.stroke(); }
    g.lineWidth = 10; g.strokeRect(0, 0, w, h);
  }, -25.6, 55.5, 10);
  net.rotation.set(Math.PI / 2, -Math.PI / 2, 0); net.name = 'Filet de rangement'; net.userData.info = { title: 'Filet de rangement', text: 'Pour les petits objets.' };
  // tailgate inner trim panel, attached to the tailgate
  const tail = car.getObjectByName('open_TAIL');
  if (tail) {
    // lower panel: flat, just inside the tailgate skin (y = 71.6), narrowed between the tail lights
    const sh = new THREE.Shape([[-21, 6.2], [21, 6.2], [21.5, 11.2], [16.3, 12.0], [16.0, 19.4], [-16.0, 19.4], [-16.3, 12.0], [-21.5, 11.2]].map(([x, z]) => new THREE.Vector2(x, z)));
    const geo = new THREE.ShapeGeometry(sh);
    const pos = geo.attributes.position;
    for (let i = 0; i < pos.count; i++) { const X = pos.getX(i), Y = pos.getY(i); pos.setXYZ(i, X, 71.6, Y); }
    // upper strip up to the rear window
    const up = new THREE.BufferGeometry();
    up.setAttribute('position', new THREE.Float32BufferAttribute([-16, 71.6, 19.4, 16, 71.6, 19.4, 26.5, 66.6, 20.9, -26.5, 66.6, 20.9], 3));
    up.setIndex([0, 1, 2, 0, 2, 3]);
    geo.computeVertexNormals(); up.computeVertexNormals();
    const trimUp = part(G, up, m.trunkTrim, 0, 0, 0);
    const trim = part(G, geo, m.trunkTrim, 0, 0, 0, ['Garniture de hayon', 'Habillage intérieur du hayon avec poignée de fermeture.']);
    trim.material = m.trunkTrim.clone(); trim.material.side = THREE.DoubleSide;
    const handle = part(G, rbox(7, 1.0, 1.4, 0.4), m.blackPlastic, 0, 71.0, 7.6);
    trimUp.material = trim.material;
    G.updateMatrixWorld(true); tail.attach(trim); tail.attach(trimUp); tail.attach(handle);
  }
}

// ---------- Tap a part: fly the camera to it and show its card
const ray = new THREE.Raycaster(), ndc = new THREE.Vector2();
let focusAnim = null, highlighted = null, homeMinDist = 0;
function isShown(o) { while (o) { if (!o.visible) return false; o = o.parent; } return true; }
function infoOf(o) { while (o) { if (o.userData && o.userData.info) return o; o = o.parent; } return null; }
function highlight(mesh) {
  if (highlighted) { highlighted.material = highlighted.userData.origMat; highlighted = null; }
  if (!mesh || !mesh.isMesh) return;
  mesh.userData.origMat = mesh.material;
  const m = mesh.material.clone(); m.emissive = new THREE.Color('#3d8bff'); m.emissiveIntensity = 0.3;
  mesh.material = m; highlighted = mesh;
}
function flyTo(target, dist) {
  const dir = camera.position.clone().sub(controls.target).normalize();
  if (dir.y < 0.25) { dir.y = 0.35; dir.normalize(); }
  focusAnim = { p0: camera.position.clone(), t0: controls.target.clone(), p1: target.clone().add(dir.multiplyScalar(dist)), t1: target.clone(), t: 0 };
}
function showInfo(obj) {
  wake();
  const box = new THREE.Box3().setFromObject(obj), c = box.getCenter(new THREE.Vector3());
  const r = Math.max(box.getSize(new THREE.Vector3()).length(), size * 0.05);
  controls.minDistance = Math.min(homeMinDist, r * 1.2);
  flyTo(c, Math.max(r * 3.4, size * 0.3));
  highlight(obj);
  const card = document.getElementById('info');
  card.querySelector('h2').textContent = obj.userData.info.title;
  card.querySelector('p').textContent = obj.userData.info.text;
  card.classList.add('show');
}
function hideInfo(goHome = true) {
  document.getElementById('info').classList.remove('show'); highlight(null);
  if (goHome) { controls.minDistance = homeMinDist; flyTo(center, homeDist()); }
}
document.getElementById('infoBack').onclick = () => hideInfo(true);
let down = null;
renderer.domElement.addEventListener('pointerdown', e => { down = { x: e.clientX, y: e.clientY, t: performance.now() }; });
renderer.domElement.addEventListener('pointerup', e => {
  if (!down || Math.hypot(e.clientX - down.x, e.clientY - down.y) > 6 || performance.now() - down.t > 450) return;
  ndc.set(e.clientX / innerWidth * 2 - 1, -(e.clientY / innerHeight) * 2 + 1);
  ray.setFromCamera(ndc, camera);
  const hit = ray.intersectObjects(scene.children, true).find(h => isShown(h.object) && !(h.object.material && h.object.material.transparent && h.object.material.opacity < 0.3));
  const obj = hit && infoOf(hit.object);
  if (obj) { setRot(false); showInfo(obj); }
  else if (document.getElementById('info').classList.contains('show')) hideInfo(false);
});
window.focusPart = name => { const o = scene.getObjectByName(name); if (o && o.userData.info) showInfo(o); };

function setDoor(k, open) {
  const d = doors[k]; if (!d) return;
  d.target = open ? 1 : 0;
  document.querySelector(`[data-door="${k}"]`).classList.toggle('on', !!open);
  if (open && (k === 'HOOD' || k === 'TAIL')) { const t = document.getElementById('tip'); t.classList.add('show'); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('show'), 3500); }
  if (!open && (k === 'HOOD' || k === 'TAIL')) hideInfo(false);
  const all = Object.values(doors).every(x => x.target === 1);
  document.getElementById('allDoors').classList.toggle('on', all);
}
document.querySelectorAll('[data-door]').forEach(b => b.onclick = () => setDoor(b.dataset.door, doors[b.dataset.door]?.target !== 1));
document.getElementById('allDoors').onclick = () => {
  const open = !Object.values(doors).every(x => x.target === 1);
  Object.keys(doors).forEach(k => setDoor(k, open));
};
// API for Koackpit (CAN data): setDoors({FL:true, FR:false, RL:false, RR:false, HOOD:false, TAIL:false})
window.setDoors = st => { Object.entries(st).forEach(([k, v]) => setDoor(k, v)); wake(); };
// Masque CAN Koackpit : 1 AVG, 2 AVD, 4 ARG, 8 ARD, 16 coffre, 32 capot
window.koackpitDoors = m => setDoors({ FL: !!(m & 1), FR: !!(m & 2), RL: !!(m & 4), RR: !!(m & 8), TAIL: !!(m & 16), HOOD: !!(m & 32) });
const qz = new THREE.Quaternion();
function ease(t) { return t < .5 ? 4*t*t*t : 1 - Math.pow(-2*t + 2, 3) / 2; }

function layout() {
  const W = innerWidth, H = innerHeight, barH = EMBED ? 0 : document.getElementById('bar').offsetHeight + 24;
  camera.aspect = W / (H + barH);
  camera.setViewOffset(W, H + barH, 0, barH, W, H);
  camera.updateProjectionMatrix();
}
function homeDist(mult = 1.55) {
  const v = THREE.MathUtils.degToRad(camera.fov) / 2, h = Math.atan(Math.tan(v) * camera.aspect);
  return Math.max(size * mult, size * 0.58 / Math.tan(Math.min(v, h)));
}
addEventListener('resize', () => {
  layout();
  renderer.setSize(innerWidth, innerHeight);
});

const clock = new THREE.Clock();
let idle = 0; function wake() { idle = 0; }
['pointerdown', 'pointermove', 'wheel'].forEach(e => renderer.domElement.addEventListener(e, wake, { passive: true }));
addEventListener('resize', wake);
renderer.setAnimationLoop(() => {
  const dt = Math.min(clock.getDelta(), 0.1);
  if (focusAnim) {
    focusAnim.t = Math.min(1, focusAnim.t + dt / 0.85); const k = ease(focusAnim.t);
    camera.position.lerpVectors(focusAnim.p0, focusAnim.p1, k);
    controls.target.lerpVectors(focusAnim.t0, focusAnim.t1, k);
    if (focusAnim.t >= 1) focusAnim = null;
  }
  if (anim) {
    anim.t = Math.min(1, anim.t + dt / 0.8);
    const k = 1 - Math.pow(1 - anim.t, 3);
    // spherical interpolation around target for a nice arc
    const a = anim.from.clone().sub(center), b = anim.to.clone().sub(center);
    const sa = new THREE.Spherical().setFromVector3(a), sb = new THREE.Spherical().setFromVector3(b);
    let dt = sb.theta - sa.theta; if (dt > Math.PI) dt -= 2*Math.PI; if (dt < -Math.PI) dt += 2*Math.PI;
    const sp = new THREE.Spherical(sa.radius + (sb.radius - sa.radius)*k, sa.phi + (sb.phi - sa.phi)*k, sa.theta + dt*k);
    camera.position.copy(center).add(new THREE.Vector3().setFromSpherical(sp));
    if (anim.t >= 1) anim = null;
  }
  for (const d of Object.values(doors)) {
    if (d.t !== d.target) {
      d.t = d.target > d.t ? Math.min(d.target, d.t + dt / 0.9) : Math.max(d.target, d.t - dt / 0.7);
      qz.setFromAxisAngle(d.axis, d.sign * d.max * ease(d.t));
      d.node.quaternion.copy(d.base).multiply(qz);
      renderer.shadowMap.needsUpdate = true;
    }
  }
  // Compartiment moteur dessiné seulement capot ouvert (invisible sinon)
  if (bayGroup) bayGroup.visible = !doors.HOOD || doors.HOOD.t > 0 || doors.HOOD.target > 0;
  if (doors.TAIL) {
    const shelf = scene.getObjectByName('open_SHELF');
    if (shelf) {
      if (!shelf.userData.base) shelf.userData.base = shelf.quaternion.clone();
      qz.setFromAxisAngle(xAxis, THREE.MathUtils.degToRad(26) * ease(doors.TAIL.t));
      shelf.quaternion.copy(shelf.userData.base).multiply(qz);
    }
    if (trunkLamp) trunkLamp.material.emissiveIntensity = doors.TAIL.t > 0.15 ? 2.0 : 0;
  }
  const moved = controls.update();
  const busy = moved || anim || focusAnim || controls.autoRotate || Object.values(doors).some(d => d.t !== d.target);
  idle = busy ? 0 : idle + 1;
  if (idle > 45) return;
  renderer.render(scene, camera);
});
