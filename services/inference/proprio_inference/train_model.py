"""Train the production candidate on all MetaMotion data and save it for the service.

    python -m proprio_inference.train_model --out models/proprio-classifier [--raw data/raw/MetaMotion]
    python -m proprio_inference.train_model --out models/proprio-classifier --synthetic [--if-missing]

M1 (KNN, ``basic+derived+pca+temporal``, rotation augmentation D-018) is trained exactly as the
Phase 3 harness's ``ModelBank`` does it; the output directory (``model.joblib`` + ``metadata.json``)
is what ``MODEL_URI`` points at. Metadata: name, version (git SHA + data md5), feature set, classes,
default tau.

``--synthetic`` trains on generated data instead (no MetaMotion needed): a throwaway fixture for the
local stack and CI, named ``synthetic-fixture`` so it can never be mistaken for a real model.
"""

from __future__ import annotations

import argparse
import logging
from pathlib import Path

from workout_ml.watch import metrics
from workout_ml.watch.predict import BUNDLE_FILE, DEFAULT_MODEL_ID, train_bundle

from proprio_inference import synthetic


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--out", required=True, help="output directory for the model bundle")
    p.add_argument("--raw", default=None, help="MetaMotion raw data dir (default: config)")
    p.add_argument("--model", default=DEFAULT_MODEL_ID, help="harness model id (default M1)")
    p.add_argument("--tau", type=float, default=metrics.DEFAULT_TAU, help="default tau to record")
    p.add_argument("--name", default="proprio-classifier")
    p.add_argument("--version", default=None, help="override the git-sha + data-md5 version")
    p.add_argument(
        "--synthetic",
        action="store_true",
        help="train a throwaway FIXTURE on generated data (name synthetic-fixture), not a real model",
    )
    p.add_argument(
        "--if-missing", action="store_true", help="do nothing when --out already holds a model"
    )
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    if args.if_missing and (Path(args.out) / BUNDLE_FILE).is_file():
        print(f"model already present in {args.out}; nothing to do")
        return 0

    if args.synthetic:
        bundle = train_bundle(
            synthetic.mm_cleaned(),
            model_id=args.model,
            aug_cfg=synthetic.SYNTHETIC_AUGMENTATION,
            tau_default=args.tau,
            name=synthetic.SYNTHETIC_NAME,
            version=synthetic.SYNTHETIC_VERSION,
        )
    else:
        from workout_ml.watch.common import load_metamotion_cleaned

        mm = load_metamotion_cleaned(args.raw)
        bundle = train_bundle(
            mm, model_id=args.model, tau_default=args.tau, name=args.name, version=args.version
        )
    out = bundle.save(Path(args.out))
    print(f"saved {bundle.name} {bundle.version} ({len(bundle.classes)} classes) to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
