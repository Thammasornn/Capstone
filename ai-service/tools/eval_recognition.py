#!/usr/bin/env python3
"""
วัดความแม่นยำ + ความเร็วของการจดจำใบหน้าด้วยโมเดลจริง (YOLO face-pose → MiniFASNetV2 → SFace → cosine) จากภาพจริง
พิมพ์ตาราง markdown ไว้ใส่สไลด์ · ภาพจริงห้าม commit (PDPA) — เก็บนอก repo และลบเมื่อวัดเสร็จ

โครงโฟลเดอร์ (ชื่อโฟลเดอร์ = รหัสคน เช่น p01 · ห้ามใช้ชื่อจริง):
  enroll/<คน>/*.jpg     ภาพลงทะเบียน (3–5 รูปต่อคน แบบเดียวกับหน้าเว็บ)
  probe/<คน>/*.jpg      ภาพทดสอบจากกล้องห้อง (คนที่ไม่มีใน enroll = คนแปลกหน้า ใช้วัดการจับคู่ผิด)
  spoof/<ชนิด>/*.jpg    (ไม่บังคับ) ภาพหลอก: print / phone / screen / video → วัด anti-spoofing แยกตามชนิด
ภาพ probe ที่มีหลายใบหน้า ใช้ใบหน้าที่ใหญ่ที่สุด (ครอปให้เหลือคนเดียวจะแม่นกว่า)

  python tools/eval_recognition.py --enroll ~/eval/enroll --probe ~/eval/probe [--spoof ~/eval/spoof] \
      --models /opt/bangmod/models --out ~/eval/report.md
"""
import argparse
import os
import sys
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def list_people(root: Path) -> dict[str, list[Path]]:
    out = {}
    for d in sorted(p for p in root.iterdir() if p.is_dir() and not p.name.startswith(".")):
        imgs = sorted(p for p in d.rglob("*") if p.suffix.lower() in IMAGE_EXT)
        if imgs:
            out[d.name] = imgs
    return out


def list_images(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("*") if p.suffix.lower() in IMAGE_EXT)


def largest(faces):
    return max(faces, key=lambda f: float(f[2]) * float(f[3])) if faces else None


class Runner:
    """เรียกโมเดล + จับเวลา (ms) ของแต่ละขั้น"""

    def __init__(self, vision, read=None):
        import cv2
        self.vision = vision
        self.read = read or (lambda p: cv2.imread(str(p), cv2.IMREAD_COLOR))
        self.ms: dict[str, list[float]] = {"detect": [], "feature": [], "liveness": []}

    def _t(self, name, fn, *a):
        t = time.perf_counter()
        r = fn(*a)
        self.ms[name].append((time.perf_counter() - t) * 1000)
        return r

    def face(self, path: Path, min_face_px: int = 0, min_conf: float = 0.0, with_liveness: bool = False):
        """คืน (embedding | None, liveness | None, เหตุผลที่ใช้ไม่ได้ | None)"""
        img = self.read(path)
        if img is None:
            return None, None, "read"
        faces = self._t("detect", self.vision.detect, img)
        f = largest(faces)
        if f is None:
            return None, None, "no_face"
        if f[-1] < min_conf or min(f[2], f[3]) < min_face_px:
            return None, None, "too_small"
        live = None
        if with_liveness and getattr(self.vision, "ANTISPOOF_READY", False):
            try:
                live = self._t("liveness", self.vision.liveness, img, f)
            except self.vision.AntiSpoofError:
                live = None                           # ตรวจไม่ได้ = ไม่นับ (รายงานแยก)
        try:
            return self._t("feature", self.vision.feature, img, f), live, None
        except ValueError:                            # vision.EmbeddingError
            return None, live, "bad_embedding"


def stat(a) -> str:
    if not a:
        return "-"
    a = np.asarray(a)
    return f"{a.mean():.1f} | {np.percentile(a, 50):.1f} | {np.percentile(a, 95):.1f}"


def pct(n, d) -> str:
    return "-" if d == 0 else f"{100 * n / d:.1f}% ({n}/{d})"


def evaluate(runner: Runner, enroll: dict[str, list[Path]], probe: dict[str, list[Path]],
             spoof: list[Path], thresholds: list[float], margin: float, min_face_px: int, min_conf: float,
             live_thresholds: list[float]) -> str:
    from gallery import RoomGallery, best_matches

    lines = []
    # ---- ลงทะเบียน ----
    owners, rows, real_live, enroll_fail = [], [], [], 0
    for person, imgs in enroll.items():
        for p in imgs:
            emb, live, why = runner.face(p, with_liveness=True)
            if emb is None:
                enroll_fail += 1
                continue
            owners.append(person)
            rows.append(emb)
            if live is not None:
                real_live.append(live)
    if not rows:
        raise SystemExit("ไม่มีภาพลงทะเบียนที่ใช้ได้")
    gallery = RoomGallery("eval", True, owners, np.stack(rows).astype(np.float32))
    known = set(owners)

    # ---- ทดสอบ ----
    probes = []          # (truth, embedding)
    miss = {"no_face": 0, "too_small": 0, "read": 0, "bad_embedding": 0}
    total_probe = 0
    for person, imgs in probe.items():
        for p in imgs:
            total_probe += 1
            emb, live, why = runner.face(p, min_face_px, min_conf, with_liveness=True)
            if emb is None:
                miss[why] += 1
                continue
            probes.append((person, emb))
            if live is not None:
                real_live.append(live)
    if not probes:
        raise SystemExit("ไม่มีภาพทดสอบที่ตรวจพบใบหน้า")
    feats = np.stack([e for _, e in probes]).astype(np.float32)
    truth = [t for t, _ in probes]
    ids = list(dict.fromkeys(owners))
    sims = feats @ gallery.matrix.T
    per = np.full((len(feats), len(ids)), -1.0)
    for col, o in enumerate(owners):
        k = ids.index(o)
        per[:, k] = np.maximum(per[:, k], sims[:, col])
    genuine = [per[i, ids.index(t)] for i, t in enumerate(truth) if t in known]
    impostor = [per[i, k] for i, t in enumerate(truth) for k, o in enumerate(ids) if o != t]
    n_known = sum(1 for t in truth if t in known)
    n_unknown = len(truth) - n_known
    rank1 = sum(1 for i, t in enumerate(truth) if t in known and ids[int(np.argmax(per[i]))] == t)

    lines += ["## ข้อมูลที่ใช้วัด", "",
              "| รายการ | ค่า |", "|---|---|",
              f"| คนที่ลงทะเบียน | {len(ids)} คน · {len(rows)} ภาพ (ใช้ไม่ได้ {enroll_fail}) |",
              f"| ภาพทดสอบ | {total_probe} ภาพ · คนที่ลงทะเบียน {n_known} · คนแปลกหน้า {n_unknown} |",
              f"| ตรวจพบใบหน้าในภาพทดสอบ | {pct(len(probes), total_probe)} · ไม่พบ {miss['no_face']} · "
              f"เล็ก/ไม่ชัด (< {min_face_px} px หรือ conf < {min_conf}) {miss['too_small']} · อ่านไม่ได้ {miss['read']} |",
              f"| คู่เทียบ (genuine / impostor) | {len(genuine)} / {len(impostor)} |",
              f"| Rank-1 (ไม่ใช้ threshold) | {pct(rank1, n_known)} |", ""]

    lines += [f"## ผลตาม threshold (margin {margin})", "",
              "TAR = คนจริงคะแนนถึง threshold · FAR = เทียบกับคนอื่นแล้วคะแนนถึง threshold (1:1) · "
              "ระบบ = กติกาเดียวกับตัวอ่านกล้อง (คะแนนสูงสุด ≥ threshold และห่างคนที่ 2 ≥ margin) · "
              "ทุกคอลัมน์คิดจากภาพทดสอบที่ตรวจพบใบหน้า", "",
              "| threshold | TAR | FAR | ระบบ: ถูกคน | ระบบ: ผิดคน | ระบบ: ไม่จับคู่ | คนแปลกหน้าถูกจับคู่ | Accuracy |",
              "|---|---|---|---|---|---|---|---|"]
    g, im = np.asarray(genuine), np.asarray(impostor)
    for th in thresholds:
        res = best_matches(feats, gallery, th, margin)
        correct = sum(1 for (sid, _), t in zip(res, truth) if t in known and sid == t)
        wrong = sum(1 for (sid, _), t in zip(res, truth) if t in known and sid is not None and sid != t)
        rejected = n_known - correct - wrong
        fa_unknown = sum(1 for (sid, _), t in zip(res, truth) if t not in known and sid is not None)
        acc = correct + (n_unknown - fa_unknown)
        lines.append(f"| {th:.3f} | {pct(int((g >= th).sum()), g.size)} | {pct(int((im >= th).sum()), im.size)} | "
                     f"{pct(correct, n_known)} | {pct(wrong, n_known)} | {pct(rejected, n_known)} | "
                     f"{pct(fa_unknown, n_unknown)} | {pct(acc, len(truth))} |")
    lines.append("")

    # ---- anti-spoofing ----
    spoof_live: dict[str, list[float]] = {}
    for p in spoof:
        _, live, why = runner.face(p, with_liveness=True)
        if live is not None:
            kind = p.parent.name                     # ชนิด = ชื่อโฟลเดอร์ (print / phone / …)
            spoof_live.setdefault(kind, []).append(live)
    if spoof and real_live:
        r = np.asarray(real_live)
        s = np.asarray([v for vs in spoof_live.values() for v in vs])
        kinds = sorted(spoof_live)
        lines += ["## Anti-spoofing (MiniFASNetV2)", "",
                  f"ภาพจริง {r.size} ภาพ (enroll + probe) · ภาพหลอก {s.size}/{len(spoof)} ภาพที่ตรวจพบใบหน้า · "
                  "BPCER = คนจริงถูกปฏิเสธ · APCER = ภาพหลอกผ่าน (รวม และแยกตามโฟลเดอร์ชนิด)", "",
                  "| ANTISPOOF_MIN_REAL | BPCER | APCER | " + " | ".join(f"APCER {k}" for k in kinds) + " |",
                  "|---|---|---|" + "---|" * len(kinds)]
        for th in live_thresholds:
            per = " | ".join(pct(int((np.asarray(spoof_live[k]) >= th).sum()), len(spoof_live[k])) for k in kinds)
            lines.append(f"| {th:.2f} | {pct(int((r < th).sum()), r.size)} | {pct(int((s >= th).sum()), s.size)} | "
                         + per + " |")
        lines.append("")
    elif spoof:
        lines += ["## Anti-spoofing", "", "ไม่ได้วัด: ไม่มีโมเดล MiniFASNetV2 (ANTISPOOF_READY = False)", ""]

    # ---- ความเร็ว ----
    ms = runner.ms
    per_face = [d + f for d, f in zip(ms["detect"], ms["feature"])]
    lines += ["## ความเร็ว (ms · ต่อการเรียก 1 ครั้ง · เครื่องที่รันสคริปต์นี้)", "",
              "| ขั้น | n | mean | p50 | p95 |", "|---|---|---|---|---|",
              f"| YOLO หาใบหน้า (ต่อภาพ) | {len(ms['detect'])} | {stat(ms['detect'])} |",
              f"| SFace embedding (ต่อใบหน้า) | {len(ms['feature'])} | {stat(ms['feature'])} |",
              f"| Anti-spoofing (ต่อใบหน้า) | {len(ms['liveness'])} | {stat(ms['liveness'])} |",
              f"| รวม หา + จดจำ 1 ใบหน้า | {len(per_face)} | {stat(per_face)} |", ""]
    return "\n".join(lines)


def floats(s: str) -> list[float]:
    return [float(x) for x in s.split(",") if x.strip()]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--enroll", required=True, type=Path)
    ap.add_argument("--probe", required=True, type=Path)
    ap.add_argument("--spoof", type=Path)
    ap.add_argument("--models", default=os.getenv("MODELS_DIR", "/models"))
    ap.add_argument("--thresholds", default="0.30,0.363,0.40,0.45,0.50,0.55")
    ap.add_argument("--margin", type=float, default=float(os.getenv("MATCH_MARGIN", "0.05")))
    ap.add_argument("--min-face-px", type=int, default=int(os.getenv("CAMERA_MIN_FACE_PX", "40")))
    ap.add_argument("--min-conf", type=float, default=float(os.getenv("CAMERA_MIN_CONF", "0.6")))
    ap.add_argument("--liveness-thresholds", default="0.5,0.6,0.7,0.8,0.9")
    ap.add_argument("--out", type=Path, help="เขียนผลเป็นไฟล์ markdown ด้วย")
    a = ap.parse_args(argv)

    m = a.models
    os.environ.setdefault("YOLO_FACE_MODEL", f"{m}/yolov8n-face-pose.pt")
    os.environ.setdefault("SFACE_MODEL", f"{m}/face_recognition_sface_2021dec.onnx")
    os.environ.setdefault("ANTISPOOF_MODEL", f"{m}/2.7_80x80_MiniFASNetV2.onnx")
    os.environ.setdefault("ANTISPOOF", "log")              # คำนวณคะแนนเสมอ (ไม่ปฏิเสธ) ไว้หาเกณฑ์
    os.environ.setdefault("YOLO_CONFIG_DIR", "/tmp/ultralytics")
    import platform

    import vision  # โหลดโมเดลจริง (ช้าครั้งแรก)

    enroll, probe = list_people(a.enroll), list_people(a.probe)
    spoof = list_images(a.spoof) if a.spoof else []
    runner = Runner(vision)
    first = next(iter(enroll.values()))[0]
    runner.face(first, with_liveness=True)               # อุ่นเครื่อง (ครั้งแรกช้ากว่าปกติ) ไม่นับเวลา
    runner.ms = {k: [] for k in runner.ms}

    report = "\n".join([
        "# ผลวัดการจดจำใบหน้า (Bangmod Smart Attendance)", "",
        f"วันที่ {time.strftime('%Y-%m-%d %H:%M')} · เครื่อง {platform.machine()} {platform.processor() or ''} · "
        f"CPU {os.cpu_count()} core · YOLOv8n-face-pose + SFace 2021dec + MiniFASNetV2 "
        f"({'พร้อม' if vision.ANTISPOOF_READY else 'ไม่มี'})", "",
        evaluate(runner, enroll, probe, spoof, floats(a.thresholds), a.margin, a.min_face_px, a.min_conf,
                 floats(a.liveness_thresholds)),
    ])
    print(report)
    if a.out:
        a.out.write_text(report + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
