// Prime Reef — Dosadora de 1 canal para a placa relé "eWeLink 1 canal 7-32V" com módulo PSF-B01 (ESP8285).
//
// Substitui o firmware eWeLink: a bomba passa a ser controlada pelo app Prime Reef, na rede local,
// sem nuvem. Placa no Arduino IDE: "Generic ESP8285 Module" (core ESP8266), Flash 1MB.
//
// Primeiro uso: a placa cria a rede Wi-Fi "PrimeReef-Dosadora-XXXX" (senha: primereef).
// Conecte o celular nela, abra http://192.168.4.1, escolha seu Wi-Fi e digite a senha.
// Depois, no app Prime Reef: Adicionar → Buscar todos os equipamentos na rede.
//
// Segurar o botão da placa por 5 s apaga o Wi-Fi salvo e volta para o modo de configuração.

#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <EEPROM.h>
#include <time.h>
#include <PrimeReefDevice.h>

#define FW_VERSION "1"
#define BOTAO_PIN 0         // botão da placa (GPIO0)
#define DOSE_MAX_SEG 120    // trava de segurança: a bomba nunca fica ligada mais que isso por comando
#define MANUAL_MAX_SEG 60   // "Bomba ligada (manual)" desliga sozinha depois disso
#define CAL_SEG 30          // tempo do teste de calibração

// Pinos possíveis do relé nas variações dessa placa (confira pelo app em "Pino do relé").
const uint8_t PINOS[] = {13, 12, 4, 5, 14};
const char* PINOS_NOMES[] = {"GPIO13", "GPIO12", "GPIO4", "GPIO5", "GPIO14"};
const int N_PINOS = 5;

struct Config {
  uint32_t magic;
  char ssid[33];
  char pass[65];
  uint8_t relePino;
  bool automatico;
  float mlDia;
  uint8_t dosesDia;
  uint8_t horaInicio;
  float vazao;         // ml por segundo
  float capacidade;    // ml do galão cheio
  float reservatorio;  // ml restantes
};
const uint32_t MAGIC = 0x50524431;  // "PRD1"
Config cfg;

ESP8266WebServer server(80);
PrimeReefDevice reef("dosadora", "Dosadora Prime Reef 1 canal", FW_VERSION);

bool modoAP = false;
bool bombaLigada = false;
uint32_t desligarEm = 0;
float dosadoHoje = 0;
int ultimoDia = -1;
long ultimoSlot = -1;
uint32_t reiniciarEm = 0;
uint32_t botaoDesde = 0;

// ---------------- Configuração salva ----------------

void salvar() {
  EEPROM.put(0, cfg);
  EEPROM.commit();
}

void carregar() {
  EEPROM.begin(sizeof(Config) + 16);
  EEPROM.get(0, cfg);
  if (cfg.magic != MAGIC) {
    memset(&cfg, 0, sizeof(cfg));
    cfg.magic = MAGIC;
    cfg.relePino = 13;
    cfg.automatico = false;  // só liga depois de calibrar
    cfg.mlDia = 10;
    cfg.dosesDia = 6;
    cfg.horaInicio = 8;
    cfg.vazao = 1.0;
    cfg.capacidade = 1000;
    cfg.reservatorio = 1000;
    salvar();
  }
}

// ---------------- Bomba ----------------

void rele(bool on) { digitalWrite(cfg.relePino, on ? HIGH : LOW); }

void ligarBomba(float segundos, float ml) {
  if (segundos <= 0) return;
  if (segundos > DOSE_MAX_SEG) segundos = DOSE_MAX_SEG;
  bombaLigada = true;
  desligarEm = millis() + (uint32_t)(segundos * 1000);
  rele(true);
  if (ml > 0) {
    dosadoHoje += ml;
    cfg.reservatorio = max(0.0f, cfg.reservatorio - ml);
    salvar();
  }
}

void desligarBomba() {
  rele(false);
  bombaLigada = false;
  desligarEm = 0;
}

void dosar(float ml) {
  if (cfg.vazao <= 0 || ml <= 0) return;
  float seg = ml / cfg.vazao;
  if (seg > DOSE_MAX_SEG) {
    seg = DOSE_MAX_SEG;
    ml = seg * cfg.vazao;
  }
  ligarBomba(seg, ml);
}

// ---------------- Agenda ----------------

bool horaOk(struct tm& t) {
  time_t agora = time(nullptr);
  if (agora < 1700000000) return false;  // ainda sem horário da internet
  localtime_r(&agora, &t);
  return true;
}

int intervaloMin() { return 1440 / max(1, (int)cfg.dosesDia); }

float mlPorDose() { return cfg.mlDia / max(1, (int)cfg.dosesDia); }

String proximaDose() {
  if (!cfg.automatico) return "Automático desligado";
  struct tm t;
  if (!horaOk(t)) return "Aguardando horário";
  int agora = t.tm_hour * 60 + t.tm_min;
  int inicio = cfg.horaInicio * 60;
  int iv = intervaloMin();
  int desde = (agora - inicio + 1440) % 1440;
  int falta = iv - (desde % iv);
  if (falta == iv) falta = 0;
  int m = (agora + (falta == 0 ? iv : falta)) % 1440;
  char buf[32];
  snprintf(buf, sizeof(buf), "%02d:%02d · %.1f ml", m / 60, m % 60, mlPorDose());
  return String(buf);
}

void verificarAgenda() {
  struct tm t;
  if (!horaOk(t)) return;
  if (t.tm_yday != ultimoDia) {
    ultimoDia = t.tm_yday;
    dosadoHoje = 0;
  }
  if (!cfg.automatico) return;
  int agora = t.tm_hour * 60 + t.tm_min;
  int desde = (agora - cfg.horaInicio * 60 + 1440) % 1440;
  long slot = (long)t.tm_yday * 1440 + agora;
  if (desde % intervaloMin() == 0 && slot != ultimoSlot) {
    ultimoSlot = slot;
    dosar(mlPorDose());
  }
}

void atualizarAlertas() {
  float pct = cfg.capacidade > 0 ? cfg.reservatorio / cfg.capacidade * 100 : 0;
  if (pct < 10) reef.setAlert("galao", PrimeReefDevice::Level::Alarm, "Galão quase vazio");
  else if (pct < 20) reef.setAlert("galao", PrimeReefDevice::Level::Warn, "Galão abaixo de 20%");
  else reef.clearAlert("galao");

  struct tm t;
  if (cfg.automatico && !horaOk(t))
    reef.setAlert("hora", PrimeReefDevice::Level::Warn, "Sem horário da internet: dosagem automática pausada");
  else reef.clearAlert("hora");
}

// ---------------- Wi-Fi ----------------

String nomeAP() {
  uint8_t mac[6];
  WiFi.macAddress(mac);
  char buf[32];
  snprintf(buf, sizeof(buf), "PrimeReef-Dosadora-%02X%02X", mac[4], mac[5]);
  return String(buf);
}

const char* ESTILO =
    "<meta name=viewport content='width=device-width,initial-scale=1'><meta charset=utf-8>"
    "<style>body{background:#0d1117;color:#c9d1d9;font-family:sans-serif;max-width:420px;margin:auto;padding:20px}"
    "h2{color:#58a6ff}select,input,button{width:100%;padding:12px;margin:6px 0;border-radius:6px;border:1px solid #30363d;"
    "background:#161b22;color:#fff;box-sizing:border-box}button{background:#238636;border:0;font-weight:bold}</style>";

void paginaInicial() {
  String h = String(ESTILO) + "<h2>Prime Reef Dosadora</h2>";
  if (modoAP) {
    int n = WiFi.scanNetworks();
    h += "<p>Escolha o Wi-Fi da sua casa:</p><form method=POST action=/wifi><select name=s>";
    for (int i = 0; i < n; i++) {
      String s = WiFi.SSID(i);
      s.replace("'", "&#39;");
      h += "<option value='" + s + "'>" + s + " (" + String(WiFi.RSSI(i)) + " dBm)</option>";
    }
    h += "</select><input name=p type=password placeholder='Senha do Wi-Fi'><button>CONECTAR</button></form>";
  } else {
    h += "<p>Conectada em <b>" + String(cfg.ssid) + "</b> · " + WiFi.localIP().toString() + "</p>"
         "<p>Use o app <b>Prime Reef Brasil</b> para controlar esta dosadora.</p>";
  }
  server.send(200, "text/html", h);
}

void salvarWifi() {
  String s = server.arg("s"), p = server.arg("p");
  if (s.length() == 0 || s.length() > 32 || p.length() > 64) {
    server.send(400, "text/html", String(ESTILO) + "<p>Dados inválidos. <a href=/>Voltar</a></p>");
    return;
  }
  strncpy(cfg.ssid, s.c_str(), sizeof(cfg.ssid) - 1);
  cfg.ssid[sizeof(cfg.ssid) - 1] = 0;
  strncpy(cfg.pass, p.c_str(), sizeof(cfg.pass) - 1);
  cfg.pass[sizeof(cfg.pass) - 1] = 0;
  salvar();
  server.send(200, "text/html", String(ESTILO) +
              "<h2>Salvo!</h2><p>A dosadora vai reiniciar e entrar na rede <b>" + s +
              "</b>. Volte o celular para o seu Wi-Fi e procure a dosadora no app.</p>");
  reiniciarEm = millis() + 1500;
}

void conectarWifi() {
  WiFi.persistent(false);
  WiFi.hostname(reef.deviceId());
  if (strlen(cfg.ssid) > 0) {
    WiFi.mode(WIFI_STA);
    WiFi.begin(cfg.ssid, cfg.pass);
    uint32_t inicio = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - inicio < 20000) delay(250);
    if (WiFi.status() == WL_CONNECTED) return;
  }
  // Sem Wi-Fi: abre a rede de configuração e continua tentando o Wi-Fi salvo em segundo plano.
  modoAP = true;
  WiFi.mode(strlen(cfg.ssid) > 0 ? WIFI_AP_STA : WIFI_AP);
  WiFi.softAP(nomeAP().c_str(), "primereef");
}

// ---------------- Setup / loop ----------------

void setup() {
  Serial.begin(115200);
  carregar();
  pinMode(BOTAO_PIN, INPUT_PULLUP);
  pinMode(cfg.relePino, OUTPUT);
  desligarBomba();

  conectarWifi();
  configTime("<-03>3", "pool.ntp.org", "time.google.com");  // horário de Brasília

  // ---- Leituras ----
  reef.addSensor("hoje", "Dosado hoje", "ml", [] { return dosadoHoje; }, 1);
  reef.addSensor("galao", "Galão", "%",
                 []() -> float { return cfg.capacidade > 0 ? cfg.reservatorio / cfg.capacidade * 100 : 0.0f; }, 0,
                 [](float pct) {
                   return pct < 10 ? PrimeReefDevice::Level::Alarm
                        : pct < 20 ? PrimeReefDevice::Level::Warn
                                   : PrimeReefDevice::Level::Ok;
                 });
  reef.addTextSensor("proxima", "Próxima dose", []() -> String { return proximaDose(); });
  reef.addSensor("rssi", "Sinal Wi-Fi", "dBm", [] { return (float)WiFi.RSSI(); }, 0);

  // ---- Dosagem ----
  reef.addSwitch("auto", "Dosagem automática", [] { return cfg.automatico; },
                 [](bool v) { cfg.automatico = v; salvar(); });
  reef.addSlider("ml", "Volume por dia", 0, 500, 1, "ml", [] { return cfg.mlDia; },
                 [](float v) { cfg.mlDia = v; salvar(); });
  reef.addSlider("doses", "Doses por dia", 1, 24, 1, "", [] { return (float)cfg.dosesDia; },
                 [](float v) { cfg.dosesDia = (uint8_t)v; salvar(); });
  reef.addSlider("inicio", "Horário da 1ª dose", 0, 23, 1, "h", [] { return (float)cfg.horaInicio; },
                 [](float v) { cfg.horaInicio = (uint8_t)v; salvar(); });
  reef.addButton("d1", "Dosar 1 ml agora", [] { dosar(1); }, "Dosar 1 ml agora?");
  reef.addButton("d5", "Dosar 5 ml agora", [] { dosar(5); }, "Dosar 5 ml agora?");
  reef.addSwitch("bomba", "Bomba ligada (manual, máx. 60 s)", [] { return bombaLigada; },
                 [](bool v) { if (v) ligarBomba(MANUAL_MAX_SEG, 0); else desligarBomba(); });

  // ---- Calibração e galão ----
  reef.addButton("cal", "Calibrar: ligar a bomba por 30 s",
                 [] { ligarBomba(CAL_SEG, 0); },
                 "Coloque a mangueira num copo medidor. A bomba vai ligar por 30 segundos. "
                 "Depois divida os ml medidos por 30 e informe em \"Vazão calibrada\".");
  reef.addNumber("vazao", "Vazão calibrada", 0.01, 20, 0.01, "ml/s", [] { return cfg.vazao; },
                 [](float v) { cfg.vazao = v; salvar(); });
  reef.addNumber("cap", "Capacidade do galão", 100, 20000, 50, "ml", [] { return cfg.capacidade; },
                 [](float v) { cfg.capacidade = v; if (cfg.reservatorio > v) cfg.reservatorio = v; salvar(); });
  reef.addButton("encher", "Galão reabastecido",
                 [] { cfg.reservatorio = cfg.capacidade; salvar(); }, "Confirmar que o galão foi reabastecido?");

  // ---- Instalação ----
  std::vector<String> opcoes;
  for (int i = 0; i < N_PINOS; i++) opcoes.push_back(PINOS_NOMES[i]);
  reef.addSelect("pino", "Pino do relé (instalação)", opcoes,
                 []() -> String {
                   for (int i = 0; i < N_PINOS; i++)
                     if (PINOS[i] == cfg.relePino) return String(PINOS_NOMES[i]);
                   return String("GPIO") + cfg.relePino;
                 },
                 [](const String& v) {
                   for (int i = 0; i < N_PINOS; i++) {
                     if (v == PINOS_NOMES[i]) {
                       desligarBomba();
                       pinMode(cfg.relePino, INPUT);
                       cfg.relePino = PINOS[i];
                       pinMode(cfg.relePino, OUTPUT);
                       rele(false);
                       salvar();
                     }
                   }
                 });
  reef.addButton("esquecer", "Esquecer Wi-Fi",
                 [] { cfg.ssid[0] = 0; cfg.pass[0] = 0; salvar(); reiniciarEm = millis() + 1500; },
                 "A dosadora vai sair do Wi-Fi e criar a rede de configuração PrimeReef-Dosadora. Continuar?");

  reef.begin(server);
  server.on("/", paginaInicial);
  server.on("/wifi", HTTP_POST, salvarWifi);
  server.begin();
  atualizarAlertas();
}

void loop() {
  server.handleClient();
  reef.loop();

  if (bombaLigada && (int32_t)(millis() - desligarEm) >= 0) desligarBomba();

  static uint32_t ultimoSeg = 0;
  if (millis() - ultimoSeg >= 1000) {
    ultimoSeg = millis();
    verificarAgenda();
    atualizarAlertas();
  }

  // Modo configuração: assim que o Wi-Fi salvo conectar, reinicia em modo normal.
  if (modoAP && strlen(cfg.ssid) > 0 && WiFi.status() == WL_CONNECTED && reiniciarEm == 0) {
    reiniciarEm = millis() + 3000;
  }

  // Botão segurado por 5 s: esquece o Wi-Fi.
  if (digitalRead(BOTAO_PIN) == LOW) {
    if (botaoDesde == 0) botaoDesde = millis();
    if (millis() - botaoDesde > 5000 && reiniciarEm == 0) {
      desligarBomba();
      cfg.ssid[0] = 0;
      cfg.pass[0] = 0;
      salvar();
      reiniciarEm = millis() + 500;
    }
  } else {
    botaoDesde = 0;
  }

  if (reiniciarEm && (int32_t)(millis() - reiniciarEm) >= 0) {
    desligarBomba();
    ESP.restart();
  }
}
