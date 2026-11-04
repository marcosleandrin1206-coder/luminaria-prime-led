# Dosadora Prime Reef (1 canal)

Firmware da dosadora própria da Prime Reef, para a placa **LC Technology ESP12F_Relay_X1**
(ESP8266 ESP-12F, 1 relé). Aparece sozinha no app Prime Reef (Protocolo Prime Reef v1) e tem uma
página própria (abra o IP da dosadora no navegador ou pelo app).

| Arquivo | O que é |
|---|---|
| `DosadoraPrimeReef/DosadoraPrimeReef.ino` | código-fonte |
| `version.txt` | versão publicada (as dosadoras comparam com o `FW_NUM` delas) |
| `bin/firmware-<N>.bin` | firmware de cada versão publicada (baixado pela atualização remota) |
| `firmware.bin` | cópia da versão mais recente, para gravar manualmente |

## Como publicar uma atualização

1. No `DosadoraPrimeReef.ino`, aumente **`FW_VERSION`** e **`FW_NUM`** (ex.: `"5"` e `5`).
2. Envie para a branch **main**.
3. O GitHub Actions (*Firmware Dosadora*) compila, cria `bin/firmware-5.bin` e atualiza `version.txt`.
4. Em até 6 h cada dosadora encontra a versão nova e instala sozinha quando a bomba estiver livre
   (ou toque em *Verificar atualização* na aba Sistema da página dela para ser na hora).

Se o `FW_NUM` não aumentar, nada é publicado: dá para mexer no código sem afetar as dosadoras instaladas.

## Primeira gravação (pela serial)

Arduino IDE com o core ESP8266 e a biblioteca [`PrimeReefDevice`](../firmware-sdk/PrimeReefDevice) instalada:
placa **Generic ESP8266 Module**, Flash Size **4MB (FS:2MB OTA:~1019KB)**, Erase Flash **All Flash Contents**.
Detalhes no cabeçalho do `.ino`.
