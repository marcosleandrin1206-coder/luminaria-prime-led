# Prime Reef Brasil

Firmware e aplicativo do ecossistema Prime Reef Brasil para aquários marinhos.

| Pasta | Conteúdo |
|---|---|
| `firmware.bin`, `version.txt` | Firmware da luminária (atualização remota OTA: **não mover/renomear**) |
| [`android-app/`](android-app) | App Android que controla todos os aparelhos Prime Reef da rede |
| [`dosadora/`](dosadora) | Firmware da Dosadora Prime Reef e as versões publicadas para atualização remota |
| [`docs/PROTOCOLO.md`](docs/PROTOCOLO.md) | Protocolo Prime Reef v1: padrão que todo aparelho novo implementa |
| [`firmware-sdk/PrimeReefDevice/`](firmware-sdk/PrimeReefDevice) | Biblioteca Arduino (ESP32) que implementa o protocolo, com exemplo de dosadora |
| [`tools/simulador.py`](tools/simulador.py) | Simula dosadora, bomba, ATO e sensor para testar o app sem hardware |

## Como encaixa

```
 App Android ──Wi-Fi local──┬── Luminária (API própria: /status, /live, /schedule...)
                            ├── Dosadora   ┐
                            ├── Bomba      │  Protocolo Prime Reef v1
                            ├── ATO        │  /api/info  /api/state  /api/set
                            └── Sensores   ┘  + mDNS _primereef._tcp
```

Os aparelhos novos usam a biblioteca `PrimeReefDevice`: declaram sensores, controles e alertas, e o
app monta a tela deles sozinho.
