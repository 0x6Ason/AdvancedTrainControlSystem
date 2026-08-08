"use strict";

window.AtcsNetworkMap = class AtcsNetworkMap {
  constructor(canvas, onSymbolSelected) {
    this.canvas = canvas;
    this.onSymbolSelected = onSymbolSelected;
    this.gl = canvas.getContext("webgl2", {
      antialias: true,
      alpha: false,
      depth: true,
      powerPreference: "high-performance"
    });
    if (!this.gl) throw new Error("This administration map requires WebGL2.");

    this.camera = { yaw: 0, pitch: degrees(55), distance: 1000, target: [0, 0, 0] };
    this.origin = [0, 0, 0];
    this.bounds = null;
    this.symbols = [];
    this.selectedSymbolId = null;
    this.drag = null;
    this.viewProjection = identityMatrix();
    this.renderPending = false;
    this.lightTheme = document.documentElement.dataset.theme !== "dark";
    this.railVertexCount = 0;
    this.gridVertexCount = 0;
    this.railPointCount = 0;
    this.symbolPointCount = 0;

    this.segmentProgram = createProgram(this.gl, SEGMENT_VERTEX_SHADER, SEGMENT_FRAGMENT_SHADER);
    this.pointProgram = createProgram(this.gl, POINT_VERTEX_SHADER, POINT_FRAGMENT_SHADER);
    this.railBuffer = this.gl.createBuffer();
    this.gridBuffer = this.gl.createBuffer();
    this.railPointBuffer = this.gl.createBuffer();
    this.symbolPointBuffer = this.gl.createBuffer();
    this.configureEvents();
    this.requestRender();
  }

  setScene(graph, symbols) {
    const nodes = graph.nodes || [];
    const points = nodes.map(node => ({ x: node[0], y: node[1], z: node[2] }));
    points.push(...symbols.map(symbol => ({ x: symbol.x, y: symbol.y, z: symbol.z })));
    this.bounds = calculateBounds(points);
    this.symbols = symbols;
    if (!this.bounds) {
      this.origin = [0, 0, 0];
      this.uploadBuffer(this.railBuffer, new Float32Array());
      this.uploadBuffer(this.gridBuffer, new Float32Array());
      this.uploadBuffer(this.railPointBuffer, new Float32Array());
      this.uploadBuffer(this.symbolPointBuffer, new Float32Array());
      this.railVertexCount = this.gridVertexCount = this.railPointCount = this.symbolPointCount = 0;
      this.requestRender();
      return;
    }

    this.origin = [
      (this.bounds.minX + this.bounds.maxX) / 2,
      (this.bounds.minY + this.bounds.maxY) / 2,
      (this.bounds.minZ + this.bounds.maxZ) / 2
    ];
    const relativeNodes = nodes.map(node => [
      node[0] - this.origin[0],
      (node[1] - this.origin[1]) * 3,
      node[2] - this.origin[2],
      materialType(graph.materials?.[node[3]])
    ]);

    const railVertices = [];
    for (const edge of graph.edges || []) {
      const first = relativeNodes[edge[0]], second = relativeNodes[edge[1]];
      if (!first || !second) continue;
      appendSegmentQuad(railVertices, first, second, Math.max(first[3], second[3]));
    }
    this.railVertexCount = railVertices.length / 9;
    this.uploadBuffer(this.railBuffer, new Float32Array(railVertices));

    const railPoints = [];
    for (const node of relativeNodes) railPoints.push(node[0], node[1], node[2], node[3], 2.4);
    this.railPointCount = relativeNodes.length;
    this.uploadBuffer(this.railPointBuffer, new Float32Array(railPoints));

    this.rebuildSymbolBuffer();
    this.rebuildGrid();
    this.fit();
  }

  setSelectedSymbol(symbolId) {
    this.selectedSymbolId = symbolId || null;
    this.rebuildSymbolBuffer();
    this.requestRender();
  }

  setTheme(theme) {
    this.lightTheme = theme !== "dark";
    this.requestRender();
  }

  fit() {
    if (!this.bounds) return;
    const spanX = Math.max(1, this.bounds.maxX - this.bounds.minX);
    const spanZ = Math.max(1, this.bounds.maxZ - this.bounds.minZ);
    const spanY = Math.max(1, (this.bounds.maxY - this.bounds.minY) * 3);
    const radius = Math.max(spanX, spanZ, spanY * 2) * 0.58;
    this.camera.target = [0, 0, 0];
    this.camera.distance = Math.max(40, radius / Math.tan(degrees(22.5)));
    this.requestRender();
  }

  north() {
    this.camera.yaw = 0;
    this.requestRender();
  }

  setTopView() {
    this.camera.pitch = degrees(89.5);
    this.camera.yaw = 0;
    this.requestRender();
  }

  setThreeDimensionalView() {
    this.camera.pitch = degrees(55);
    this.requestRender();
  }

  resize() {
    const box = this.canvas.getBoundingClientRect();
    const ratio = window.devicePixelRatio || 1;
    const width = Math.max(1, Math.round(box.width * ratio));
    const height = Math.max(1, Math.round(box.height * ratio));
    if (this.canvas.width !== width || this.canvas.height !== height) {
      this.canvas.width = width;
      this.canvas.height = height;
      this.requestRender();
    }
  }

  configureEvents() {
    this.canvas.addEventListener("contextmenu", event => event.preventDefault());
    this.canvas.addEventListener("wheel", event => {
      event.preventDefault();
      this.camera.distance *= event.deltaY < 0 ? 0.84 : 1.18;
      this.camera.distance = clamp(this.camera.distance, 8, 1000000);
      this.requestRender();
    }, { passive: false });
    this.canvas.addEventListener("pointerdown", event => {
      this.canvas.setPointerCapture(event.pointerId);
      this.drag = {
        button: event.button,
        startX: event.clientX,
        startY: event.clientY,
        lastX: event.clientX,
        lastY: event.clientY,
        rotate: event.button === 2 || event.shiftKey
      };
      this.canvas.classList.add("dragging");
    });
    this.canvas.addEventListener("pointermove", event => {
      if (!this.drag) return;
      const deltaX = event.clientX - this.drag.lastX;
      const deltaY = event.clientY - this.drag.lastY;
      this.drag.lastX = event.clientX;
      this.drag.lastY = event.clientY;
      if (this.drag.rotate) {
        this.camera.yaw -= deltaX * 0.006;
        this.camera.pitch = clamp(this.camera.pitch + deltaY * 0.006, degrees(22), degrees(89.5));
      } else {
        const scale = 2 * this.camera.distance * Math.tan(degrees(22.5)) / Math.max(1, this.canvas.clientHeight);
        const right = [Math.cos(this.camera.yaw), 0, -Math.sin(this.camera.yaw)];
        const forward = [-Math.sin(this.camera.yaw), 0, -Math.cos(this.camera.yaw)];
        this.camera.target[0] -= right[0] * deltaX * scale - forward[0] * deltaY * scale;
        this.camera.target[2] -= right[2] * deltaX * scale - forward[2] * deltaY * scale;
      }
      this.requestRender();
    });
    this.canvas.addEventListener("pointerup", event => {
      if (!this.drag) return;
      const moved = Math.hypot(event.clientX - this.drag.startX, event.clientY - this.drag.startY);
      const wasLeftClick = this.drag.button === 0 && !this.drag.rotate;
      this.drag = null;
      this.canvas.classList.remove("dragging");
      if (wasLeftClick && moved < 4) this.pickSymbol(event.clientX, event.clientY);
    });
    this.canvas.addEventListener("pointercancel", () => {
      this.drag = null;
      this.canvas.classList.remove("dragging");
    });
  }

  pickSymbol(clientX, clientY) {
    const rect = this.canvas.getBoundingClientRect();
    const x = (clientX - rect.left) * this.canvas.width / rect.width;
    const y = (clientY - rect.top) * this.canvas.height / rect.height;
    let nearest = null, nearestDistance = 15 * (window.devicePixelRatio || 1);
    for (const symbol of this.symbols) {
      const projected = projectPoint(this.viewProjection, [
        symbol.x - this.origin[0],
        (symbol.y - this.origin[1]) * 3 + 1.5,
        symbol.z - this.origin[2]
      ], this.canvas.width, this.canvas.height);
      if (!projected) continue;
      const distance = Math.hypot(projected[0] - x, projected[1] - y);
      if (distance < nearestDistance) {
        nearest = symbol;
        nearestDistance = distance;
      }
    }
    if (nearest) this.onSymbolSelected(nearest);
  }

  rebuildGrid() {
    if (!this.bounds) return;
    const spanX = Math.max(1, this.bounds.maxX - this.bounds.minX);
    const spanZ = Math.max(1, this.bounds.maxZ - this.bounds.minZ);
    const step = niceStep(Math.max(spanX, spanZ) / 18);
    const padding = step * 2;
    const minX = this.bounds.minX - this.origin[0] - padding;
    const maxX = this.bounds.maxX - this.origin[0] + padding;
    const minZ = this.bounds.minZ - this.origin[2] - padding;
    const maxZ = this.bounds.maxZ - this.origin[2] + padding;
    const groundY = (this.bounds.minY - this.origin[1]) * 3 - 8;
    const vertices = [];
    for (let x = Math.floor(minX / step) * step; x <= maxX; x += step) {
      appendSegmentQuad(vertices, [x, groundY, minZ], [x, groundY, maxZ], 9);
    }
    for (let z = Math.floor(minZ / step) * step; z <= maxZ; z += step) {
      appendSegmentQuad(vertices, [minX, groundY, z], [maxX, groundY, z], 9);
    }
    this.gridVertexCount = vertices.length / 9;
    this.uploadBuffer(this.gridBuffer, new Float32Array(vertices));
  }

  rebuildSymbolBuffer() {
    const vertices = [];
    for (const symbol of this.symbols) {
      const selected = symbol.id === this.selectedSymbolId;
      vertices.push(
        symbol.x - this.origin[0],
        (symbol.y - this.origin[1]) * 3 + 2,
        symbol.z - this.origin[2],
        symbolType(symbol),
        selected ? 14 : 8
      );
    }
    this.symbolPointCount = this.symbols.length;
    this.uploadBuffer(this.symbolPointBuffer, new Float32Array(vertices));
  }

  uploadBuffer(buffer, data) {
    const gl = this.gl;
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
  }

  requestRender() {
    if (this.renderPending) return;
    this.renderPending = true;
    requestAnimationFrame(() => {
      this.renderPending = false;
      this.render();
    });
  }

  render() {
    this.resize();
    const gl = this.gl;
    gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    if (this.lightTheme) gl.clearColor(0.972, 0.980, 0.992, 1);
    else gl.clearColor(0.018, 0.025, 0.043, 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    gl.enable(gl.DEPTH_TEST);
    gl.depthFunc(gl.LEQUAL);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

    const aspect = this.canvas.width / Math.max(1, this.canvas.height);
    const projection = perspectiveMatrix(
      degrees(45), aspect, Math.max(0.5, this.camera.distance / 10000), this.camera.distance * 20 + 10000);
    const horizontal = Math.cos(this.camera.pitch) * this.camera.distance;
    const eye = [
      this.camera.target[0] + Math.sin(this.camera.yaw) * horizontal,
      this.camera.target[1] + Math.sin(this.camera.pitch) * this.camera.distance,
      this.camera.target[2] + Math.cos(this.camera.yaw) * horizontal
    ];
    const view = lookAtMatrix(eye, this.camera.target, [0, 1, 0]);
    this.viewProjection = multiplyMatrices(projection, view);

    this.drawSegments(this.gridBuffer, this.gridVertexCount, 1, 2);
    this.drawPoints(this.railPointBuffer, this.railPointCount, 0);
    this.drawPoints(this.railPointBuffer, this.railPointCount, 1);
    this.drawSegments(this.railBuffer, this.railVertexCount, 6, 0);
    this.drawSegments(this.railBuffer, this.railVertexCount, 2.6, 1);
    this.drawPoints(this.symbolPointBuffer, this.symbolPointCount, 0);
    this.drawPoints(this.symbolPointBuffer, this.symbolPointCount, 1);
  }

  drawSegments(buffer, count, width, layer) {
    if (!count) return;
    const gl = this.gl, program = this.segmentProgram;
    gl.useProgram(program);
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    const stride = 9 * 4;
    enableAttribute(gl, program, "aStart", 3, stride, 0);
    enableAttribute(gl, program, "aEnd", 3, stride, 3 * 4);
    enableAttribute(gl, program, "aAlong", 1, stride, 6 * 4);
    enableAttribute(gl, program, "aSide", 1, stride, 7 * 4);
    enableAttribute(gl, program, "aType", 1, stride, 8 * 4);
    gl.uniformMatrix4fv(gl.getUniformLocation(program, "uViewProjection"), false, this.viewProjection);
    gl.uniform2f(gl.getUniformLocation(program, "uViewport"), this.canvas.width, this.canvas.height);
    gl.uniform1f(gl.getUniformLocation(program, "uWidth"), width * (window.devicePixelRatio || 1));
    gl.uniform1i(gl.getUniformLocation(program, "uLayer"), layer);
    gl.uniform1i(gl.getUniformLocation(program, "uLightTheme"), this.lightTheme ? 1 : 0);
    gl.drawArrays(gl.TRIANGLES, 0, count);
  }

  drawPoints(buffer, count, layer) {
    if (!count) return;
    const gl = this.gl, program = this.pointProgram;
    gl.useProgram(program);
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    const stride = 5 * 4;
    enableAttribute(gl, program, "aPosition", 3, stride, 0);
    enableAttribute(gl, program, "aType", 1, stride, 3 * 4);
    enableAttribute(gl, program, "aSize", 1, stride, 4 * 4);
    gl.uniformMatrix4fv(gl.getUniformLocation(program, "uViewProjection"), false, this.viewProjection);
    gl.uniform1f(gl.getUniformLocation(program, "uPixelRatio"), window.devicePixelRatio || 1);
    gl.uniform1i(gl.getUniformLocation(program, "uLayer"), layer);
    gl.uniform1i(gl.getUniformLocation(program, "uLightTheme"), this.lightTheme ? 1 : 0);
    gl.drawArrays(gl.POINTS, 0, count);
  }
};

function appendSegmentQuad(output, start, end, type) {
  for (const [along, side] of [[0, -1], [0, 1], [1, -1], [1, -1], [0, 1], [1, 1]]) {
    output.push(start[0], start[1], start[2], end[0], end[1], end[2], along, side, type);
  }
}

function materialType(material = "") {
  if (material.includes("powered_rail")) return 1;
  if (material.includes("detector_rail")) return 2;
  if (material.includes("activator_rail")) return 3;
  return 0;
}

function symbolType(symbol) {
  if (symbol.decision === "IGNORED") return 14;
  return ({ STATION: 10, SPAWN: 11, ANNOUNCE: 12, PROPERTY: 13 })[symbol.type] || 15;
}

function calculateBounds(points) {
  if (!points.length) return null;
  return points.reduce((bounds, point) => ({
    minX: Math.min(bounds.minX, point.x), maxX: Math.max(bounds.maxX, point.x),
    minY: Math.min(bounds.minY, point.y), maxY: Math.max(bounds.maxY, point.y),
    minZ: Math.min(bounds.minZ, point.z), maxZ: Math.max(bounds.maxZ, point.z)
  }), { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity, minZ: Infinity, maxZ: -Infinity });
}

function niceStep(value) {
  const power = 10 ** Math.floor(Math.log10(Math.max(1, value)));
  const normalized = value / power;
  return (normalized < 2 ? 1 : normalized < 5 ? 2 : 5) * power;
}

function enableAttribute(gl, program, name, size, stride, offset) {
  const location = gl.getAttribLocation(program, name);
  gl.enableVertexAttribArray(location);
  gl.vertexAttribPointer(location, size, gl.FLOAT, false, stride, offset);
}

function createProgram(gl, vertexSource, fragmentSource) {
  const vertex = compileShader(gl, gl.VERTEX_SHADER, vertexSource);
  const fragment = compileShader(gl, gl.FRAGMENT_SHADER, fragmentSource);
  const program = gl.createProgram();
  gl.attachShader(program, vertex);
  gl.attachShader(program, fragment);
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program));
  gl.deleteShader(vertex); gl.deleteShader(fragment);
  return program;
}

function compileShader(gl, type, source) {
  const shader = gl.createShader(type);
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader));
  return shader;
}

function degrees(value) { return value * Math.PI / 180; }
function clamp(value, minimum, maximum) { return Math.max(minimum, Math.min(maximum, value)); }
function identityMatrix() { return new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]); }

function perspectiveMatrix(fov, aspect, near, far) {
  const f = 1 / Math.tan(fov / 2), range = 1 / (near - far);
  return new Float32Array([
    f / aspect, 0, 0, 0,
    0, f, 0, 0,
    0, 0, (far + near) * range, -1,
    0, 0, 2 * far * near * range, 0
  ]);
}

function lookAtMatrix(eye, center, up) {
  const z = normalize([eye[0] - center[0], eye[1] - center[1], eye[2] - center[2]]);
  const x = normalize(cross(up, z));
  const y = cross(z, x);
  return new Float32Array([
    x[0], y[0], z[0], 0,
    x[1], y[1], z[1], 0,
    x[2], y[2], z[2], 0,
    -dot(x, eye), -dot(y, eye), -dot(z, eye), 1
  ]);
}

function multiplyMatrices(a, b) {
  const output = new Float32Array(16);
  for (let column = 0; column < 4; column++) {
    for (let row = 0; row < 4; row++) {
      let sum = 0;
      for (let index = 0; index < 4; index++) sum += a[index * 4 + row] * b[column * 4 + index];
      output[column * 4 + row] = sum;
    }
  }
  return output;
}

function projectPoint(matrix, point, width, height) {
  const vector = [point[0], point[1], point[2], 1], clip = [0, 0, 0, 0];
  for (let row = 0; row < 4; row++) {
    clip[row] = matrix[row] * vector[0] + matrix[4 + row] * vector[1] + matrix[8 + row] * vector[2] + matrix[12 + row] * vector[3];
  }
  if (clip[3] <= 0) return null;
  const ndcX = clip[0] / clip[3], ndcY = clip[1] / clip[3], ndcZ = clip[2] / clip[3];
  if (ndcZ < -1 || ndcZ > 1) return null;
  return [(ndcX * 0.5 + 0.5) * width, (1 - (ndcY * 0.5 + 0.5)) * height];
}

function normalize(vector) {
  const length = Math.hypot(...vector) || 1;
  return vector.map(value => value / length);
}
function cross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
function dot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

const SEGMENT_VERTEX_SHADER = `#version 300 es
in vec3 aStart;
in vec3 aEnd;
in float aAlong;
in float aSide;
in float aType;
uniform mat4 uViewProjection;
uniform vec2 uViewport;
uniform float uWidth;
out float vType;
void main() {
  vec4 startClip = uViewProjection * vec4(aStart, 1.0);
  vec4 endClip = uViewProjection * vec4(aEnd, 1.0);
  vec4 clip = mix(startClip, endClip, aAlong);
  vec2 startScreen = startClip.xy / startClip.w * uViewport;
  vec2 endScreen = endClip.xy / endClip.w * uViewport;
  vec2 direction = normalize(endScreen - startScreen + vec2(0.0001));
  vec2 normal = vec2(-direction.y, direction.x);
  clip.xy += normal * aSide * uWidth / uViewport * clip.w;
  gl_Position = clip;
  vType = aType;
}`;

const SEGMENT_FRAGMENT_SHADER = `#version 300 es
precision highp float;
in float vType;
uniform int uLayer;
uniform bool uLightTheme;
out vec4 color;
void main() {
  if (uLayer == 0) { color = uLightTheme ? vec4(0.06, 0.09, 0.15, 0.88) : vec4(0.005, 0.018, 0.024, 0.95); return; }
  if (uLayer == 2) { color = uLightTheme ? vec4(0.45, 0.52, 0.64, 0.24) : vec4(0.12, 0.31, 0.36, 0.35); return; }
  if (vType < 0.5) color = uLightTheme ? vec4(0.15, 0.39, 0.92, 1.0) : vec4(0.41, 0.82, 0.84, 1.0);
  else if (vType < 1.5) color = vec4(1.0, 0.69, 0.12, 1.0);
  else if (vType < 2.5) color = vec4(0.37, 0.88, 0.43, 1.0);
  else color = vec4(0.86, 0.40, 0.72, 1.0);
}`;

const POINT_VERTEX_SHADER = `#version 300 es
in vec3 aPosition;
in float aType;
in float aSize;
uniform mat4 uViewProjection;
uniform float uPixelRatio;
out float vType;
void main() {
  gl_Position = uViewProjection * vec4(aPosition, 1.0);
  gl_PointSize = aSize * uPixelRatio;
  vType = aType;
}`;

const POINT_FRAGMENT_SHADER = `#version 300 es
precision highp float;
in float vType;
uniform int uLayer;
uniform bool uLightTheme;
out vec4 color;
void main() {
  vec2 point = gl_PointCoord * 2.0 - 1.0;
  if (dot(point, point) > 1.0) discard;
  if (uLayer == 0) { color = uLightTheme ? vec4(0.06, 0.09, 0.15, 0.88) : vec4(0.01, 0.03, 0.04, 0.95); return; }
  if (vType < 0.5) color = uLightTheme ? vec4(0.15, 0.39, 0.92, 1.0) : vec4(0.41, 0.82, 0.84, 1.0);
  else if (vType < 1.5) color = vec4(1.0, 0.69, 0.12, 1.0);
  else if (vType < 2.5) color = vec4(0.37, 0.88, 0.43, 1.0);
  else if (vType < 9.5) color = vec4(0.86, 0.40, 0.72, 1.0);
  else if (vType < 10.5) color = vec4(0.26, 0.65, 0.91, 1.0);
  else if (vType < 11.5) color = vec4(1.0, 0.70, 0.10, 1.0);
  else if (vType < 12.5) color = vec4(0.37, 0.88, 0.43, 1.0);
  else if (vType < 13.5) color = vec4(0.68, 0.54, 0.83, 1.0);
  else if (vType < 14.5) color = vec4(0.48, 0.53, 0.53, 1.0);
  else color = vec4(0.83, 0.43, 0.69, 1.0);
}`;
