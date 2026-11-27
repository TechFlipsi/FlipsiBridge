"""W23-Tests: Auto-Lösch-Sweep für offline Geräte (Besitzer-Regel 18.11.).

Deckt: 14-Tage-Frist, Admin-Schutz, enrolled_at-Fallback, Feature-Aus-Schalter.
"""
import importlib
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest


def _relay_mod():
    for name in ("tools.android_relay", "android_relay"):
        try:
            return importlib.import_module(name)
        except ImportError:
            continue
    pytest.skip("android_relay nicht importierbar")


@pytest.fixture()
def sweep():
    return _relay_mod()._registry_sweep


def _alt_tage(tage):
    return (time.time() - tage * 86400) * 1000


def test_alt_non_admin_wird_geloescht(sweep, monkeypatch):
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_MAX_IDLE_DAYS", "14")
    entries = {"alt1": {"admin": False,
                       "last_seen": _alt_tage(20),
                       "enrolled_at": _alt_tage(20)}}
    assert sweep(entries) == ["alt1"]
    assert "alt1" not in entries


def test_admin_bleibt_immer_geschuetzt(sweep, monkeypatch):
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_MAX_IDLE_DAYS", "14")
    entries = {"admin1": {"admin": True,
                          "last_seen": _alt_tage(30),
                          "enrolled_at": _alt_tage(30)}}
    assert sweep(entries) == []
    assert "admin1" in entries


def test_frisches_geraet_bleibt(sweep, monkeypatch):
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_MAX_IDLE_DAYS", "14")
    jetzt = time.time() * 1000
    entries = {"neu1": {"admin": False, "last_seen": jetzt,
                        "enrolled_at": jetzt}}
    assert sweep(entries) == []
    assert "neu1" in entries


def test_nie_online_enrolled_vor_15_tagen_wird_geloescht(sweep, monkeypatch):
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_MAX_IDLE_DAYS", "14")
    entries = {"w1": {"admin": False, "last_seen": 0,
                     "enrolled_at": _alt_tage(15)}}
    assert sweep(entries) == ["w1"]


def test_frisch_enrolled_ohne_connect_bleibt(sweep, monkeypatch):
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_MAX_IDLE_DAYS", "14")
    entries = {"w2": {"admin": False, "last_seen": 0,
                      "enrolled_at": time.time() * 1000}}
    assert sweep(entries) == []
    assert "w2" in entries


def test_feature_ausschalter(sweep, monkeypatch):
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_MAX_IDLE_DAYS", "0")
    entries = {"alt2": {"admin": False,
                        "last_seen": _alt_tage(30),
                        "enrolled_at": _alt_tage(30)}}
    assert sweep(entries) == []
    assert "alt2" in entries

