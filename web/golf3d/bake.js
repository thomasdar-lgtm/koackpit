// Préparation hors ligne du modèle (web/golf3d/build-model.sh) : normales « à arêtes vives » calculées une fois
// pour toutes (au lieu de chaque ouverture de l'écran voiture), puis géométrie réindexée et exportée.
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';
import { toCreasedNormals, mergeVertices } from 'three/addons/utils/BufferGeometryUtils.js';
window.bake = async (url) => {
  const buf = await (await fetch(url)).arrayBuffer();
  const gltf = await new Promise((ok, ko) => new GLTFLoader().parse(buf, '', ok, ko));
  let before = 0, after = 0;
  gltf.scene.traverse(o => {
    if (o.isMesh && o.geometry.index) {
      const soft = { 'Int-fabric': 85, 'Int-carpet': 70, 'GolfV-inside': 60 }[o.material.name] || 40;
      before += o.geometry.attributes.position.count;
      const g = mergeVertices(toCreasedNormals(o.geometry, THREE.MathUtils.degToRad(soft)), 1e-5);
      o.geometry.dispose(); o.geometry = g;
      after += g.attributes.position.count;
    }
  });
  const glb = await new GLTFExporter().parseAsync(gltf.scene, { binary: true });
  const b = new Uint8Array(glb); let s = '';
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000));
  return { before, after, b64: btoa(s) };
};
