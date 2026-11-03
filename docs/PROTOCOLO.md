# Protocolo Prime Reef v1

Padrão que **todo dispositivo Prime Reef Brasil** (luminária, dosadora, bomba, ATO, controlador,
sensores...) implementa para ser encontrado e controlado pelo app Prime Reef, sem precisar
atualizar o app a cada aparelho novo.

A ideia central: o dispositivo **descreve a si mesmo**. Ele informa quais sensores tem e quais
controles aceita, e o app monta a tela automaticamente.

> A biblioteca Arduino em [`firmware-sdk/PrimeReefDevice`](../firmware-sdk/PrimeReefDevice)
> implementa tudo isto. Basta declarar os sensores/controles no `setup()`.

## 1. Rede e descoberta

- ESP32 conectado ao Wi-Fi da casa, servidor HTTP na **porta 80**, respostas em JSON UTF-8.
- Hostname mDNS: `<tipo>-xxxxxx` (últimos 3 bytes do MAC em hexadecimal), ex.: `dosadora-a1b2c3.local`.
- Serviço mDNS: **`_primereef._tcp`**, porta 80, com registros TXT:

| TXT | Exemplo | Significado |
|---|---|---|
| `type` | `dosadora` | tipo do aparelho (tabela abaixo) |
| `api` | `1` | versão deste protocolo |
| `id` | `dosadora-a1b2c3` | identificador único e estável |
| `fw` | `3` | versão do firmware |

O app também varre a sub-rede chamando `GET /api/info` em cada IP, então o mDNS é recomendado,
mas não obrigatório.

### Tipos

| `type` | Uso |
|---|---|
| `luminaria` | Iluminação |
| `kh` | Medidor de KH (AquaPrime KH) |
| `dosadora` | Bombas dosadoras |
| `bomba` | Circulação, wavemaker, retorno |
| `ato` | Reposição automática de água |
| `controlador` | Controladores, tomadas/relés, termostato |
| `sensor` | Monitores (temperatura, pH, salinidade, nível...) |

Tipos desconhecidos aparecem no app como "Outros dispositivos". Novos tipos podem ser criados
livremente.

## 2. `GET /api/info`

```json
{
  "api": 1,
  "id": "dosadora-a1b2c3",
  "type": "dosadora",
  "model": "Dosadora 4 canais",
  "name": "Dosadora do sump",
  "fw": "3"
}
```

## 3. `GET /api/state`

```json
{
  "sensors": [
    { "id": "temp", "label": "Temperatura", "value": 25.4, "unit": "°C", "status": "ok" },
    { "id": "nivel", "label": "Reservatório", "value": "Baixo", "status": "warn" }
  ],
  "controls": [
    { "id": "auto",  "label": "Dosagem automática", "type": "switch", "value": true },
    { "id": "ml_ca", "label": "Cálcio por dia", "type": "slider", "value": 20, "min": 0, "max": 100, "step": 1, "unit": "ml" },
    { "id": "modo",  "label": "Modo", "type": "select", "value": "Pulso", "options": ["Constante", "Pulso", "Aleatório"] },
    { "id": "cal",   "label": "Vazão calibrada", "type": "number", "value": 1.25, "min": 0, "max": 10, "step": 0.01, "unit": "ml/s" },
    { "id": "dose1", "label": "Dosar 1 ml (Cálcio)", "type": "button", "confirm": "Dosar 1 ml agora?" }
  ],
  "alerts": [
    { "level": "warn", "msg": "Reservatório de cálcio abaixo de 20%" }
  ]
}
```

### Sensores
| Campo | Obrigatório | Descrição |
|---|---|---|
| `id` | sim | identificador único no aparelho |
| `label` | sim | nome exibido |
| `value` | sim | número ou texto |
| `unit` | não | unidade exibida após o valor |
| `status` | não | `ok`, `warn` ou `alarm` (o app colore o valor) |

### Controles
| `type` | Campos extras | Valor enviado em `/api/set` |
|---|---|---|
| `switch` | `value` (bool) | `1` ou `0` |
| `slider` | `value`, `min`, `max`, `step`, `unit` | número |
| `number` | `value`, `min`, `max`, `step`, `unit` | número |
| `select` | `value`, `options` (lista de textos) | texto da opção |
| `button` | `confirm` (opcional: pergunta antes de executar) | `1` |

### Alertas
`level`: `info`, `warn` ou `alarm`. Alertas `warn`/`alarm` aparecem no topo do app.

## 4. `GET /api/set?id=<id>&v=<valor>`

Altera um controle. Resposta: o novo `/api/state` (HTTP 200), ou HTTP 400 com
`{"error":"mensagem"}` se o `id` ou o valor for inválido.

## 5. Opcional

- `GET /`: painel web próprio do aparelho (o app oferece o atalho "Abrir painel web").
- `GET /checkupdate`: atualização remota de firmware, no mesmo padrão da luminária.

## Compatibilidade com a luminária atual

O firmware atual da luminária (v9) ainda não tem `/api/*`. O app a reconhece pela API própria
dela (`/status`, `/live`, `/schedule`...) e mostra a tela especializada (controle manual,
programação com curva, grupo, atualização). Se no futuro a luminária ganhar `/api/info`
com `"type":"luminaria"`, o app continua usando a tela especializada.
