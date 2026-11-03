#!/usr/bin/env python3
"""
Simulador de aparelhos Prime Reef (Protocolo Prime Reef v1) para testar o app sem hardware.

Uso:
    python3 simulador.py                    # dosadora na porta 8080
    python3 simulador.py --tipo bomba --porta 8081
    python3 simulador.py --tipo todos       # dosadora:8080, bomba:8081, ato:8082, sensor:8083

No app: Adicionar > "Adicionar pelo IP" > <ip-do-computador>:8080
(a busca automática só testa a porta 80; use --porta 80 com sudo para aparecer nela).
"""
import argparse
import json
import random
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PRESETS = {
    "dosadora": {
        "model": "Dosadora 3 canais (simulada)",
        "controls": [
            {"id": "auto", "label": "Dosagem automática", "type": "switch", "value": True},
            {"id": "modo", "label": "Distribuição", "type": "select", "value": "Hora em hora",
             "options": ["Hora em hora", "Uma vez ao dia"]},
            {"id": "ca_ml", "label": "Cálcio por dia", "type": "slider", "value": 20, "min": 0, "max": 200, "step": 1, "unit": "ml"},
            {"id": "kh_ml", "label": "Alcalinidade por dia", "type": "slider", "value": 20, "min": 0, "max": 200, "step": 1, "unit": "ml"},
            {"id": "ca_cal", "label": "Calibração Cálcio", "type": "number", "value": 1.0, "min": 0.1, "max": 10, "step": 0.01, "unit": "ml/s"},
            {"id": "ca_dose", "label": "Dosar 1 ml de Cálcio", "type": "button", "confirm": "Dosar 1 ml agora?"},
        ],
        "sensors": lambda st: [
            {"id": "ca_hoje", "label": "Cálcio hoje", "value": st["ca_hoje"], "unit": "ml"},
            {"id": "kh_hoje", "label": "Alcalinidade hoje", "value": 12.5, "unit": "ml"},
            {"id": "ca_res", "label": "Galão Cálcio", "value": st["ca_res"], "unit": "%",
             "status": "warn" if st["ca_res"] < 20 else "ok"},
        ],
        "state": {"ca_hoje": 8.0, "ca_res": 18},
    },
    "bomba": {
        "model": "Wavemaker (simulada)",
        "controls": [
            {"id": "on", "label": "Ligada", "type": "switch", "value": True},
            {"id": "vel", "label": "Velocidade", "type": "slider", "value": 60, "min": 0, "max": 100, "step": 1, "unit": "%"},
            {"id": "modo", "label": "Modo", "type": "select", "value": "Onda", "options": ["Constante", "Onda", "Aleatório"]},
            {"id": "feed", "label": "Modo alimentação (10 min)", "type": "button"},
        ],
        "sensors": lambda st: [
            {"id": "rpm", "label": "Rotação", "value": random.randint(1800, 2100), "unit": "rpm"},
            {"id": "watts", "label": "Consumo", "value": round(random.uniform(14, 16), 1), "unit": "W"},
        ],
        "state": {},
    },
    "ato": {
        "model": "Reposição automática (simulada)",
        "controls": [
            {"id": "on", "label": "Reposição ativa", "type": "switch", "value": True},
            {"id": "max", "label": "Limite diário", "type": "number", "value": 2.0, "min": 0.5, "max": 10, "step": 0.5, "unit": "L"},
        ],
        "sensors": lambda st: [
            {"id": "nivel", "label": "Nível do sump", "value": "Normal"},
            {"id": "hoje", "label": "Reposto hoje", "value": 1.2, "unit": "L"},
            {"id": "res", "label": "Reservatório", "value": 9, "unit": "%", "status": "alarm"},
        ],
        "alerts": [{"level": "alarm", "msg": "Reservatório de água doce quase vazio"}],
        "state": {},
    },
    "sensor": {
        "model": "Monitor de parâmetros (simulado)",
        "controls": [],
        "sensors": lambda st: [
            {"id": "temp", "label": "Temperatura", "value": round(random.uniform(25.2, 25.8), 1), "unit": "°C"},
            {"id": "ph", "label": "pH", "value": round(random.uniform(8.05, 8.2), 2)},
            {"id": "sal", "label": "Salinidade", "value": 1.025, "unit": "sg"},
        ],
        "state": {},
    },
}


def make_handler(tipo, port):
    p = PRESETS[tipo]
    controls = json.loads(json.dumps(p["controls"]))
    st = dict(p["state"])
    lock = threading.Lock()
    dev_id = f"{tipo}-sim{port}"

    def state():
        alerts = list(p.get("alerts", []))
        if tipo == "dosadora" and st["ca_res"] < 20:
            alerts.append({"level": "warn", "msg": "Galão de Cálcio abaixo de 20%"})
        return {"sensors": p["sensors"](st), "controls": controls, "alerts": alerts}

    class H(BaseHTTPRequestHandler):
        def _json(self, code, obj):
            body = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            u = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            with lock:
                if u.path == "/api/info":
                    return self._json(200, {"api": 1, "id": dev_id, "type": tipo, "model": p["model"],
                                            "name": p["model"].split(" (")[0], "fw": "1"})
                if u.path == "/api/state":
                    return self._json(200, state())
                if u.path == "/api/set":
                    c = next((c for c in controls if c["id"] == q.get("id")), None)
                    v = q.get("v", "")
                    if c is None:
                        return self._json(400, {"error": f"Controle desconhecido: {q.get('id')}"})
                    t = c["type"]
                    if t == "switch":
                        c["value"] = v in ("1", "true", "on")
                    elif t in ("slider", "number"):
                        try:
                            f = float(v)
                        except ValueError:
                            return self._json(400, {"error": "Valor inválido"})
                        if not c["min"] <= f <= c["max"]:
                            return self._json(400, {"error": f"Valor fora da faixa ({c['min']} a {c['max']})"})
                        c["value"] = f
                    elif t == "select":
                        if v not in c["options"]:
                            return self._json(400, {"error": "Opção inválida"})
                        c["value"] = v
                    elif t == "button" and tipo == "dosadora" and c["id"] == "ca_dose":
                        st["ca_hoje"] += 1
                    print(f"[{tipo}:{port}] {c['id']} = {v}")
                    return self._json(200, state())
            self.send_error(404)

        def log_message(self, *a):
            pass

    return H


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tipo", default="dosadora", choices=list(PRESETS) + ["todos"])
    ap.add_argument("--porta", type=int, default=8080)
    a = ap.parse_args()
    tipos = list(PRESETS) if a.tipo == "todos" else [a.tipo]
    for i, t in enumerate(tipos):
        port = a.porta + i
        srv = ThreadingHTTPServer(("0.0.0.0", port), make_handler(t, port))
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        print(f"{t:9s} -> http://0.0.0.0:{port}/api/state")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
