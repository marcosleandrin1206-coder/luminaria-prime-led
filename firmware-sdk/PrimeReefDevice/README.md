# PrimeReefDevice (biblioteca Arduino)

Implementa o [Protocolo Prime Reef v1](../../docs/PROTOCOLO.md) para qualquer aparelho ESP32 do
ecossistema Prime Reef Brasil. O firmware só declara seus **sensores**, **controles** e **alertas**.
O app Prime Reef encontra o aparelho na rede (mDNS `_primereef._tcp`) e monta a tela sozinho,
**sem precisar atualizar o app**.

## Instalação

Copie a pasta `PrimeReefDevice` para `Documentos/Arduino/libraries/` e reinicie a Arduino IDE.
Requer o core ESP32 da Espressif (usa `WebServer`, `ESPmDNS` e `WiFi`).

## Uso

```cpp
#include <WiFi.h>
#include <WebServer.h>
#include <PrimeReefDevice.h>

WebServer server(80);
PrimeReefDevice reef("bomba", "Wavemaker 2 canais", "1");   // tipo, modelo, versão do firmware
int velocidade = 50;
bool ligada = true;

void setup() {
  WiFi.begin("rede", "senha");
  while (WiFi.status() != WL_CONNECTED) delay(300);

  reef.addSensor("temp", "Temperatura do driver", "°C", [] { return lerTemp(); });
  reef.addSwitch("on", "Ligada", [] { return ligada; }, [](bool v) { ligada = v; });
  reef.addSlider("vel", "Velocidade", 0, 100, 1, "%", [] { return (float)velocidade; },
                 [](float v) { velocidade = v; });
  reef.addSelect("modo", "Modo", {"Constante", "Onda", "Aleatório"}, getModo, setModo);
  reef.addButton("feed", "Modo alimentação (10 min)", [] { alimentar(); });

  reef.begin(server);   // registra /api/info, /api/state, /api/set e anuncia no mDNS
  server.begin();
}

void loop() { server.handleClient(); }
```

Alertas aparecem no topo do app:

```cpp
reef.setAlert("nivel", PrimeReefDevice::Level::Warn, "Nível do reservatório baixo");
reef.clearAlert("nivel");
```

Veja o exemplo completo em [`examples/Dosadora`](examples/Dosadora/Dosadora.ino).

| Tipo (`type`) | Aparelho |
|---|---|
| `luminaria` | Iluminação |
| `dosadora` | Dosadoras |
| `bomba` | Circulação / wavemaker / retorno |
| `ato` | Reposição automática |
| `controlador` | Controladores, tomadas, termostato |
| `sensor` | Monitores (temperatura, pH, salinidade, nível) |
