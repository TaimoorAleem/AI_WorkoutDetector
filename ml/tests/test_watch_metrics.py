"""Wilson CI, PSI, rejection, tau selection and gate status logic (spec section 4)."""

import numpy as np
import pandas as pd
import pytest

from workout_ml.watch import metrics as M


def test_wilson_known_values():
    lo, hi = M.wilson_ci(8, 10)
    assert lo == pytest.approx(0.4902, abs=1e-3)
    assert hi == pytest.approx(0.9433, abs=1e-3)
    lo, hi = M.wilson_ci(0, 10)
    assert lo == 0.0 and hi == pytest.approx(0.2775, abs=1e-3)
    lo, hi = M.wilson_ci(10, 10)
    assert hi == 1.0 and lo == pytest.approx(0.7225, abs=1e-3)
    lo, hi = M.wilson_ci(36, 40)  # the ~40-set case in PROJECT.md 6.5
    assert hi - lo == pytest.approx(0.2, abs=0.05)
    assert np.isnan(M.wilson_ci(0, 0)[0])


def test_psi_identical_vs_shifted():
    rng = np.random.default_rng(0)
    a = rng.normal(0, 1, 5000)
    assert M.psi(a, rng.normal(0, 1, 5000)) < 0.05
    assert M.psi(a, rng.normal(2, 1, 5000)) > 0.25
    assert M.psi(np.ones(100), np.ones(100) * 3) > 0.25  # constant reference does not crash


def _win(specs):
    """specs: list of (set, kind, truth, [preds])."""
    rows = [{"set": s, "kind": k, "truth": t, "pred": p} for s, k, t, preds in specs for p in preds]
    return pd.DataFrame(rows)


def test_rejection_by_vote_share_threshold():
    win = _win(
        [
            ("a", "set", "bench", ["bench"] * 8 + ["ohp"] * 2),  # share 0.8
            ("b", "set", "bench", ["bench"] * 5 + ["ohp"] * 5),  # share 0.5 (tie -> bench)
            ("r1", "rest", "rest", ["rest"] * 9 + ["row"]),  # rest vote
            ("r2", "rest", "rest", ["row"] * 7 + ["rest"] * 3),  # confident lift: not rejected
            ("o1", "other", "other", ["squat"] * 4 + ["dead"] * 6),  # share 0.6
        ]
    )
    dec = M.segment_decisions(win, 0.6).set_index("set")
    assert dec.loc["a", "final"] == "bench"
    assert dec.loc["b", "final"] == "rejected"
    assert dec.loc["r1", "final"] == "rest"
    assert dec.loc["r2", "logged"]
    assert dec.loc["o1", "final"] == "dead"  # share 0.6 is not below tau 0.6
    rj = M.rejection(M.segment_decisions(win, 0.6))
    assert (rj["n"], rj["rejected"]) == (3, 1)
    rj = M.rejection(M.segment_decisions(win, 0.7))
    assert rj["rejected"] == 2  # o1 is now below tau
    assert M.segment_decisions(win, 0.0).set_index("set").loc["b", "final"] == "bench"


def test_select_tau_largest_within_two_points():
    specs = [(f"s{i}", "set", "bench", ["bench"] * 9 + ["ohp"]) for i in range(40)]  # share 0.9
    specs += [(f"t{i}", "set", "bench", ["bench"] * 5 + ["ohp"] * 4) for i in range(10)]  # 0.55
    out = M.select_tau(_win(specs))
    assert out["tau"] == pytest.approx(0.55 - 0.05) or out["tau"] == 0.55
    assert out["tau"] <= 0.55
    # unanimous fit set: tau is capped, not 1.0
    unanimous = _win([(f"s{i}", "set", "ohp", ["ohp"] * 10) for i in range(10)])
    assert M.select_tau(unanimous)["tau"] == M.TAU_MAX
    # no lift sets -> the default
    assert M.select_tau(_win([("r", "rest", "rest", ["rest"] * 5)]))["tau"] == M.DEFAULT_TAU


def test_prop_and_count_status_logic():
    assert M.prop_status(True, 95, 100, 0.90) == M.PASS
    assert M.prop_status(True, 18, 20, 0.90) == M.PROVISIONAL  # lower bound ~0.70 < 0.80
    assert M.prop_status(False, 50, 100, 0.90) == M.FAIL
    assert M.prop_status(True, 0, 0, 0.90) == M.NA
    assert M.count_status(True, 40) == M.PASS
    assert M.count_status(True, 10) == M.PROVISIONAL
    assert M.count_status(False, 10) == M.FAIL
    assert M.count_status(True, 0) == M.NA
    assert M.combine([M.PASS, M.PROVISIONAL]) == M.PROVISIONAL
    assert M.combine([M.PASS, M.FAIL, M.NA]) == M.FAIL
    assert M.suffix_status(M.PASS, True) == "PASS (in-sample)"
    assert M.suffix_status(M.PASS, False) == "PASS"


def _gate_inputs(n_correct, n_total, baseline):
    specs = [(f"s{i}", "set", "bench", ["bench"] * 10) for i in range(n_correct)]
    specs += [(f"w{i}", "set", "bench", ["ohp"] * 10) for i in range(n_total - n_correct)]
    specs += [(f"r{i}", "rest", "rest", ["rest"] * 10) for i in range(20)]
    win = _win(specs)
    dec = M.segment_decisions(win, 0.6)
    wm = M.window_metrics(win)
    reps = M.rep_summary(pd.DataFrame(columns=["label", "predicted", "reps_user"]))
    return M.evaluate_gates(dec=dec, win=win, wm=wm, reps=reps, baseline=baseline)


def test_set_accuracy_gate_absolute_and_baseline_relative():
    g = _gate_inputs(92, 100, baseline=None)
    assert g["set_accuracy"]["status"] == M.PASS
    g = _gate_inputs(92, 100, baseline=0.986)  # 0.92 is more than 5 pp below 0.986
    assert g["set_accuracy"]["status"] == M.FAIL
    g = _gate_inputs(80, 100, baseline=0.82)  # baseline below 0.90: only the 5 pp rule applies
    assert g["set_accuracy"]["status"] == M.PASS
    g = _gate_inputs(0, 0, baseline=0.986)
    assert g["set_accuracy"]["status"] == M.NA
    assert g["reps"]["status"] == M.NA
    assert g["rejection"]["status"] == M.PROVISIONAL or g["rejection"]["status"] == M.PASS
    assert g["calibration"]["status"] == M.NA


def test_recall_gate_ignores_classes_with_one_set():
    specs = [(f"a{i}", "set", "bench", ["bench"] * 10) for i in range(3)]
    specs += [(f"b{i}", "set", "squat", ["squat"] * 10) for i in range(3)]
    specs += [("c0", "set", "row", ["bench"] * 10)]  # one set, recall 0: insufficient data
    win = _win(specs)
    wm = M.window_metrics(win)
    g = M.evaluate_gates(
        dec=M.segment_decisions(win, 0.6),
        win=win,
        wm=wm,
        reps={"overall": None, "per_exercise": {}},
    )
    assert "row" in g["macro_f1"]["detail"].split("insufficient data:")[1]
    assert g["macro_f1"]["min_recall"] == 1.0
    assert wm["macro_f1"] < 0.85  # the row set still drags macro-F1 down


def test_rep_summary_and_bench_ohp():
    rows = pd.DataFrame(
        {
            "label": ["bench", "bench", "squat", "squat"],
            "predicted": [5, 7, 10, 9],
            "reps_user": [5, 5, 10, 10],
        }
    )
    s = M.rep_summary(rows)
    assert s["overall"]["mae"] == pytest.approx(0.75)
    assert s["overall"]["exact"] == pytest.approx(0.5)
    assert s["overall"]["within1"] == pytest.approx(0.75)
    assert s["per_exercise"]["bench"]["mae"] == pytest.approx(1.0)
    win = _win(
        [
            ("a", "set", "bench", ["ohp"] * 6 + ["bench"] * 4),
            ("b", "set", "ohp", ["ohp"] * 10),
        ]
    )
    b = M.bench_ohp(win, M.segment_decisions(win, 0.0))
    assert b["bench_as_ohp"]["windows"] == 6 and b["bench_as_ohp"]["sets"] == 1
    assert b["ohp_as_bench"]["windows"] == 0
