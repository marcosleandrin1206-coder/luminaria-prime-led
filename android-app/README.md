# App Android — Prime Reef Brasil

Aplicativo Android para controlar **todas as luminárias Prime Reef Brasil** da casa em um só lugar.
Ele conversa direto com o servidor HTTP do firmware de cada luminária pela rede Wi-Fi local
(não precisa de nuvem nem de conta).

## Funcionalidades

- **Descoberta automática** das luminárias na rede (mDNS `_primereef._tcp` + varredura da sub-rede),
  ou adição manual pelo IP.
- **Painel geral** com o status ao vivo de cada luminária: relógio, níveis dos 4 canais
  (Azul Royal, Azul, Branco, UV), modo manual/programa e cooler.
- **Controle de todas ao mesmo tempo**: sliders que ajustam todas as luminárias online juntas e
  botão para voltar todas ao programa.
- Por luminária:
  - **Controle** manual com pré-visualização em tempo real enquanto arrasta o slider.
  - **Programação**: horário de ligar/desligar, intensidade máxima por canal e gráfico da curva do dia
    (mesma rampa de 60 min do firmware). Opção de salvar o mesmo programa em todas.
  - **Grupo**: ver/remover luminárias pareadas, buscar novas e criar grupo, sair do grupo.
  - **Sistema**: atualização remota de firmware (da luminária ou do grupo inteiro) e atalho para o painel web.
- Guia para configurar uma luminária nova (modo ponto de acesso, `192.168.4.1`).

## Como obter o APK

Cada push que altera `android-app/` dispara o workflow **App Android** no GitHub Actions.
Abra a execução mais recente em *Actions → App Android* e baixe o artefato `prime-reef-apk`.
Instale o `app-release.apk` no celular (é preciso permitir "instalar apps de fontes desconhecidas").

## Compilando localmente

Requer Android Studio (ou JDK 17 + Android SDK 35):

```bash
cd android-app
./gradlew assembleDebug
# APK em app/build/outputs/apk/debug/app-debug.apk
```

## API do firmware usada

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
