import React from 'react';
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { STATUS_LABEL, type MapData, type NodeHold, type VehicleHeading, type VehicleSnapshot } from '../../api';
import { AGV, CALL_OFF, CALL_ON, CARRIER_OFF, CARRIER_ON, DESTINATION, HELD, OHT, layerOfNode, vehicleLook, callable } from './MapView';
import { nodeHeading, portTitle, segmentBetween, segmentPoints, vehiclePlaces, vehiclePoint } from './geometry';
import { MotionBuffer } from './motion';

/** Map coordinates are millimetres; the scene is in metres. */
const S = 1 / 1000;
/** Height of the overhead rail. OHT hangs below it, AGV runs on the floor. */
const RAIL = 3.2;
const OHT_BODY = { l: 0.9, h: 0.55, w: 0.5 };
const AGV_BODY = { l: 1.0, h: 0.32, w: 0.7 };
const SELECT = '#172231';
const TARGET = '#E0A100';

type Layer = 'all' | 'OHT' | 'AGV';
type Props = {
  map: MapData;
  vehicles: VehicleSnapshot[];
  selectedId: number | null;
  onSelect: (vehicle: VehicleSnapshot) => void;
  layer: Layer;
  targetNodeId?: number | null;
  onNodeSelect?: (nodeId: number) => void;
  holds?: NodeHold[];
  headings?: VehicleHeading[];
};

// ---- optional vehicle models ----

/**
 * A site can drop its own models in as `public/models/oht.glb` and `public/models/agv.glb`. They
 * are scaled to the vehicle's length and stood on the floor (AGV) or hung from the rail (OHT),
 * facing +X. Without them the built-in box shapes are used.
 */
type Models = { OHT: THREE.Object3D | null; AGV: THREE.Object3D | null };
let modelsPromise: Promise<Models> | null = null;
function loadModels(): Promise<Models> {
  if (!modelsPromise) {
    const loader = new GLTFLoader();
    const one = async (name: string) => {
      const url = `/models/${name}.glb`;
      try {
        // A missing file comes back as the app's own page, not as a 404, in the dev server.
        const head = await fetch(url, { method: 'HEAD' });
        if (!head.ok || (head.headers.get('content-type') ?? '').includes('text/html')) return null;
        return (await loader.loadAsync(url)).scene;
      } catch {
        return null;
      }
    };
    modelsPromise = Promise.all([one('oht'), one('agv')]).then(([oht, agv]) => ({ OHT: oht, AGV: agv }));
  }
  return modelsPromise;
}

/** A copy of the model, its longest floor side scaled to `length`, centred, bottom at y = 0. */
function fitted(model: THREE.Object3D, length: number): { object: THREE.Object3D; height: number } {
  const copy = model.clone(true);
  const size = new THREE.Box3().setFromObject(copy).getSize(new THREE.Vector3());
  copy.scale.multiplyScalar(length / Math.max(size.x, size.z, 1e-6));
  const box = new THREE.Box3().setFromObject(copy);
  const centre = box.getCenter(new THREE.Vector3());
  copy.position.sub(new THREE.Vector3(centre.x, box.min.y, centre.z));
  copy.traverse(o => { if ((o as THREE.Mesh).isMesh) o.castShadow = true; });
  const wrap = new THREE.Group();
  wrap.add(copy);
  return { object: wrap, height: box.max.y - box.min.y };
}

// ---- labels ----

/** A text label that always faces the camera. `dot` draws a status dot before the text. */
function makeLabel(text: string, opts: { dot?: string; dark?: boolean; size?: number; carrier?: boolean }): THREE.Sprite {
  const px = 44, padX = 22, dotW = opts.dot ? 40 : 0, carrierW = opts.carrier === undefined ? 0 : 56;
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d')!;
  ctx.font = `600 ${px}px ui-monospace, Consolas, monospace`;
  const width = Math.ceil(ctx.measureText(text).width) + padX * 2 + dotW + carrierW;
  canvas.width = width; canvas.height = 72;
  ctx.font = `600 ${px}px ui-monospace, Consolas, monospace`;
  ctx.fillStyle = opts.dark ? 'rgba(23,34,49,0.92)' : 'rgba(255,255,255,0.92)';
  ctx.beginPath(); ctx.roundRect(0, 0, width, 72, 16); ctx.fill();
  if (!opts.dark) { ctx.strokeStyle = '#C9D3E0'; ctx.lineWidth = 3; ctx.stroke(); }
  if (opts.dot) {
    ctx.fillStyle = opts.dot;
    ctx.beginPath(); ctx.arc(padX + 12, 36, 12, 0, Math.PI * 2); ctx.fill();
  }
  ctx.fillStyle = opts.dark ? '#fff' : '#42525F';
  ctx.textBaseline = 'middle';
  ctx.fillText(text, padX + dotW, 38);
  if (opts.carrier !== undefined) {
    // Carrier on board: green; none: the inactive colour — the same round mark as in 2D.
    ctx.fillStyle = opts.carrier ? CARRIER_ON : CARRIER_OFF;
    ctx.strokeStyle = '#ffffff'; ctx.lineWidth = 4;
    ctx.beginPath(); ctx.arc(width - padX - 20, 36, 20, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
  }
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, depthTest: false, transparent: true }));
  const h = opts.size ?? 0.34;
  sprite.scale.set(h * width / 72, h, 1);
  sprite.renderOrder = 10;
  return sprite;
}

// ---- the scene ----

const statusOf = (v: VehicleSnapshot) => v.status ?? (v.idle ? 'IDLE' : 'MOVING');
const modeLetter = (v: VehicleSnapshot) => v.mode === 'REMOTE' ? 'R' : v.mode === 'SEMI_AUTO' ? 'S' : v.mode === 'MANUAL' ? 'M' : '?';

type VehicleObject = {
  group: THREE.Group;
  type: 'OHT' | 'AGV';
  shell: THREE.MeshStandardMaterial;
  outline: THREE.LineBasicMaterial;
  band: THREE.MeshStandardMaterial;
  lamp: THREE.MeshStandardMaterial;
  marker: THREE.MeshBasicMaterial;
  ring: THREE.Mesh;
  carrier: THREE.Object3D;
  carrierMark: THREE.MeshStandardMaterial;
  label: THREE.Sprite | null;
  labelKey: string;
  labelY: number;
  target: THREE.Vector3;
  yaw: number;
  targetYaw: number;
  placed: boolean;
};

function disposeTree(root: THREE.Object3D) {
  root.traverse(o => {
    const mesh = o as THREE.Mesh;
    mesh.geometry?.dispose();
    const materials = Array.isArray(mesh.material) ? mesh.material : mesh.material ? [mesh.material] : [];
    for (const m of materials) {
      (m as THREE.SpriteMaterial).map?.dispose();
      m.dispose();
    }
  });
}

class World {
  readonly renderer: THREE.WebGLRenderer;
  readonly scene = new THREE.Scene();
  readonly camera = new THREE.PerspectiveCamera(45, 1, 0.1, 2000);
  readonly controls: OrbitControls;
  private layout = new THREE.Group();
  private fleet = new THREE.Group();
  private targetMark: THREE.Mesh;
  private vehicles = new Map<number, VehicleObject>();
  private nodePos = new Map<number, THREE.Vector3>();
  private map: MapData | null = null;
  private motion = new MotionBuffer();
  private places = new THREE.Group();
  private placesKey = '';
  /** The vehicle the camera is riding with, and how far behind it the camera sits. */
  private following: number | null = null;
  private followDistance = 12;
  /** Set when a ride begins, so the first frame places the camera instead of flying it there. */
  private followSnap = false;
  /** Where a zoom step is heading, so it slides there instead of jumping. */
  private zoomGoal: number | null = null;
  private overlays = new THREE.Group();
  private overlaysKey = '';
  private pickable: THREE.Object3D[] = [];
  private centre = new THREE.Vector2();
  private span = 20;
  private models: Models = { OHT: null, AGV: null };
  private frame = 0;
  private last = performance.now();
  private resize: ResizeObserver;

  constructor(private host: HTMLDivElement) {
    this.renderer = new THREE.WebGLRenderer({ antialias: true });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    host.appendChild(this.renderer.domElement);
    this.renderer.domElement.style.display = 'block';

    // A mid-tone ground, so the white vehicles stand out from it.
    this.scene.background = new THREE.Color('#C3CCD7');
    this.scene.add(new THREE.HemisphereLight('#ffffff', '#B8C2CE', 1.6));
    const sun = new THREE.DirectionalLight('#ffffff', 1.4);
    sun.position.set(-12, 25, 14);
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    this.scene.add(sun, sun.target);
    this.scene.add(this.layout, this.fleet);

    this.targetMark = new THREE.Mesh(new THREE.TorusGeometry(0.32, 0.04, 8, 40),
      new THREE.MeshBasicMaterial({ color: TARGET }));
    this.targetMark.rotation.x = -Math.PI / 2;
    this.targetMark.visible = false;
    this.scene.add(this.targetMark);

    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = true;
    this.controls.maxPolarAngle = Math.PI * 0.49;
    this.controls.minDistance = 2;
    // The wheel zooms towards what the pointer is over, not towards the middle of the screen. On a
    // site this size the middle is usually empty floor, and zooming at it walks away from the thing
    // being looked at.
    this.controls.zoomToCursor = true;
    this.controls.zoomSpeed = 1.1;

    // While following a vehicle the camera is driven, so the wheel sets how far behind it sits.
    this.renderer.domElement.addEventListener('wheel', event => {
      if (this.following == null) return;
      event.preventDefault();
      this.followDistance = this.clampDistance(this.followDistance * (event.deltaY > 0 ? 1.12 : 1 / 1.12));
    }, { passive: false });

    this.resize = new ResizeObserver(() => this.fit());
    this.resize.observe(host);
    this.fit();
    const tick = () => {
      this.frame = requestAnimationFrame(tick);
      this.animate();
    };
    tick();
  }

  private fit() {
    const w = this.host.clientWidth || 600, h = this.host.clientHeight || 400;
    this.renderer.setSize(w, h, false);
    this.renderer.domElement.style.width = '100%';
    this.renderer.domElement.style.height = '100%';
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
  }

  /** Map point to scene point at height y. Map y grows downwards, as scene z does seen from the front. */
  private at(x: number, y: number, height = 0) {
    return new THREE.Vector3(x * S - this.centre.x, height, y * S - this.centre.y);
  }

  setModels(models: Models) {
    this.models = models;
    // Rebuild every vehicle with the new bodies; positions are taken over.
    for (const [id, obj] of this.vehicles) {
      this.fleet.remove(obj.group);
      disposeTree(obj.group);
      this.vehicles.delete(id);
    }
  }

  setLayout(map: MapData, layer: Layer) {
    this.fleet.clear();
    for (const obj of this.vehicles.values()) disposeTree(obj.group);
    this.vehicles.clear();
    this.layout.parent?.remove(this.layout);
    disposeTree(this.layout);
    this.layout = new THREE.Group();
    this.scene.add(this.layout);
    this.pickable = [];
    this.nodePos.clear();
    this.map = map;
    this.placesKey = '';
    if (map.nodes.length === 0) return;

    const xs = map.nodes.map(n => n.x * S), ys = map.nodes.map(n => n.y * S);
    const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
    this.centre.set((minX + maxX) / 2, (minY + maxY) / 2);
    this.span = Math.max(maxX - minX, maxY - minY, 6);
    const visible = (t: 'OHT' | 'AGV') => layer === 'all' || layer === t;

    // Floor and grid, a metre per cell.
    const floorW = maxX - minX + 8, floorD = maxY - minY + 8;
    const floor = new THREE.Mesh(new THREE.PlaneGeometry(floorW, floorD),
      new THREE.MeshStandardMaterial({ color: '#D3DAE2', roughness: 0.95 }));
    floor.rotation.x = -Math.PI / 2;
    floor.receiveShadow = true;
    this.layout.add(floor);
    const cells = Math.ceil(Math.max(floorW, floorD));
    const grid = new THREE.GridHelper(cells, cells, '#AEB9C6', '#C2CBD5');
    grid.position.y = 0.002;
    this.layout.add(grid);

    for (const n of map.nodes) {
      const t = layerOfNode(map, n.id);
      this.nodePos.set(n.id, this.at(n.x, n.y, t === 'OHT' ? RAIL : 0));
    }

    const railMat = new THREE.MeshStandardMaterial({ color: OHT, metalness: 0.3, roughness: 0.5 });
    const railOff = new THREE.MeshStandardMaterial({ color: OHT, transparent: true, opacity: 0.3 });
    const pathMat = new THREE.MeshBasicMaterial({ color: AGV });
    const pathOff = new THREE.MeshBasicMaterial({ color: AGV, transparent: true, opacity: 0.3 });
    for (const e of map.edges) {
      const na = map.nodes.find(n => n.id === e.fromNodeId), nb = map.nodes.find(n => n.id === e.toNodeId);
      const t = layerOfNode(map, e.fromNodeId);
      if (!na || !nb || !visible(t)) continue;
      // A curved segment is drawn as short straight pieces along the same curve the 2D plan uses.
      const points = segmentPoints(na.x, na.y, nb.x, nb.y, e.curveMm ?? 0).map(p => this.at(p.x, p.y));
      for (let k = 0; k < points.length - 1; k++) {
        const a = points[k], b = points[k + 1];
        const dx = b.x - a.x, dz = b.z - a.z, length = Math.hypot(dx, dz);
        const yaw = Math.atan2(-dz, dx);
        if (t === 'OHT') {
          // The overhead rail: a beam at rail height. Pieces overlap a little so a curve has no gaps.
          const beam = new THREE.Mesh(new THREE.BoxGeometry(length + 0.02, 0.08, 0.14), e.enabled ? railMat : railOff);
          beam.position.set((a.x + b.x) / 2, RAIL + 0.04, (a.z + b.z) / 2);
          beam.rotation.y = yaw;
          this.layout.add(beam);
        } else if (points.length > 2) {
          // A curved floor route: painted solid in short pieces, every other one, so it reads dashed.
          if (k % 2 === 1) continue;
          const piece = new THREE.Mesh(new THREE.BoxGeometry(length, 0.01, 0.1), e.enabled ? pathMat : pathOff);
          piece.position.set((a.x + b.x) / 2, 0.006, (a.z + b.z) / 2);
          piece.rotation.y = yaw;
          this.layout.add(piece);
        } else {
          // The floor route: a dashed line painted on the floor.
          const dash = 0.5, gap = 0.3;
          for (let s = 0; s < length; s += dash + gap) {
            const l = Math.min(dash, length - s), mid = s + l / 2;
            const piece = new THREE.Mesh(new THREE.BoxGeometry(l, 0.01, 0.1), e.enabled ? pathMat : pathOff);
            piece.position.set(a.x + dx * mid / length, 0.006, a.z + dz * mid / length);
            piece.rotation.y = yaw;
            this.layout.add(piece);
          }
        }
      }
    }

    for (const n of map.nodes) {
      const t = layerOfNode(map, n.id);
      if (!visible(t)) continue;
      const p = this.nodePos.get(n.id)!;
      const color = n.enabled ? '#ffffff' : '#C4C4C4';
      const mark = t === 'OHT'
        ? new THREE.Mesh(new THREE.SphereGeometry(0.11, 16, 12), new THREE.MeshStandardMaterial({ color, emissive: OHT, emissiveIntensity: 0.25 }))
        : new THREE.Mesh(new THREE.CylinderGeometry(0.16, 0.16, 0.02, 24), new THREE.MeshStandardMaterial({ color, emissive: AGV, emissiveIntensity: 0.25 }));
      mark.position.copy(p).setY(t === 'OHT' ? RAIL + 0.04 : 0.012);
      mark.userData.nodeId = n.id;
      this.layout.add(mark);
      this.pickable.push(mark);
      if (t === 'OHT') {
        // Hanger up to the ceiling, so the rail reads as hung rather than floating.
        const hanger = new THREE.Mesh(new THREE.CylinderGeometry(0.015, 0.015, 0.7, 6),
          new THREE.MeshStandardMaterial({ color: '#8C9AAB' }));
        hanger.position.copy(p).setY(RAIL + 0.43);
        this.layout.add(hanger);
      }
      const label = makeLabel(n.code, { size: 0.2 });
      label.position.copy(p).setY(t === 'OHT' ? RAIL + 0.35 : 0.3);
      this.layout.add(label);
    }

    for (const port of map.ports) {
      const n = map.nodes.find(x => x.id === port.linkedNodeId);
      if (!n || !visible(port.accessVehicleType)) continue;
      const color = port.accessVehicleType === 'OHT' ? OHT : AGV;
      // OHT lowers onto a load port below the rail; AGV docks beside a stand next to its node.
      const base = this.at(n.x, n.y);
      const size = port.accessVehicleType === 'OHT' ? new THREE.Vector3(0.7, 0.9, 0.6) : new THREE.Vector3(0.8, 0.7, 0.6);
      if (port.accessVehicleType === 'AGV') base.z += 0.9;
      const box = new THREE.Mesh(new THREE.BoxGeometry(size.x, size.y, size.z),
        new THREE.MeshStandardMaterial({ color: '#9FB0C3', roughness: 0.7 }));
      box.position.copy(base).setY(size.y / 2);
      box.castShadow = true; box.receiveShadow = true;
      const edges = new THREE.LineSegments(new THREE.EdgesGeometry(box.geometry), new THREE.LineBasicMaterial({ color }));
      edges.position.copy(box.position);
      this.layout.add(box, edges);
      const title = portTitle(port);
      const label = makeLabel(title.main, { size: 0.26 });
      label.position.copy(base).setY(size.y + (title.sub ? 0.5 : 0.25));
      this.layout.add(label);
      if (title.sub) {
        const sub = makeLabel(title.sub, { size: 0.18 });
        sub.position.copy(base).setY(size.y + 0.2);
        this.layout.add(sub);
      }
    }

    this.home();
  }

  /** Seen from the front and above, the whole layout in view. */
  home() {
    this.controls.target.set(0, 1, 0);
    this.camera.position.set(0, this.span * 0.75, this.span * 0.85);
    this.controls.maxDistance = this.span * 3;
    this.controls.update();
  }
  /** Straight down, like the 2D plan. */
  top() {
    this.controls.target.set(0, 0, 0);
    this.camera.position.set(0, this.span * 1.3, 0.01);
    this.controls.update();
  }
  /**
   * How far the camera may sit from what it is looking at: enough to hold the whole site, and
   * close enough to read one vehicle's label. Both ends follow the site's own size, because a
   * limit in metres means something different on a bay than on a plant.
   */
  private clampDistance(distance: number) {
    return Math.max(4, Math.min(this.span * 2.2, distance));
  }
  /**
   * A zoom step, as a distance to reach rather than a jump to it: the view slides there over a few
   * frames, so it stays obvious which way the site moved. Holding the button keeps it going.
   */
  zoom(factor: number) {
    if (this.following != null) {
      this.followDistance = this.clampDistance(this.followDistance * factor);
      return;
    }
    const now = this.zoomGoal ?? this.camera.position.distanceTo(this.controls.target);
    this.zoomGoal = this.clampDistance(now * factor);
  }

  private build(type: 'OHT' | 'AGV'): VehicleObject {
    const group = new THREE.Group();
    // Both kinds are white; the band, lamp and floor patch carry the state colour, the body's
    // outline whether it may be given work.
    const shell = new THREE.MeshStandardMaterial({ color: '#F6F8FA', roughness: 0.5, metalness: 0.05 });
    const outline = new THREE.LineBasicMaterial({ color: CALL_OFF });
    const band = new THREE.MeshStandardMaterial({ color: '#5B6B7C', emissive: '#5B6B7C', emissiveIntensity: 0.6 });
    const lamp = new THREE.MeshStandardMaterial({ color: '#5B6B7C', emissive: '#5B6B7C', emissiveIntensity: 1.4 });
    const dark = new THREE.MeshStandardMaterial({ color: '#2A3542', roughness: 0.6 });
    const body = new THREE.Group();
    const model = this.models[type];
    let carrierY: number, labelY: number, top: number;
    if (type === 'OHT') {
      const { l, h, w } = OHT_BODY;
      const trolley = new THREE.Mesh(new THREE.BoxGeometry(0.5, 0.12, 0.3), dark);
      trolley.position.y = RAIL + 0.14;
      const hanger = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.2, 0.06), dark);
      hanger.position.y = RAIL - 0.02;
      body.add(trolley, hanger);
      const bottom = RAIL - 0.08 - h;
      if (model) {
        const fit = fitted(model, l);
        fit.object.position.y = RAIL - 0.08 - fit.height;
        body.add(fit.object);
      } else {
        const box = new THREE.Mesh(new THREE.BoxGeometry(l, h, w), shell);
        box.position.y = bottom + h / 2;
        box.castShadow = true;
        const edges = new THREE.LineSegments(new THREE.EdgesGeometry(box.geometry), outline);
        edges.position.copy(box.position);
        body.add(box, edges);
      }
      const ring = new THREE.Mesh(new THREE.BoxGeometry(l + 0.03, 0.07, w + 0.03), band);
      ring.position.y = bottom + 0.1;
      body.add(ring);
      top = RAIL + 0.2;
      carrierY = bottom - 0.22;
      labelY = RAIL + 0.75;
    } else {
      const { l, h, w } = AGV_BODY;
      const lift = 0.08;
      if (model) {
        const fit = fitted(model, l);
        body.add(fit.object);
        top = fit.height;
      } else {
        const box = new THREE.Mesh(new THREE.BoxGeometry(l, h, w), shell);
        box.position.y = lift + h / 2;
        box.castShadow = true;
        const edges = new THREE.LineSegments(new THREE.EdgesGeometry(box.geometry), outline);
        edges.position.copy(box.position);
        body.add(box, edges);
        for (const [x, z] of [[0.32, 0.36], [-0.32, 0.36], [0.32, -0.36], [-0.32, -0.36]]) {
          const wheel = new THREE.Mesh(new THREE.CylinderGeometry(0.08, 0.08, 0.06, 16), dark);
          wheel.rotation.x = Math.PI / 2;
          wheel.position.set(x, 0.08, z);
          body.add(wheel);
        }
        top = lift + h;
      }
      const ring = new THREE.Mesh(new THREE.BoxGeometry(l + 0.03, 0.06, w + 0.03), band);
      ring.position.y = model ? 0.06 : lift + 0.06;
      body.add(ring);
      carrierY = top + 0.17;
      labelY = top + 0.85;
    }
    // Status lamp at the front, on top.
    const bulb = new THREE.Mesh(new THREE.SphereGeometry(0.06, 12, 10), lamp);
    bulb.position.set(0.3, top + 0.05, 0);
    body.add(bulb);
    // The carrier: under the hoist for OHT, on the deck for AGV.
    const carrier = new THREE.Mesh(new THREE.BoxGeometry(0.34, 0.32, 0.34),
      new THREE.MeshStandardMaterial({ color: CARRIER_ON, roughness: 0.6 }));
    carrier.position.y = carrierY;
    carrier.castShadow = true;
    body.add(carrier);
    // Round mark on the roof, always shown: green with a carrier, the inactive colour without.
    // On an OHT it sits behind the hanger, beside the rail, so the rail does not hide it.
    const carrierMark = new THREE.MeshStandardMaterial({ color: CARRIER_OFF, emissive: CARRIER_OFF, emissiveIntensity: 0.25 });
    const disc = new THREE.Mesh(new THREE.CylinderGeometry(0.17, 0.17, 0.03, 32), carrierMark);
    if (type === 'OHT') disc.position.set(-0.24, RAIL - 0.08 + 0.016, 0);
    else disc.position.set(-0.05, top + 0.016, 0);
    body.add(disc);
    group.add(body);

    // A status-coloured patch on the floor below, so an OHT is easy to place from above.
    const marker = new THREE.MeshBasicMaterial({ color: '#5B6B7C', transparent: true, opacity: 0.28, depthWrite: false });
    const patch = new THREE.Mesh(new THREE.CircleGeometry(0.62, 32), marker);
    patch.rotation.x = -Math.PI / 2;
    patch.position.y = 0.01;
    group.add(patch);
    const ring = new THREE.Mesh(new THREE.TorusGeometry(0.7, 0.035, 8, 48), new THREE.MeshBasicMaterial({ color: SELECT }));
    ring.rotation.x = -Math.PI / 2;
    ring.position.y = 0.02;
    ring.visible = false;
    group.add(ring);

    return { group, type, shell, outline, band, lamp, marker, ring, carrier, carrierMark, label: null, labelKey: '', labelY,
      target: new THREE.Vector3(), yaw: 0, targetYaw: 0, placed: false };
  }

  /** Tags on the nodes vehicles wait at (대기) and charge at (충전). Rebuilt only when they change. */
  private setPlaces(vehicles: VehicleSnapshot[], layer: Layer) {
    const places = vehiclePlaces(vehicles, this.map ?? undefined);
    const key = layer + JSON.stringify([...places.home]) + JSON.stringify([...places.charge]);
    if (key === this.placesKey || !this.map) return;
    this.placesKey = key;
    this.places.parent?.remove(this.places);
    disposeTree(this.places);
    this.places = new THREE.Group();
    this.scene.add(this.places);
    const put = (nodeId: number, text: string, color: string, shift: number) => {
      const p = this.nodePos.get(nodeId);
      if (!p || !this.map || !(layer === 'all' || layer === layerOfNode(this.map, nodeId))) return;
      const pad = new THREE.Mesh(new THREE.CylinderGeometry(0.45, 0.45, 0.02, 32),
        new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.35, depthWrite: false }));
      pad.position.set(p.x, 0.012, p.z);
      const label = makeLabel(text, { dot: color, size: 0.22 });
      label.position.set(p.x, (p.y > 0 ? p.y + 0.6 : 0.55) + shift, p.z);
      this.places.add(pad, label);
    };
    for (const [id, who] of places.home) put(id, `대기 · ${who.join(',')}`, '#5B6B7C', 0);
    for (const [id, who] of places.charge) put(id, who.length ? `충전 · ${who.join(',')}` : '충전', '#2E7D32', places.home.has(id) ? 0.3 : 0);
  }

  setVehicles(vehicles: VehicleSnapshot[], selectedId: number | null, layer: Layer) {
    this.setPlaces(vehicles, layer);
    if (this.map) this.motion.push(this.map, vehicles);
    const seen = new Set<number>();
    for (const v of vehicles) {
      const point = this.map ? vehiclePoint(this.map, v) : null;
      const p = point ? this.at(point.x, point.y) : undefined;
      if (!p || !(layer === 'all' || layer === v.vType)) continue;
      seen.add(v.id);
      let obj = this.vehicles.get(v.id);
      if (!obj || obj.type !== v.vType) {
        if (obj) { this.fleet.remove(obj.group); disposeTree(obj.group); }
        obj = this.build(v.vType);
        obj.group.userData.vehicleId = v.id;
        this.vehicles.set(v.id, obj);
        this.fleet.add(obj.group);
      }
      if (!obj.placed) {
        obj.group.position.set(p.x, 0, p.z); obj.placed = true;
        // Until it is seen to move, it faces the way the track leaves the node it stands on.
        const along = this.map ? nodeHeading(this.map, v.nodeId) : null;
        if (along != null) { obj.yaw = obj.targetYaw = -along * Math.PI / 180; }
      }
      const status = statusOf(v);
      const look = vehicleLook(v);
      const color = new THREE.Color(look.fill);
      obj.band.color.copy(color); obj.band.emissive.copy(color);
      obj.lamp.color.copy(color); obj.lamp.emissive.copy(color);
      obj.marker.color.copy(color);
      obj.outline.color.set(callable(v) ? CALL_ON : CALL_OFF);
      obj.carrier.visible = v.carrierPresent;
      const mark = v.carrierPresent ? CARRIER_ON : CARRIER_OFF;
      obj.carrierMark.color.set(mark); obj.carrierMark.emissive.set(mark);
      obj.ring.visible = selectedId === v.id;
      const extra = status !== 'IDLE' && status !== 'MOVING' || !v.online ? ` · ${look.label}` : '';
      const battery = v.batteryPct == null ? '' : ` ${Math.round(v.batteryPct)}%`;
      const key = `${v.code} ${modeLetter(v)}${battery}${extra}|${look.fill}|${v.carrierPresent ? 1 : 0}`;
      if (key !== obj.labelKey) {
        if (obj.label) { obj.group.remove(obj.label); disposeTree(obj.label); }
        obj.label = makeLabel(key.split('|')[0], { dot: look.fill, dark: true, carrier: v.carrierPresent });
        obj.label.position.y = obj.labelY;
        obj.group.add(obj.label);
        obj.labelKey = key;
      }
    }
    for (const [id, obj] of this.vehicles) {
      if (seen.has(id)) continue;
      this.fleet.remove(obj.group);
      disposeTree(obj.group);
      this.vehicles.delete(id);
    }
  }

  /** Held nodes as a fluorescent band along the track; each way to a destination as a thin red line. */
  setOverlays(holds: NodeHold[], headings: VehicleHeading[], layer: Layer) {
    const key = layer + JSON.stringify(holds.map(h => [h.vehicleId, h.nodeId, h.sequence]))
      + JSON.stringify(headings.map(h => [h.vehicleId, h.targetNodeId, h.remainingNodeIds]));
    if (key === this.overlaysKey || !this.map) return;
    this.overlaysKey = key;
    this.overlays.parent?.remove(this.overlays);
    disposeTree(this.overlays);
    this.overlays = new THREE.Group();
    this.scene.add(this.overlays);
    const map = this.map;
    const visible = (id: number) => layer === 'all' || layer === layerOfNode(map, id);
    const lift = (id: number) => (layerOfNode(map, id) === 'OHT' ? RAIL + 0.1 : 0.03);
    // Points along the track from one node to the next, at the height of that track.
    const along = (ids: number[]) => {
      const out: THREE.Vector3[] = [];
      for (let i = 1; i < ids.length; i++) {
        const a = map.nodes.find(n => n.id === ids[i - 1]), b = map.nodes.find(n => n.id === ids[i]);
        const seg = segmentBetween(map, ids[i - 1], ids[i]);
        if (!a || !b || !seg) continue;
        for (const p of segmentPoints(a.x, a.y, b.x, b.y, seg.curveMm)) out.push(this.at(p.x, p.y, lift(ids[i])));
      }
      return out;
    };
    const glow = new THREE.MeshBasicMaterial({ color: HELD, transparent: true, opacity: 0.55, depthWrite: false });
    const runs = new Map<number, NodeHold[]>();
    for (const h of holds) runs.set(h.vehicleId, [...(runs.get(h.vehicleId) ?? []), h]);
    for (const run of runs.values()) {
      const ids = run.sort((a, b) => a.sequence - b.sequence).map(h => h.nodeId);
      if (!ids.every(visible)) continue;
      const pts = along(ids);
      for (let i = 1; i < pts.length; i++) {
        const a = pts[i - 1], b = pts[i];
        const len = a.distanceTo(b);
        if (len < 1e-3) continue;
        const band = new THREE.Mesh(new THREE.BoxGeometry(len + 0.02, 0.05, 0.32), glow);
        band.position.set((a.x + b.x) / 2, (a.y + b.y) / 2 + 0.01, (a.z + b.z) / 2);
        band.rotation.y = Math.atan2(-(b.z - a.z), b.x - a.x);
        this.overlays.add(band);
      }
      for (const id of ids) {
        const n = map.nodes.find(x => x.id === id);
        if (!n) continue;
        const dot = new THREE.Mesh(new THREE.SphereGeometry(0.16, 12, 10), glow);
        dot.position.copy(this.at(n.x, n.y, lift(id)));
        this.overlays.add(dot);
      }
    }
    const red = new THREE.LineBasicMaterial({ color: DESTINATION });
    for (const h of headings) {
      if (!visible(h.targetNodeId)) continue;
      const pts = along(h.remainingNodeIds).map(p => p.clone().setY(p.y + 0.04));
      if (pts.length > 1) this.overlays.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(pts), red));
      const goal = map.nodes.find(n => n.id === h.targetNodeId);
      if (!goal) continue;
      const ring = new THREE.Mesh(new THREE.TorusGeometry(0.28, 0.03, 8, 40), new THREE.MeshBasicMaterial({ color: DESTINATION }));
      ring.rotation.x = -Math.PI / 2;
      ring.position.copy(this.at(goal.x, goal.y, lift(goal.id) + 0.05));
      this.overlays.add(ring);
    }
  }

  setTarget(nodeId: number | null) {
    const p = nodeId === null ? undefined : this.nodePos.get(nodeId);
    this.targetMark.visible = !!p;
    if (p) this.targetMark.position.copy(p).setY(p.y > 0 ? p.y + 0.04 : 0.03);
  }

  /** Vehicle or node under a pointer position, vehicles first. */
  pick(clientX: number, clientY: number): { vehicleId?: number; nodeId?: number } {
    const r = this.renderer.domElement.getBoundingClientRect();
    const ndc = new THREE.Vector2(((clientX - r.left) / r.width) * 2 - 1, -((clientY - r.top) / r.height) * 2 + 1);
    const ray = new THREE.Raycaster();
    ray.setFromCamera(ndc, this.camera);
    for (const hit of ray.intersectObjects(this.fleet.children, true)) {
      for (let o: THREE.Object3D | null = hit.object; o; o = o.parent)
        if (o.userData.vehicleId !== undefined) return { vehicleId: o.userData.vehicleId as number };
    }
    const node = ray.intersectObjects(this.pickable, false)[0];
    return node ? { nodeId: node.object.userData.nodeId as number } : {};
  }

  private animate() {
    const now = performance.now(), dt = Math.min(0.1, (now - this.last) / 1000);
    this.last = now;
    // Each frame is drawn from the reported track, replayed just behind real time, so a vehicle
    // moves at its own pace between reports; it faces the way it is going.
    const k = 1 - Math.exp(-dt * 8);
    const wall = Date.now();
    for (const [id, obj] of this.vehicles) {
      const p = this.motion.at(id, wall);
      if (p) {
        const next = this.at(p.x, p.y);
        const dx = next.x - obj.group.position.x, dz = next.z - obj.group.position.z;
        if (dx * dx + dz * dz > 1e-6) obj.targetYaw = Math.atan2(-dz, dx);
        obj.group.position.set(next.x, 0, next.z);
      }
      let d = obj.targetYaw - obj.yaw;
      d = Math.atan2(Math.sin(d), Math.cos(d));
      obj.yaw += d * Math.min(1, k * 2);
      obj.group.children[0].rotation.y = obj.yaw;
    }

    if (this.following !== null) {
      const obj = this.vehicles.get(this.following);
      if (!obj) this.follow(null);          // it left the map; give the view back rather than hang
      else {
        // Behind and a little above it, looking the way it is going. The camera is led to that
        // place rather than snapped to it, so a vehicle turning does not whip the whole site round.
        // An OHT rides on the rail and an AGV on the floor, so the camera rides at its height --
        // aiming at the floor beneath a rail vehicle looks past the thing being followed.
        const high = obj.type === 'OHT' ? RAIL - 0.5 : 0.6;
        const ride = obj.group.position.clone().setY(high);
        const back = new THREE.Vector3(-Math.cos(obj.yaw), 0, Math.sin(obj.yaw))
          .multiplyScalar(this.followDistance);
        const wanted = ride.clone().add(back);
        wanted.y = high + Math.max(1.2, this.followDistance * 0.3);
        if (this.followSnap) {
          // The ride starts at the vehicle. Flying there from wherever the view happened to be is
          // a long swoop over everything in between, which shows nobody anything and costs the
          // seconds in which the vehicle was worth watching.
          this.camera.position.copy(wanted);
          this.controls.target.copy(ride);
          this.followSnap = false;
        } else {
          this.camera.position.lerp(wanted, 1 - Math.exp(-dt * 3.2));
          this.controls.target.lerp(ride, 1 - Math.exp(-dt * 5));
        }
        this.camera.lookAt(this.controls.target);
        // The orbit is not updated while riding: it would recompute the camera from its own idea
        // of where it should be and undo everything above.
        this.renderer.render(this.scene, this.camera);
        return;
      }
    } else if (this.zoomGoal !== null) {
      const dir = this.camera.position.clone().sub(this.controls.target);
      const at = dir.length() || 1;
      const next = at + (this.zoomGoal - at) * (1 - Math.exp(-dt * 9));
      this.camera.position.copy(this.controls.target).add(dir.multiplyScalar(next / at));
      if (Math.abs(next - this.zoomGoal) < this.zoomGoal * 0.004) this.zoomGoal = null;
    }

    this.controls.update();
    this.renderer.render(this.scene, this.camera);
  }

  /**
   * Rides with one vehicle, or stops riding.
   *
   * <p>While riding, the view is the vehicle's: the site turns as it turns, which is the only way
   * to see what a vehicle meets -- what it is about to reach, and what is behind it. The orbit is
   * handed back the moment it stops, and the wheel becomes how far behind the camera sits.
   */
  follow(vehicleId: number | null) {
    this.following = vehicleId;
    this.zoomGoal = null;
    if (vehicleId === null) {
      // Hand the orbit back where the ride left off: it takes its bearings from where the camera
      // and the target actually are, so the view does not jump when the ride ends.
      this.controls.enabled = true;
      this.controls.update();
      return;
    }
    // A few vehicle lengths behind, whatever the camera was doing before. One world unit is a
    // metre here, so this is a camera walking behind the vehicle rather than watching the site.
    this.followDistance = this.clampDistance(7);
    this.followSnap = true;
    this.controls.enabled = false;
  }
  /** Which vehicle the camera is riding with, so a screen can say so. */
  riding() { return this.following; }

  dispose() {
    cancelAnimationFrame(this.frame);
    this.resize.disconnect();
    this.controls.dispose();
    disposeTree(this.scene);
    this.renderer.dispose();
    this.renderer.domElement.remove();
  }
}

/**
 * The same layout and vehicles as the 2D map, in 3D: the OHT rail hangs at ceiling height with its
 * vehicles below it, the AGV route is painted on the floor. Status shows as the colour of each
 * vehicle's band, lamp and floor patch; whether it carries as a round mark on the roof and in the
 * label (green with a carrier, the inactive colour without) and a green box when it does; mode and
 * code in the label.
 * Clicking a vehicle selects it, clicking a node picks it as the move target, as in 2D.
 */
export default function MapView3D(props: Props) {
  const host = React.useRef<HTMLDivElement | null>(null);
  const world = React.useRef<World | null>(null);
  const latest = React.useRef(props);
  latest.current = props;
  const [models, setModels] = React.useState<Models | null>(null);

  // The layout is rebuilt only when it changes, not on every refresh of the same map.
  const { map } = props;
  const layoutKey = React.useMemo(() => [
    map.revisionId, props.layer,
    map.nodes.map(n => `${n.id}:${n.x}:${n.y}:${n.enabled ? 1 : 0}`).join(','),
    map.edges.map(e => `${e.id}:${e.enabled ? 1 : 0}:${e.curveMm ?? 0}`).join(','),
    map.ports.map(p => `${p.id}:${p.linkedNodeId}:${p.equipmentName ?? ''}`).join(','),
  ].join('|'), [map, props.layer]);

  React.useEffect(() => {
    const w = new World(host.current!);
    world.current = w;
    let alive = true;
    loadModels().then(m => { if (alive) setModels(m); });
    const el = w.renderer.domElement;
    let down: { x: number; y: number } | null = null;
    const onDown = (e: PointerEvent) => { down = { x: e.clientX, y: e.clientY }; };
    const onUp = (e: PointerEvent) => {
      if (!down || e.button !== 0 || Math.abs(e.clientX - down.x) + Math.abs(e.clientY - down.y) > 5) { down = null; return; }
      down = null;
      const hit = w.pick(e.clientX, e.clientY), p = latest.current;
      if (hit.vehicleId !== undefined) {
        const v = p.vehicles.find(x => x.id === hit.vehicleId);
        if (v) p.onSelect(v);
      } else if (hit.nodeId !== undefined) p.onNodeSelect?.(hit.nodeId);
    };
    const onMove = (e: PointerEvent) => {
      if (e.buttons) return;
      const hit = w.pick(e.clientX, e.clientY);
      el.style.cursor = hit.vehicleId !== undefined || (hit.nodeId !== undefined && latest.current.onNodeSelect) ? 'pointer' : 'grab';
    };
    el.addEventListener('pointerdown', onDown);
    el.addEventListener('pointerup', onUp);
    el.addEventListener('pointermove', onMove);
    return () => {
      alive = false;
      el.removeEventListener('pointerdown', onDown);
      el.removeEventListener('pointerup', onUp);
      el.removeEventListener('pointermove', onMove);
      w.dispose();
      world.current = null;
    };
  }, []);

  React.useEffect(() => {
    const w = world.current, p = latest.current;
    if (!w) return;
    if (models) w.setModels(models);
    w.setLayout(p.map, p.layer);
    w.setVehicles(p.vehicles, p.selectedId, p.layer);
    w.setTarget(p.targetNodeId ?? null);
    w.setOverlays(p.holds ?? [], p.headings ?? [], p.layer);
  }, [layoutKey, models]);
  React.useEffect(() => {
    world.current?.setOverlays(props.holds ?? [], props.headings ?? [], props.layer);
  }, [props.holds, props.headings, props.layer]);
  React.useEffect(() => {
    world.current?.setVehicles(props.vehicles, props.selectedId, props.layer);
  }, [props.vehicles, props.selectedId, props.layer]);
  React.useEffect(() => {
    world.current?.setTarget(props.targetNodeId ?? null);
  }, [props.targetNodeId]);

  // Riding with a vehicle. Choosing a different one moves the ride to it, which is what somebody
  // who clicks another vehicle while following plainly means; choosing none ends the ride.
  const [riding, setRiding] = React.useState(false);
  React.useEffect(() => {
    const w = world.current;
    if (!w) return;
    if (props.selectedId === null) { if (w.riding() !== null) w.follow(null); setRiding(false); return; }
    if (w.riding() !== null && w.riding() !== props.selectedId) w.follow(props.selectedId);
  }, [props.selectedId]);

  const button = (label: string, title: string, run: () => void) => (
    <button type="button" title={title} aria-label={title} onClick={run}
      style={{ minWidth: 28, height: 26, padding: '0 6px', border: '1px solid var(--ln2)', background: 'var(--srf)',
        color: 'var(--ink2)', borderRadius: 5, cursor: 'pointer', fontSize: 12, lineHeight: 1 }}>{label}</button>
  );
  return (
    <div className="mapwrap map3d" style={{ position: 'relative' }}>
      <div style={{ position: 'absolute', right: 10, top: 10, display: 'flex', gap: 4, zIndex: 2 }}>
        {/* Riding with a vehicle: only offered once one is chosen, because there is nothing to
            ride with until then. */}
        <button type="button"
          title={props.selectedId === null ? '차량을 먼저 고르면 그 차량 시점으로 따라갑니다.'
            : riding ? '따라가기를 멈추고 시점을 되돌려 줍니다.' : '고른 차량 뒤에서 그 차량이 보는 쪽으로 따라갑니다.'}
          disabled={props.selectedId === null}
          onClick={() => {
            const w = world.current;
            if (!w || props.selectedId === null) return;
            const next = riding ? null : props.selectedId;
            w.follow(next);
            setRiding(next !== null);
          }}
          style={{
            height: 26, padding: '0 9px', borderRadius: 5, fontSize: 12, lineHeight: 1,
            border: `1px solid ${riding ? 'var(--acc)' : 'var(--ln2)'}`,
            background: riding ? 'var(--acc)' : 'var(--srf)',
            color: riding ? '#fff' : props.selectedId === null ? 'var(--mut)' : 'var(--ink2)',
            cursor: props.selectedId === null ? 'default' : 'pointer',
          }}>{riding ? '따라가는 중' : '따라가기'}</button>
        {button('+', '확대', () => world.current?.zoom(0.75))}
        {button('−', '축소', () => world.current?.zoom(1 / 0.75))}
        {button('위', '위에서 보기', () => { world.current?.follow(null); setRiding(false); world.current?.top(); })}
        {button('⤢', '처음 시점', () => { world.current?.follow(null); setRiding(false); world.current?.home(); })}
      </div>
      <div ref={host} role="img" aria-label="OHT 천장 레일과 AGV 바닥 주행로의 3D 설비 지도. 끌어서 회전, 휠로 확대·축소"
        style={{ width: '100%', height: 'min(62vh, 640px)', minHeight: 380, touchAction: 'none' }} />
    </div>
  );
}
