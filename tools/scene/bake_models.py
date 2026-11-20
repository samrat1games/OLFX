#!/usr/bin/env python3
"""Download CC0 Poly Haven props and bake them to a compact mesh format for the OLFX scene.

.mesh: magic 'OLM1', u32 vertexCount, u32 indexCount, then vertexCount*8 float32 (pos3 nrm3 uv2) and indexCount u32.
Run: python3 tools/scene/bake_models.py <outdir>. Poly Haven assets are CC0 (https://polyhaven.com/license)."""
import json, struct, sys, urllib.request, os
import numpy as np

MODELS = ["Sofa_01", "CoffeeTable_01", "ArmChair_01", "Shelf_01"]
get = lambda u: urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "olfx-asset-baker/1.0"}), timeout=60).read()

def quat_mat(q):
    x, y, z, w = q
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
                     [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])

def node_matrix(n):
    if "matrix" in n: return np.array(n["matrix"]).reshape(4, 4).T
    m = np.eye(4)
    s = n.get("scale", [1, 1, 1]); m[:3, :3] = quat_mat(n.get("rotation", [0, 0, 0, 1])) @ np.diag(s)
    m[:3, 3] = n.get("translation", [0, 0, 0]); return m

def accessor(g, buf, i):
    a = g["accessors"][i]; bv = g["bufferViews"][a["bufferView"]]
    comp = {5120: "b", 5121: "B", 5122: "h", 5123: "H", 5125: "I", 5126: "f"}[a["componentType"]]
    n = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[a["type"]]
    off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    stride = bv.get("byteStride", 0)
    dt = np.dtype(comp).newbyteorder("<")
    if stride and stride != dt.itemsize * n:
        raw = np.frombuffer(buf, np.uint8, count=a["count"] * stride, offset=off).reshape(a["count"], stride)
        return np.frombuffer(raw[:, :dt.itemsize * n].tobytes(), dt).reshape(a["count"], n)
    return np.frombuffer(buf, dt, count=a["count"] * n, offset=off).reshape(a["count"], n)

def bake(name, out):
    info = json.loads(get(f"https://api.polyhaven.com/files/{name}"))["gltf"]["1k"]["gltf"]
    g = json.loads(get(info["url"]))
    inc = info["include"]
    binuri = g["buffers"][0]["uri"]; buf = get(inc[binuri]["url"])
    diff = [k for k in inc if k.endswith("_diff_1k.jpg")][0]
    open(os.path.join(out, name + ".jpg"), "wb").write(get(inc[diff]["url"]))
    verts, idx, base = [], [], 0
    def walk(ni, parent):
        nonlocal base
        n = g["nodes"][ni]; m = parent @ node_matrix(n)
        if "mesh" in n:
            for p in g["meshes"][n["mesh"]]["primitives"]:
                at = p["attributes"]
                pos = accessor(g, buf, at["POSITION"]).astype(np.float64); nrm = accessor(g, buf, at["NORMAL"]).astype(np.float64)
                uv = accessor(g, buf, at["TEXCOORD_0"]).astype(np.float64)
                pos = (m[:3, :3] @ pos.T).T + m[:3, 3]; nrm = (np.linalg.inv(m[:3, :3]).T @ nrm.T).T
                nrm /= np.maximum(np.linalg.norm(nrm, axis=1, keepdims=True), 1e-9)
                verts.append(np.hstack([pos, nrm, uv]).astype(np.float32))
                idx.append(accessor(g, buf, p["indices"]).reshape(-1).astype(np.uint32) + base); base += len(pos)
        for c in n.get("children", []): walk(c, m)
    for r in g["scenes"][g.get("scene", 0)]["nodes"]: walk(r, np.eye(4))
    v = np.vstack(verts); i = np.concatenate(idx)
    mn = v[:, :3].min(0); mx = v[:, :3].max(0); v[:, 0] -= (mn[0] + mx[0]) / 2; v[:, 2] -= (mn[2] + mx[2]) / 2; v[:, 1] -= mn[1]
    with open(os.path.join(out, name + ".mesh"), "wb") as f:
        f.write(b"OLM1" + struct.pack("<II", len(v), len(i)) + v.astype("<f4").tobytes() + i.astype("<u4").tobytes())
    print(name, len(v), "verts", len(i) // 3, "tris", "size(m)", (mx - mn).round(2))

if __name__ == "__main__":
    os.makedirs(sys.argv[1], exist_ok=True)
    for m in MODELS: bake(m, sys.argv[1])
