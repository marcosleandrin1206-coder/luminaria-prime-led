# App Android — Prime Reef Brasil

Aplicativo Android para controlar **todo o ecossistema Prime Reef Brasil** em um só lugar:
luminárias, dosadoras, AquaPrime KH e os próximos produtos Prime Reef.
Ele conversa direto com cada aparelho pela rede Wi-Fi local (não precisa de nuvem nem de conta).

## Como o app suporta aparelhos que ainda nem existem

Todo aparelho novo segue o [Protocolo Prime Reef v1](../docs/PROTOCOLO.md): ele informa ao app
quais sensores, controles e alertas tem, e o app **monta a tela automaticamente**. Para lançar
uma dosadora, wavemaker ou ATO novo, basta o firmware usar a biblioteca
[`firmware-sdk/PrimeReefDevice`](../firmware-sdk/PrimeReefDevice). Não é preciso atualizar o app.

A luminária atual (firmware v9) usa a API própria dela e tem uma tela especializada.

## Funcionalidades

- **Descoberta automática** de todos os aparelhos na rede (mDNS `_primereef._tcp` + varredura da
  sub-rede), identificando o tipo de cada um, ou adição manual pelo IP.
- **Meu aquário**: resumo com aparelhos online e alertas ativos de todo o ecossistema
  (ex.: galão da dosadora acabando, reservatório do ATO vazio, aparelho offline).
- Aparelhos **agrupados por tipo** (Iluminação, Dosadoras, Bombas, Reposição, Controladores, Sensores).
- **Aparelhos do Protocolo Prime Reef**: leituras, alertas e controles (liga/desliga, sliders,
  números, listas de opções e botões de ação com confirmação) gerados a partir do aparelho.
- **Controle de todas as luminárias ao mesmo tempo** e botão para voltar todas ao programa.
- Por luminária:
  - **Controle** manual com pré-visualização em tempo real enquanto arrasta o slider.
  - **Programação**: horário de ligar/desligar, intensidade máxima por canal e gráfico da curva do dia
    (mesma rampa de 60 min do firmware). Opção de salvar o mesmo programa em todas.
  - **Grupo**: ver/remover luminárias pareadas, buscar novas e criar grupo, sair do grupo.
  - **Sistema**: atualização remota de firmware (da luminária ou do grupo inteiro) e atalho para o painel web.
- Guia para configurar um aparelho novo (modo ponto de acesso, `192.168.4.1`).

## Como obter o APK

Abra no celular e instale (permita "instalar apps de fontes desconhecidas"):

**https://github.com/marcosleandrin1206-coder/luminaria-prime-led/releases/download/app-latest/PrimeReef.apk**

Esse link sempre aponta para a versão mais recente: cada push que altera `android-app/` recompila o
app no GitHub Actions e substitui o arquivo.

## Compilando localmente

Requer Android Studio (ou JDK 17 + Android SDK 35):

```bash
cd android-app
./gradlew assembleDebug
# APK em app/build/outputs/apk/debug/app-debug.apk
```

## Testando sem hardware

```bash
python3 tools/simulador.py --tipo todos   # dosadora:8080, bomba:8081, ato:8082, sensor:8083
```

No app: *Adicionar → Adicionar pelo IP →* `<ip-do-computador>:8080`.

## API própria da luminária (firmware atual)

| Endpoint | Uso |
|---|---|
| `GET /status` | níveis atuais (`b`, `az`, `w`, `u`), hora `t`, `manual`, `ml` (min restantes), `cooler` |
| `GET /preview?ch=&v=` | pré-visualização enquanto arrasta |
| `GET /live?ch=&v=` | aplica o valor manual (canais `b`, `az`, `w`, `u`) |
| `GET /cancelmanual` | volta ao programa |
| `GET /schedule` | `h1`, `m1`, `h2`, `m2`, `royal`, `blue`, `w`, `u` |
| `GET /save?t1=HH:MM&t2=HH:MM&royal=&blue=&w=&u=` | salva a programação |
| `GET /peers`, `/discover`, `/getnumber`, `/removepeer?host=`, `/forgetgroup`; `POST /creategroup` | grupo |
| `GET /checkupdate`, `/checkupdate_group` | atualização remota via GitHub |
