// Prime Reef — Dosadora de 1 canal
// Placa: LC Technology "ESP12F_Relay_X1" (ESP8266 ESP-12F, 1 relé, alimentação DC 5 V ou 8-80 V).
//
// A bomba é controlada pelo app Prime Reef e pela página da própria dosadora (abra o IP dela no
// navegador), na rede local, sem nuvem.
//
// Arduino IDE: placa "Generic ESP8266 Module", Flash Size "4MB (FS:2MB OTA:~1019KB)".
// Gravação: adaptador USB-serial em GND/TX/RX do conector de programação (cruzado: TX→RX, RX→TX),
// jumper no IO0↔GND, placa alimentada pelo borne de 12 V. Depois de gravar, tire o jumper.
// Primeira gravação: "Erase Flash: All Flash Contents". Atualizações: "Only Sketch".
//
// Primeiro uso: a placa cria a rede Wi-Fi "PrimeReef-Dosadora-XXXX" (senha: primereef).
// Conecte o celular nela; a página de configuração abre sozinha (ou acesse http://192.168.4.1).
//
// Esquecer o Wi-Fi: com a placa ligada, feche o jumper do IO0 por 5 s (ou use a página/app).
// LED vermelho: aceso = conectado · pisca devagar = modo configuração · pisca rápido = procurando Wi-Fi.
//
// Atualização remota: a cada 6 h a dosadora lê dosadora/version.txt no GitHub (OTA_REPO); se o número for
// maior que FW_NUM, baixa dosadora/bin/firmware-<versão>.bin e instala quando a bomba está livre.
// Também dá para enviar um .bin pela página (aba Sistema), com a senha OTA_SENHA.
//
// Para publicar uma versão nova: aumente FW_VERSION e FW_NUM e envie para a branch main. O GitHub Actions
// compila, grava dosadora/bin/firmware-<FW_NUM>.bin e atualiza dosadora/version.txt sozinho.

#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <ESP8266HTTPClient.h>
#include <ESP8266httpUpdate.h>
#include <WiFiClientSecureBearSSL.h>
#include <Updater.h>
#include <DNSServer.h>
#include <EEPROM.h>
#include <time.h>
#include <PrimeReefDevice.h>

#define FW_VERSION "4"
#define FW_NUM 4             // número comparado com o version.txt do GitHub (aumente a cada versão publicada)
#define BOTAO_PIN 0          // jumper IO0 da placa (fechar por 5 s com a placa ligada = esquecer Wi-Fi)
#define LED_PIN 16           // LED vermelho da placa (acende em LOW)
#define DOSE_MAX_SEG 120     // trava de segurança: a bomba nunca fica ligada mais que isso por comando
#define MANUAL_MAX_SEG 60    // escorva manual desliga sozinha depois disso
#define CAL_SEG 30           // tempo do teste de calibração
#define GRAVAR_ATRASO_MS 3000UL                   // espera 3 s sem mudanças antes de gravar a configuração
#define GRAVAR_GALAO_MS (6UL * 3600UL * 1000UL)   // nível do galão vai para a flash no máximo a cada 6 h
#define AP_TENTATIVA_MS 120000UL                  // no modo configuração, tenta o Wi-Fi salvo a cada 2 min

// ---- Atualização ----
#define OTA_REPO "marcosleandrin1206-coder/luminaria-prime-led"   // repositório público no GitHub
#define OTA_BASE "https://raw.githubusercontent.com/" OTA_REPO "/main/dosadora/"
#define OTA_URL_VER OTA_BASE "version.txt"
// O binário tem o número da versão no nome (bin/firmware-4.bin): assim o cache do GitHub nunca entrega
// um version.txt novo junto com um firmware.bin antigo.
#define OTA_SENHA "primereef"                     // TROQUE: senha para enviar .bin pela página
#define OTA_CHECAGEM_MS (6UL * 3600UL * 1000UL)   // verifica o GitHub a cada 6 h
#define OTA_PRIMEIRA_MS 120000UL                  // primeira verificação 2 min depois de ligar

// Na ESP12F_Relay_X1 o relé vem ligado no GPIO5 (pelo resistor R14). Os outros só servem se a placa
// for modificada (R14 removido e o pino "Relay" ligado em outro GPIO).
const uint8_t PINOS[] = {5, 4, 12, 13, 14};
const char* PINOS_NOMES[] = {"GPIO5", "GPIO4", "GPIO12", "GPIO13", "GPIO14"};
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
  bool autoUpdate;     // instala atualizações do GitHub sozinha
};
const uint32_t MAGIC = 0x50524432;  // "PRD2"
Config cfg;
Config cfgGravada;  // cópia do que está na flash (para só gravar quando algo mudou)

// Estado do dia guardado na memória RTC: sobrevive a reinícios (queda de tensão, watchdog), não a falta de energia.
struct RtcDados {
  uint32_t magic;
  int32_t dia;
  int32_t ultimoSlot;
  float hoje;
  uint32_t mask;
  float reservatorio;
  uint32_t bomba;     // 1 = bomba estava ligada (se a placa reiniciar assim, foi queda de tensão/travamento)
  int32_t otaTentada; // versão que estava sendo instalada antes do reinício
  int32_t otaFalhou;  // versão que não se instalou (não tenta de novo sozinha)
  uint32_t soma;
};
const uint32_t RTC_MAGIC = 0x50525256;
#define RTC_BLOCO 64  // longe dos blocos usados por atualização OTA

ESP8266WebServer server(80);
DNSServer dns;
PrimeReefDevice reef("dosadora", "Dosadora Prime Reef 1 canal", FW_VERSION);

#define T_NADA 0
#define T_DOSE 1
#define T_MANUAL 2
#define T_CAL 3

bool modoAP = false;
bool bombaLigada = false;
uint8_t tipoAtual = T_NADA;
uint32_t inicioMs = 0;
uint32_t duracaoMs = 0;
float mlAlvo = 0;
float pendenteMl = 0;
uint32_t pendenteBit = 0;   // dose da agenda que está na fila (para desmarcar se for cancelada)
bool reinicioComBomba = false;
int versaoRemota = 0;       // versão no GitHub (0 = ainda não verificou)
int otaTentada = 0, otaFalhou = 0;
String otaMsg = "Ainda não verificou";
bool otaChecarAgora = false, otaInstalarAgora = false;
uint32_t otaProxima = 0;
uint8_t upEstado = 0;       // upload de .bin: 0 ok, 1 senha errada, 2 bomba ocupada, 3 erro no arquivo
float dosadoHoje = 0;
uint32_t dosesMask = 0;     // bit k = dose k do dia já feita
int ultimoDia = -1;
long ultimoSlot = -1;
bool horaIniciada = false;
uint32_t reiniciarEm = 0;
uint32_t botaoDesde = 0;
bool cfgPendente = false;
uint32_t cfgPendenteEm = 0;
uint32_t ultimaGravacao = 0;
bool tentandoSTA = false;
uint32_t tentativaInicio = 0;
uint32_t proximaTentativa = 0;

extern const char PAGINA[] PROGMEM;
extern const char AP_CSS[] PROGMEM;

// ---------------- Utilidades ----------------

float limitar(float v, float a, float b, float padrao) {
  if (isnan(v) || isinf(v)) return padrao;
  return v < a ? a : (v > b ? b : v);
}

String jsonEsc(const String& s) {
  String o;
  o.reserve(s.length() + 4);
  for (unsigned i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '"' || c == '\\') { o += '\\'; o += c; }
    else if ((uint8_t)c < 0x20) o += ' ';
    else o += c;
  }
  return o;
}

String htmlEsc(const String& s) {
  String o;
  o.reserve(s.length() + 8);
  for (unsigned i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '&') o += F("&amp;");
    else if (c == '<') o += F("&lt;");
    else if (c == '>') o += F("&gt;");
    else if (c == '"') o += F("&quot;");
    else if (c == '\'') o += F("&#39;");
    else o += c;
  }
  return o;
}

// ---------------- Memória RTC ----------------

uint32_t rtcSoma(const RtcDados& r) {
  const uint32_t* p = (const uint32_t*)&r;
  uint32_t s = 0x811C9DC5;
  for (size_t i = 0; i < sizeof(RtcDados) / 4 - 1; i++) s = (s ^ p[i]) * 16777619UL;
  return s;
}

void rtcSalvar() {
  RtcDados r;
  r.magic = RTC_MAGIC;
  r.dia = ultimoDia;
  r.ultimoSlot = ultimoSlot;
  r.hoje = dosadoHoje;
  r.mask = dosesMask;
  r.reservatorio = cfg.reservatorio;
  r.bomba = bombaLigada ? 1 : 0;
  r.otaTentada = otaTentada;
  r.otaFalhou = otaFalhou;
  r.soma = rtcSoma(r);
  ESP.rtcUserMemoryWrite(RTC_BLOCO, (uint32_t*)&r, sizeof(r));
}

void rtcCarregar() {
  RtcDados r;
  if (!ESP.rtcUserMemoryRead(RTC_BLOCO, (uint32_t*)&r, sizeof(r))) return;
  if (r.magic != RTC_MAGIC || r.soma != rtcSoma(r)) return;
  ultimoDia = r.dia;
  ultimoSlot = r.ultimoSlot;
  if (r.hoje >= 0 && r.hoje < 100000) dosadoHoje = r.hoje;
  dosesMask = r.mask;
  if (r.reservatorio >= 0 && r.reservatorio <= cfg.capacidade) cfg.reservatorio = r.reservatorio;
  if (r.bomba) reinicioComBomba = true;
  otaTentada = r.otaTentada;
  otaFalhou = r.otaFalhou;
}

// ---------------- Configuração salva ----------------

void gravarAgora() {
  cfgPendente = false;
  if (memcmp(&cfg, &cfgGravada, sizeof(Config)) == 0) return;
  EEPROM.put(0, cfg);
  EEPROM.commit();
  cfgGravada = cfg;
  ultimaGravacao = millis();
}

// Marca para gravar daqui a alguns segundos (vários toques seguidos viram uma gravação só).
void salvar() {
  cfgPendente = true;
  cfgPendenteEm = millis();
  rtcSalvar();
}

void tarefasGravacao() {
  if (cfgPendente && millis() - cfgPendenteEm >= GRAVAR_ATRASO_MS) gravarAgora();
  // Nível do galão muda a cada dose; vai para a flash no máximo a cada 6 h (a memória RTC cobre reinícios).
  if (cfg.reservatorio != cfgGravada.reservatorio && millis() - ultimaGravacao >= GRAVAR_GALAO_MS) gravarAgora();
}

void carregar() {
  EEPROM.begin(sizeof(Config) + 16);
  EEPROM.get(0, cfg);
  if (cfg.magic != MAGIC) {
    memset(&cfg, 0, sizeof(cfg));
    cfg.magic = MAGIC;
    cfg.relePino = 5;
    cfg.autoUpdate = true;
    cfg.automatico = false;  // só liga depois de calibrar
    cfg.mlDia = 10;
    cfg.dosesDia = 6;
    cfg.horaInicio = 8;
    cfg.vazao = 1.0;
    cfg.capacidade = 1000;
    cfg.reservatorio = 1000;
    EEPROM.put(0, cfg);
    EEPROM.commit();
  }
  cfgGravada = cfg;
  // Conferência de sanidade (flash corrompida não pode virar dose absurda).
  cfg.ssid[sizeof(cfg.ssid) - 1] = 0;
  cfg.pass[sizeof(cfg.pass) - 1] = 0;
  bool pinoOk = false;
  for (int i = 0; i < N_PINOS; i++) if (PINOS[i] == cfg.relePino) pinoOk = true;
  if (!pinoOk) cfg.relePino = 5;
  uint8_t a;
  memcpy(&a, &cfg.automatico, 1);  // flash apagada vem 0xFF: só "1" conta como ligado
  cfg.automatico = (a == 1);
  memcpy(&a, &cfg.autoUpdate, 1);
  cfg.autoUpdate = (a != 0);
  cfg.mlDia = limitar(cfg.mlDia, 0, 500, 10);
  if (cfg.dosesDia < 1 || cfg.dosesDia > 24) cfg.dosesDia = 6;
  if (cfg.horaInicio > 23) cfg.horaInicio = 8;
  cfg.vazao = limitar(cfg.vazao, 0.01, 20, 1.0);
  cfg.capacidade = limitar(cfg.capacidade, 100, 20000, 1000);
  cfg.reservatorio = limitar(cfg.reservatorio, 0, cfg.capacidade, cfg.capacidade);
}

// ---------------- Bomba ----------------

void rele(bool on) { digitalWrite(cfg.relePino, on ? HIGH : LOW); }

bool iniciarBomba(uint8_t tipo, float segundos, float ml) {
  if (bombaLigada || segundos <= 0) return false;
  if (segundos > DOSE_MAX_SEG) segundos = DOSE_MAX_SEG;
  tipoAtual = tipo;
  inicioMs = millis();
  duracaoMs = (uint32_t)(segundos * 1000);
  mlAlvo = ml;
  bombaLigada = true;
  rele(true);
  rtcSalvar();
  return true;
}

// completo = terminou o tempo programado; false = interrompida (botão, app, troca de pino, reinício).
void pararBomba(bool completo) {
  rele(false);
  if (!bombaLigada) return;
  bombaLigada = false;
  uint32_t decorrido = millis() - inicioMs;
  if (completo || decorrido > duracaoMs) decorrido = duracaoMs;
  float ml = (completo && tipoAtual == T_DOSE) ? mlAlvo : decorrido / 1000.0f * cfg.vazao;
  float antes = cfg.reservatorio;
  cfg.reservatorio = cfg.reservatorio > ml ? cfg.reservatorio - ml : 0;
  if (tipoAtual == T_DOSE) dosadoHoje += ml;
  if (completo && tipoAtual == T_DOSE) reinicioComBomba = false;
  tipoAtual = T_NADA;
  rtcSalvar();
  // Cruzou 20% ou 10%: grava já, para o alerta do galão sobreviver a uma falta de energia.
  float c20 = cfg.capacidade * 0.2f, c10 = cfg.capacidade * 0.1f;
  if ((antes >= c20 && cfg.reservatorio < c20) || (antes >= c10 && cfg.reservatorio < c10)) gravarAgora();
}

bool dosar(float ml) {
  if (cfg.vazao <= 0 || ml <= 0) return false;
  float seg = ml / cfg.vazao;
  if (seg > DOSE_MAX_SEG) {
    seg = DOSE_MAX_SEG;
    ml = seg * cfg.vazao;
  }
  return iniciarBomba(T_DOSE, seg, ml);
}

// ---------------- Ajustes (usados pelo app e pela página) ----------------

void setAuto(bool v) { cfg.automatico = v; salvar(); }
void setMlDia(float v) { cfg.mlDia = limitar(v, 0, 500, cfg.mlDia); salvar(); }
void setDoses(float v) {
  int d = (int)lroundf(limitar(v, 1, 24, cfg.dosesDia));
  if (d != cfg.dosesDia) { cfg.dosesDia = d; dosesMask = 0; }
  salvar();
}
void setInicio(float v) {
  int h = (int)lroundf(limitar(v, 0, 23, cfg.horaInicio));
  if (h != cfg.horaInicio) { cfg.horaInicio = h; dosesMask = 0; }
  salvar();
}
void setVazao(float v) { cfg.vazao = limitar(v, 0.01, 20, cfg.vazao); salvar(); }
void setCapacidade(float v) {
  cfg.capacidade = limitar(v, 100, 20000, cfg.capacidade);
  if (cfg.reservatorio > cfg.capacidade) cfg.reservatorio = cfg.capacidade;
  salvar();
}
void encherGalao() { cfg.reservatorio = cfg.capacidade; salvar(); }

void cancelarFila() {
  dosesMask &= ~pendenteBit;
  pendenteBit = 0;
  pendenteMl = 0;
}

void setPino(int idx) {
  if (idx < 0 || idx >= N_PINOS || PINOS[idx] == cfg.relePino) return;
  pararBomba(false);
  cancelarFila();
  digitalWrite(cfg.relePino, LOW);  // pino antigo continua como saída em LOW: nunca fica flutuando
  cfg.relePino = PINOS[idx];
  pinMode(cfg.relePino, OUTPUT);
  rele(false);
  salvar();
}

void esquecerWifi() {
  cfg.ssid[0] = 0;
  cfg.pass[0] = 0;
  salvar();
  if (!reiniciarEm) reiniciarEm = millis() + 1500;
}

// ---------------- Agenda ----------------

bool horaOk(struct tm& t) {
  time_t agora = time(nullptr);
  if (agora < 1700000000) return false;  // ainda sem horário da internet
  localtime_r(&agora, &t);
  return true;
}

int intervaloMin() { return 1440 / (cfg.dosesDia < 1 ? 1 : cfg.dosesDia); }
float mlPorDose() { return cfg.mlDia / (cfg.dosesDia < 1 ? 1 : cfg.dosesDia); }
float mlPorDoseReal() {
  float lim = cfg.vazao * DOSE_MAX_SEG;
  float m = mlPorDose();
  return m > lim ? lim : m;
}
bool doseLimitada() { return mlPorDose() > cfg.vazao * DOSE_MAX_SEG + 0.001f; }
int minutoDaDose(int k) { return (cfg.horaInicio * 60 + k * intervaloMin()) % 1440; }

// Minuto do dia da próxima dose (estritamente depois de "agora").
int proximaDoseMin(int agora) {
  int melhor = -1, menor = 100000;
  for (int k = 0; k < cfg.dosesDia; k++) {
    int m = minutoDaDose(k);
    int dif = (m - agora + 1440) % 1440;
    if (dif == 0) dif = 1440;
    if (dif < menor) { menor = dif; melhor = m; }
  }
  return melhor;
}

String proximaDose() {
  if (!cfg.automatico) return "Automático desligado";
  if (cfg.mlDia <= 0) return "Volume zerado";
  struct tm t;
  if (!horaOk(t)) return "Aguardando horário";
  int m = proximaDoseMin(t.tm_hour * 60 + t.tm_min);
  char buf[32];
  snprintf(buf, sizeof(buf), "%02d:%02d · %.1f ml", m / 60, m % 60, mlPorDoseReal());
  return String(buf);
}

void verificarAgenda() {
  struct tm t;
  if (!horaOk(t)) return;
  int agora = t.tm_hour * 60 + t.tm_min;
  long slot = (long)t.tm_yday * 1440 + agora;

  if (t.tm_yday != ultimoDia) {
    ultimoDia = t.tm_yday;
    dosadoHoje = 0;
    dosesMask = 0;
    rtcSalvar();
  }
  // Primeira vez com horário depois de ligar: o minuto atual conta como já processado.
  // Assim um reinício no meio de uma dose (ex.: queda de tensão da bomba) nunca repete a dose.
  if (!horaIniciada) {
    horaIniciada = true;
    ultimoSlot = slot;
    return;
  }
  if (!cfg.automatico || cfg.mlDia <= 0 || slot == ultimoSlot) return;

  int iv = intervaloMin();
  int desde = (agora - cfg.horaInicio * 60 + 1440) % 1440;
  if (desde % iv != 0) return;
  int k = desde / iv;
  if (k >= cfg.dosesDia) return;  // evita a dose extra quando 1440 não divide certinho

  ultimoSlot = slot;
  float ml = mlPorDose();
  if (!bombaLigada) {
    dosar(ml);
    dosesMask |= (1UL << k);
  } else if (tipoAtual != T_CAL) {  // na calibração a mangueira está no copo: essa dose é pulada
    pendenteMl += ml;               // ocupada: dosa logo que liberar
    pendenteBit |= (1UL << k);
    dosesMask |= (1UL << k);
  }
  rtcSalvar();
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

  if (reinicioComBomba)
    reef.setAlert("reinicio", PrimeReefDevice::Level::Warn, "A placa reiniciou com a bomba ligada: verifique a fonte");
  else reef.clearAlert("reinicio");

  if (cfg.automatico && doseLimitada())
    reef.setAlert("limite", PrimeReefDevice::Level::Warn, "Dose limitada a 120 s de bomba: aumente o número de doses");
  else reef.clearAlert("limite");
}

// ---------------- Atualização (GitHub e arquivo) ----------------

// Não atualiza com a bomba ligada, com dose na fila ou a menos de 3 min de uma dose agendada.
bool doseEmBreve() {
  struct tm t;
  if (!cfg.automatico || cfg.mlDia <= 0 || !horaOk(t)) return false;
  int agora = t.tm_hour * 60 + t.tm_min;
  for (int k = 0; k < cfg.dosesDia; k++)
    if ((minutoDaDose(k) - agora + 1440) % 1440 <= 3) return true;
  return false;
}
bool bombaLivre() { return !bombaLigada && pendenteMl <= 0 && !doseEmBreve(); }

int buscarVersao() {
  BearSSL::WiFiClientSecure cli;
  cli.setInsecure();
  HTTPClient http;
  http.setTimeout(8000);
  if (!http.begin(cli, OTA_URL_VER)) return -1;
  http.setUserAgent("PrimeReef-Dosadora");
  int v = -1;
  if (http.GET() == 200) {
    String t = http.getString();
    t.trim();
    v = t.toInt();
  }
  http.end();
  return v > 0 ? v : -1;
}

void instalarRemoto() {
  otaMsg = "Baixando a versão " + String(versaoRemota) + "…";
  otaTentada = versaoRemota;
  rtcSalvar();
  gravarAgora();
  BearSSL::WiFiClientSecure cli;
  cli.setInsecure();
  ESPhttpUpdate.rebootOnUpdate(false);
  ESPhttpUpdate.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);
  String url = String(OTA_BASE) + "bin/firmware-" + String(versaoRemota) + ".bin";
  t_httpUpdate_return r = ESPhttpUpdate.update(cli, url);
  if (r == HTTP_UPDATE_OK) {
    rtcSalvar();
    ESP.restart();
    return;
  }
  otaTentada = 0;
  rtcSalvar();
  otaMsg = "Falha ao atualizar: " + ESPhttpUpdate.getLastErrorString();
}

void tarefasOTA() {
  if (modoAP || WiFi.status() != WL_CONNECTED) return;
  // A conexão com o GitHub trava o loop por alguns segundos: nunca com a bomba ligada ou dose próxima.
  if ((otaChecarAgora || (int32_t)(millis() - otaProxima) >= 0) && bombaLivre()) {
    bool manual = otaChecarAgora;
    otaChecarAgora = false;
    otaProxima = millis() + OTA_CHECAGEM_MS;
    int v = buscarVersao();
    if (v > 0) {
      versaoRemota = v;
      if (v > FW_NUM && v == otaFalhou) otaMsg = "A versão " + String(v) + " não se instalou antes; toque em Atualizar para tentar de novo";
      else if (v > FW_NUM) otaMsg = "Versão " + String(v) + " disponível";
      else otaMsg = "Firmware em dia";
      if (v > FW_NUM && cfg.autoUpdate && v != otaFalhou && !manual) otaInstalarAgora = true;
    } else {
      otaMsg = "Não foi possível verificar (sem internet?)";
    }
  }
  if (otaInstalarAgora) {
    if (versaoRemota <= FW_NUM) { otaInstalarAgora = false; return; }
    if (!bombaLivre()) { otaMsg = "Atualização aguardando a bomba ficar livre"; return; }
    if (ESP.getFreeHeap() < 20000) { otaMsg = "Memória insuficiente para atualizar agora"; return; }
    otaInstalarAgora = false;
    instalarRemoto();
  }
}

// Recebe um .bin enviado pela página (POST /d/upload?senha=...).
void uploadArquivo() {
  HTTPUpload& up = server.upload();
  if (up.status == UPLOAD_FILE_START) {
    upEstado = 0;
    if (server.arg("senha") != OTA_SENHA) { upEstado = 1; return; }
    if (!bombaLivre()) { upEstado = 2; return; }
    uint32_t livre = (ESP.getFreeSketchSpace() - 0x1000) & 0xFFFFF000;
    if (!Update.begin(livre)) upEstado = 3;
  } else if (up.status == UPLOAD_FILE_WRITE) {
    if (upEstado == 0 && Update.write(up.buf, up.currentSize) != up.currentSize) upEstado = 3;
  } else if (up.status == UPLOAD_FILE_END) {
    if (upEstado == 0 && !Update.end(true)) upEstado = 3;
  } else if (up.status == UPLOAD_FILE_ABORTED) {
    if (upEstado == 0) Update.end();
    upEstado = 3;
  }
  yield();
}

void uploadFim();  // definida depois de responder()

// LED vermelho: aceso = conectado; pisca devagar = modo configuração; rápido = procurando Wi-Fi.
void tarefasLed() {
  uint32_t m = millis();
  bool on = modoAP ? (m / 500) % 2 : (WiFi.status() != WL_CONNECTED ? (m / 125) % 2 : true);
  digitalWrite(LED_PIN, on ? LOW : HIGH);
}

// ---------------- API da página ----------------

int pinoIndice() {
  for (int i = 0; i < N_PINOS; i++) if (PINOS[i] == cfg.relePino) return i;
  return -1;
}

void apiStatus() {
  struct tm t;
  bool hv = horaOk(t);
  int agora = hv ? t.tm_hour * 60 + t.tm_min : -1;
  int prox = (hv && cfg.automatico) ? proximaDoseMin(agora) : -1;
  char hora[8] = "--:--";
  if (hv) snprintf(hora, sizeof(hora), "%02d:%02d", t.tm_hour, t.tm_min);
  float rest = 0, dur = 0;
  if (bombaLigada) {
    uint32_t dec = millis() - inicioMs;
    rest = dec >= duracaoMs ? 0 : (duracaoMs - dec) / 1000.0f;
    dur = duracaoMs / 1000.0f;
  }
  String ssid = jsonEsc(String(cfg.ssid));
  String host = jsonEsc(WiFi.hostname());
  String ip = WiFi.localIP().toString();
  String otam = jsonEsc(otaMsg);
  char b[1100];
  snprintf(b, sizeof(b),
           "{\"fw\":\"%s\",\"ip\":\"%s\",\"ssid\":\"%s\",\"host\":\"%s\",\"rssi\":%d,\"hv\":%d,\"hora\":\"%s\","
           "\"min\":%d,\"auto\":%d,\"ml\":%.1f,\"doses\":%d,\"ini\":%d,\"vaz\":%.3f,\"cap\":%.0f,\"res\":%.1f,"
           "\"hoje\":%.2f,\"mask\":%lu,\"prox\":%d,\"mpd\":%.2f,\"lim\":%d,\"bomba\":%d,\"tipo\":%d,\"rest\":%.1f,"
           "\"dur\":%.1f,\"alvo\":%.2f,\"pend\":%.2f,\"pino\":%d,\"max\":%d,\"rst\":%d,\"fwn\":%d,\"vr\":%d,\"aup\":%d,\"otam\":\"%s\"}",
           FW_VERSION, ip.c_str(), ssid.c_str(), host.c_str(), (int)WiFi.RSSI(), hv ? 1 : 0, hora,
           agora, cfg.automatico ? 1 : 0, cfg.mlDia, cfg.dosesDia, cfg.horaInicio, cfg.vazao, cfg.capacidade,
           cfg.reservatorio, dosadoHoje, (unsigned long)dosesMask, prox, mlPorDoseReal(), doseLimitada() ? 1 : 0,
           bombaLigada ? 1 : 0, tipoAtual, rest, dur, mlAlvo, pendenteMl, pinoIndice(), DOSE_MAX_SEG, reinicioComBomba ? 1 : 0,
           FW_NUM, versaoRemota, cfg.autoUpdate ? 1 : 0, otam.c_str());
  server.sendHeader("Cache-Control", "no-store");
  server.send(200, "application/json", b);
}

void apiSet() {
  if (server.hasArg("auto")) setAuto(server.arg("auto") == "1");
  if (server.hasArg("ml")) setMlDia(server.arg("ml").toFloat());
  if (server.hasArg("doses")) setDoses(server.arg("doses").toFloat());
  if (server.hasArg("ini")) setInicio(server.arg("ini").toFloat());
  if (server.hasArg("vaz")) setVazao(server.arg("vaz").toFloat());
  if (server.hasArg("cap")) setCapacidade(server.arg("cap").toFloat());
  if (server.hasArg("pino")) setPino(server.arg("pino").toInt());
  if (server.hasArg("aup")) { cfg.autoUpdate = server.arg("aup") == "1"; salvar(); }
  atualizarAlertas();
  apiStatus();
}

void responder(bool ok, const String& msg) {
  String j = "{\"ok\":";
  j += ok ? "1" : "0";
  j += ",\"msg\":\"";
  j += jsonEsc(msg);
  j += "\"}";
  server.send(ok ? 200 : 409, "application/json", j);
}

void apiAcao() {
  String a = server.arg("a");
  const char* ocupada = "A bomba já está ligada";
  if (a == "dose") {
    float ml = server.arg("ml").toFloat();
    if (!(ml > 0) || ml > 500) return responder(false, "Volume inválido");
    if (bombaLigada) return responder(false, ocupada);
    float lim = cfg.vazao * DOSE_MAX_SEG;
    dosar(ml);
    if (ml > lim) return responder(true, String("Dose limitada a ") + String(lim, 1) + " ml (120 s)");
    return responder(true, String("Dosando ") + String(ml, 1) + " ml");
  }
  if (a == "manual") {
    if (bombaLigada) return responder(false, ocupada);
    iniciarBomba(T_MANUAL, MANUAL_MAX_SEG, 0);
    return responder(true, "Bomba ligada (desliga em 60 s)");
  }
  if (a == "cal") {
    if (bombaLigada) return responder(false, ocupada);
    iniciarBomba(T_CAL, CAL_SEG, 0);
    return responder(true, "Calibrando: 30 segundos");
  }
  if (a == "parar") {
    cancelarFila();
    pararBomba(false);
    return responder(true, "Bomba desligada");
  }
  if (a == "calml") {
    float ml = server.arg("v").toFloat();
    if (!(ml > 0) || ml > 600) return responder(false, "Volume inválido");
    setVazao(ml / CAL_SEG);
    return responder(true, String("Vazão salva: ") + String(cfg.vazao, 2) + " ml/s");
  }
  if (a == "encher") {
    encherGalao();
    atualizarAlertas();
    return responder(true, "Galão em 100%");
  }
  if (a == "otacheck") {
    otaChecarAgora = true;
    return responder(true, "Verificando atualização…");
  }
  if (a == "otainst") {
    if (versaoRemota <= FW_NUM) return responder(false, "Nenhuma versão nova");
    if (!bombaLivre()) return responder(false, "Aguarde a bomba ficar livre (ou uma dose próxima)");
    otaInstalarAgora = true;
    return responder(true, "Atualizando… a dosadora reinicia sozinha");
  }
  if (a == "esquecer") {
    pararBomba(false);
    esquecerWifi();
    return responder(true, "Reiniciando em modo configuração…");
  }
  responder(false, "Ação desconhecida");
}

void uploadFim() {
  if (upEstado == 1) return responder(false, "Senha de atualização incorreta");
  if (upEstado == 2) return responder(false, "Aguarde a bomba ficar livre (ou uma dose próxima)");
  if (upEstado != 0 || Update.hasError()) return responder(false, "Arquivo inválido para esta placa");
  responder(true, "Firmware enviado! Reiniciando…");
  if (!reiniciarEm) reiniciarEm = millis() + 1500;
}

// ---------------- Wi-Fi ----------------

String nomeAP() {
  uint8_t mac[6];
  WiFi.macAddress(mac);
  char buf[32];
  snprintf(buf, sizeof(buf), "PrimeReef-Dosadora-%02X%02X", mac[4], mac[5]);
  return String(buf);
}

String apInicio(const char* titulo) {
  String h = F("<!DOCTYPE html><html lang=pt-BR><head><meta charset=utf-8>"
               "<meta name=viewport content='width=device-width,initial-scale=1'><meta name=theme-color content=#0d1117><title>");
  h += titulo;
  h += F("</title><style>");
  h += FPSTR(AP_CSS);
  h += F("</style></head><body><div class=wrap><header><div class=logo>"
         "<svg width=22 height=26 viewBox='0 0 24 30'><path d='M12 1S2 13 2 19.5a10 10 0 0 0 20 0C22 13 12 1 12 1z' fill='#fff'/>"
         "<path d='M7 20a5 5 0 0 0 5 5' stroke='#1f6feb' stroke-width='2.2' fill='none' stroke-linecap='round'/></svg></div>"
         "<div class=brand><small>PRIME REEF</small><b>Dosadora</b></div></header>");
  return h;
}

String barras(int rssi) {
  int n = rssi >= -55 ? 4 : rssi >= -67 ? 3 : rssi >= -77 ? 2 : 1;
  String s = F("<span class=sig>");
  const char* alt[] = {"4", "7", "10", "14"};
  for (int i = 0; i < 4; i++) {
    s += F("<i style='height:");
    s += alt[i];
    s += i < n ? F("px' class=on></i>") : F("px'></i>");
  }
  s += F("</span>");
  return s;
}

void paginaAP() {
  String h = apInicio("Configurar Dosadora");
  h += F("<div class=card><h3>📶 Conectar ao Wi-Fi</h3>");
  int n = WiFi.scanComplete();
  if (n == WIFI_SCAN_FAILED) {
    WiFi.scanNetworks(true);
    n = WIFI_SCAN_RUNNING;
  }
  if (n == WIFI_SCAN_RUNNING) {
    h += F("<p class=mu><span class=spin></span>Procurando redes…</p><script>setTimeout(()=>location.reload(),2500)</script>");
  } else {
    h += F("<p class=mu>Toque na rede da sua casa:</p><div class=nets>");
    // Ordena por sinal e remove repetidas
    int idx[40];
    int m = 0;
    for (int i = 0; i < n && m < 40; i++) {
      if (WiFi.SSID(i).length() == 0) continue;
      bool rep = false;
      for (int k = 0; k < m; k++) if (WiFi.SSID(idx[k]) == WiFi.SSID(i)) { rep = true; if (WiFi.RSSI(i) > WiFi.RSSI(idx[k])) idx[k] = i; }
      if (!rep) idx[m++] = i;
    }
    for (int a = 0; a < m; a++)
      for (int b = a + 1; b < m; b++)
        if (WiFi.RSSI(idx[b]) > WiFi.RSSI(idx[a])) { int x = idx[a]; idx[a] = idx[b]; idx[b] = x; }
    for (int k = 0; k < m; k++) {
      String s = htmlEsc(WiFi.SSID(idx[k]));
      h += F("<button type=button class=net data-s=\"");
      h += s;
      h += F("\" onclick=pick(this)><span>");
      h += WiFi.encryptionType(idx[k]) == ENC_TYPE_NONE ? F("🔓 ") : F("🔒 ");
      h += s;
      h += F("</span>");
      h += barras(WiFi.RSSI(idx[k]));
      h += F("</button>");
    }
    if (m == 0) h += F("<p class=mu>Nenhuma rede encontrada.</p>");
    h += F("</div><a class=lnk href=/scan>↻ Procurar de novo</a>");
  }
  h += F("<form method=POST action=/wifi><label>Rede</label><input id=s name=s maxlength=32 required placeholder='Nome da rede'>"
         "<label>Senha</label><div class=pw><input id=p name=p type=password maxlength=64 placeholder='Senha do Wi-Fi'>"
         "<button type=button onclick=\"var e=document.getElementById('p');e.type=e.type=='password'?'text':'password'\">👁</button></div>"
         "<button class=btn>Conectar</button></form></div>"
         "<div class=foot>Prime Reef Brasil · Dosadora 1 canal · v" FW_VERSION "</div></div>"
         "<script>function pick(b){document.getElementById('s').value=b.dataset.s;document.querySelectorAll('.net').forEach(function(x){x.classList.toggle('on',x==b)});document.getElementById('p').focus()}</script>"
         "</body></html>");
  server.send(200, "text/html", h);
}

void paginaInicial() {
  if (modoAP) return paginaAP();
  server.sendHeader("Cache-Control", "no-cache");
  server.send_P(200, "text/html", PAGINA);
}

bool soNoAP() {
  if (modoAP) return true;
  server.send(403, "text/plain", "Disponivel so no modo configuracao");
  return false;
}

void reescanear() {
  if (!soNoAP()) return;
  WiFi.scanDelete();
  WiFi.scanNetworks(true);
  server.sendHeader("Location", "/");
  server.send(302, "text/plain", "");
}

void salvarWifi() {
  if (!soNoAP()) return;
  String s = server.arg("s"), p = server.arg("p");
  if (s.length() == 0 || s.length() > 32 || p.length() > 64) {
    server.send(400, "text/html", apInicio("Erro") + F("<div class=card><h3>⚠️ Dados inválidos</h3><a class=btn href=/>Voltar</a></div></div></body></html>"));
    return;
  }
  strncpy(cfg.ssid, s.c_str(), sizeof(cfg.ssid) - 1);
  cfg.ssid[sizeof(cfg.ssid) - 1] = 0;
  strncpy(cfg.pass, p.c_str(), sizeof(cfg.pass) - 1);
  cfg.pass[sizeof(cfg.pass) - 1] = 0;
  gravarAgora();
  server.send(200, "text/html",
              apInicio("Salvo") + F("<div class=card><h3>✅ Salvo!</h3><p>A dosadora vai reiniciar e entrar na rede <b>") +
                  htmlEsc(s) +
                  F("</b>.</p><p class=mu>Volte o celular para o seu Wi-Fi e procure a dosadora no app Prime Reef.</p></div></div></body></html>"));
  reiniciarEm = millis() + 1500;
}

void portalCativo() {
  // Celulares testam a internet ao entrar na rede; redirecionar faz a página de configuração abrir sozinha.
  server.sendHeader("Location", String("http://") + WiFi.softAPIP().toString() + "/");
  server.send(302, "text/plain", "");
}

void abrirAP() {
  modoAP = true;
  WiFi.mode(WIFI_AP_STA);   // STA ligado só para procurar redes
  WiFi.disconnect();
  WiFi.softAP(nomeAP().c_str(), "primereef");
  dns.start(53, "*", WiFi.softAPIP());
  WiFi.scanNetworks(true);
  proximaTentativa = millis() + AP_TENTATIVA_MS;
}

void conectarWifi() {
  WiFi.persistent(false);
  WiFi.hostname(reef.deviceId());
  if (strlen(cfg.ssid) > 0) {
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(cfg.ssid, cfg.pass);
    uint32_t inicio = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - inicio < 20000) delay(250);
    if (WiFi.status() == WL_CONNECTED) return;
  }
  abrirAP();
}

// Modo configuração: tenta o Wi-Fi salvo de tempos em tempos, só quando ninguém está conectado na rede
// da dosadora (tentar conectar faz o rádio trocar de canal e derruba o celular da configuração).
void tarefasAP() {
  dns.processNextRequest();
  if (strlen(cfg.ssid) == 0) return;
  if (!tentandoSTA) {
    if ((int32_t)(millis() - proximaTentativa) >= 0 && WiFi.softAPgetStationNum() == 0 &&
        WiFi.scanComplete() != WIFI_SCAN_RUNNING) {
      tentandoSTA = true;
      tentativaInicio = millis();
      WiFi.begin(cfg.ssid, cfg.pass);
    }
    return;
  }
  if (WiFi.status() == WL_CONNECTED) {
    if (!reiniciarEm) reiniciarEm = millis() + 3000;  // reinicia em modo normal
  } else if (millis() - tentativaInicio > 20000 || WiFi.softAPgetStationNum() > 0) {
    WiFi.disconnect();
    tentandoSTA = false;
    proximaTentativa = millis() + AP_TENTATIVA_MS;
  }
}

// ---------------- Setup / loop ----------------

void setup() {
  Serial.begin(115200);
  carregar();
  pinMode(cfg.relePino, OUTPUT);
  rele(false);
  rtcCarregar();
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);
  // Resultado de uma atualização feita antes deste reinício.
  if (otaTentada) {
    if (otaTentada > FW_NUM) {
      otaFalhou = otaTentada;
      otaMsg = "A versão " + String(otaTentada) + " não se instalou; não vai tentar de novo sozinha";
    } else {
      otaMsg = String("Atualizada para a versão ") + FW_VERSION;
    }
    otaTentada = 0;
  }
  rtcSalvar();  // grava "bomba desligada" (o aviso de reinício fica só na RAM)
  pinMode(BOTAO_PIN, INPUT_PULLUP);
  otaProxima = millis() + OTA_PRIMEIRA_MS;

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
  reef.addTextSensor("fw", "Firmware", []() -> String {
    String t = String("v") + FW_VERSION;
    if (versaoRemota > FW_NUM) t += " · v" + String(versaoRemota) + " disponível";
    return t;
  });

  // ---- Dosagem ----
  reef.addSwitch("auto", "Dosagem automática", [] { return cfg.automatico; }, [](bool v) { setAuto(v); });
  reef.addSlider("ml", "Volume por dia", 0, 500, 1, "ml", [] { return cfg.mlDia; }, [](float v) { setMlDia(v); });
  reef.addSlider("doses", "Doses por dia", 1, 24, 1, "", [] { return (float)cfg.dosesDia; }, [](float v) { setDoses(v); });
  reef.addSlider("inicio", "Horário da 1ª dose", 0, 23, 1, "h", [] { return (float)cfg.horaInicio; },
                 [](float v) { setInicio(v); });
  reef.addButton("d1", "Dosar 1 ml agora", [] { dosar(1); }, "Dosar 1 ml agora?");
  reef.addButton("d5", "Dosar 5 ml agora", [] { dosar(5); }, "Dosar 5 ml agora?");
  reef.addSwitch("bomba", "Bomba ligada (manual, máx. 60 s)", [] { return bombaLigada; },
                 [](bool v) {
                   if (v) iniciarBomba(T_MANUAL, MANUAL_MAX_SEG, 0);
                   else { cancelarFila(); pararBomba(false); }
                 });

  // ---- Calibração e galão ----
  reef.addButton("cal", "Calibrar: ligar a bomba por 30 s", [] { iniciarBomba(T_CAL, CAL_SEG, 0); },
                 "Coloque a mangueira num copo medidor. A bomba vai ligar por 30 segundos. "
                 "Depois divida os ml medidos por 30 e informe em \"Vazão calibrada\".");
  reef.addNumber("vazao", "Vazão calibrada", 0.01, 20, 0.01, "ml/s", [] { return cfg.vazao; },
                 [](float v) { setVazao(v); });
  reef.addNumber("cap", "Capacidade do galão", 100, 20000, 50, "ml", [] { return cfg.capacidade; },
                 [](float v) { setCapacidade(v); });
  reef.addButton("encher", "Galão reabastecido", [] { encherGalao(); }, "Confirmar que o galão foi reabastecido?");

  // ---- Instalação ----
  std::vector<String> opcoes;
  for (int i = 0; i < N_PINOS; i++) opcoes.push_back(PINOS_NOMES[i]);
  reef.addSelect("pino", "Pino do relé (instalação)", opcoes,
                 []() -> String {
                   int i = pinoIndice();
                   return i >= 0 ? String(PINOS_NOMES[i]) : String("GPIO") + cfg.relePino;
                 },
                 [](const String& v) {
                   for (int i = 0; i < N_PINOS; i++)
                     if (v == PINOS_NOMES[i]) setPino(i);
                 });
  reef.addButton("esquecer", "Esquecer Wi-Fi", [] { pararBomba(false); esquecerWifi(); },
                 "A dosadora vai sair do Wi-Fi e criar a rede de configuração PrimeReef-Dosadora. Continuar?");

  // Rotas da página antes do reef.begin: no servidor, a primeira rota registrada tem prioridade.
  server.on("/", paginaInicial);
  server.on("/wifi", HTTP_POST, salvarWifi);
  server.on("/scan", reescanear);
  server.on("/d/status", apiStatus);
  server.on("/d/set", HTTP_POST, apiSet);
  server.on("/d/acao", HTTP_POST, apiAcao);
  server.on("/d/upload", HTTP_POST, uploadFim, uploadArquivo);
  reef.begin(server);
  if (modoAP) server.onNotFound(portalCativo);
  server.begin();
  atualizarAlertas();
}

void loop() {
  server.handleClient();
  reef.loop();

  if (bombaLigada && millis() - inicioMs >= duracaoMs) pararBomba(true);

  // Dose agendada que chegou com a bomba ocupada.
  if (!bombaLigada && pendenteMl > 0.0001f) {
    float p = pendenteMl;
    if (cfg.automatico) {
      pendenteMl = 0;
      pendenteBit = 0;
      dosar(p);
    } else {
      cancelarFila();  // automático foi desligado enquanto esperava
    }
  }

  static uint32_t ultimoSeg = 0;
  if (millis() - ultimoSeg >= 1000) {
    ultimoSeg = millis();
    verificarAgenda();
    atualizarAlertas();
  }

  tarefasGravacao();
  tarefasLed();
  if (modoAP) tarefasAP();
  else tarefasOTA();

  // Botão segurado por 5 s: esquece o Wi-Fi.
  if (digitalRead(BOTAO_PIN) == LOW) {
    if (botaoDesde == 0) botaoDesde = millis();
    if (millis() - botaoDesde > 5000 && reiniciarEm == 0) {
      pararBomba(false);
      esquecerWifi();
      reiniciarEm = millis() + 500;
    }
  } else {
    botaoDesde = 0;
  }

  if (reiniciarEm && (int32_t)(millis() - reiniciarEm) >= 0) {
    pararBomba(false);
    gravarAgora();
    rtcSalvar();
    ESP.restart();
  }
}

// ============================================================================
// Estilo da página de configuração (modo AP)
// ============================================================================
const char AP_CSS[] PROGMEM = R"CSS(
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:radial-gradient(900px 420px at 50% -180px,#11284a 0,#0d1117 65%) no-repeat,#0d1117;color:#c9d1d9;font:15px/1.45 -apple-system,"Segoe UI",Roboto,sans-serif}
.wrap{max-width:440px;margin:auto;padding:18px 14px 40px}
header{display:flex;align-items:center;gap:12px;margin-bottom:6px}
.logo{width:44px;height:44px;border-radius:13px;background:linear-gradient(140deg,#1f6feb,#39c5cf);display:grid;place-items:center;box-shadow:0 6px 18px #1f6feb44}
.brand small{display:block;font-size:10px;letter-spacing:2.5px;color:#58a6ff;font-weight:700}
.brand b{font-size:19px;color:#fff}
.card{background:#161b22;border:1px solid #30363d;border-radius:14px;padding:16px;margin-top:12px}
h3{margin:0 0 10px;font-size:13px;color:#8b949e;text-transform:uppercase;letter-spacing:1.2px}
.mu{color:#8b949e;font-size:13px}
.nets{display:flex;flex-direction:column;gap:6px;margin:6px 0 10px}
.net{display:flex;justify-content:space-between;align-items:center;background:#1c2330;border:1px solid #30363d;border-radius:10px;padding:12px;color:#fff;font-size:15px;font-weight:600;font-family:inherit;text-align:left;cursor:pointer}
.net.on{border-color:#1f6feb;background:#132544}
.sig{display:inline-flex;align-items:flex-end;gap:2px;height:14px}
.sig i{width:4px;background:#30363d;border-radius:1px}.sig i.on{background:#3fb950}
label{display:block;font-size:12px;color:#8b949e;margin:12px 0 4px;font-weight:700;letter-spacing:.5px}
input{width:100%;background:#0d1117;border:1px solid #30363d;border-radius:10px;padding:12px;color:#fff;font-size:16px;font-family:inherit}
input:focus{outline:none;border-color:#58a6ff;box-shadow:0 0 0 3px #1f6feb33}
.pw{display:flex;gap:6px}.pw button{width:52px;background:#1c2330;border:1px solid #30363d;border-radius:10px;font-size:18px}
.btn{display:block;width:100%;margin-top:16px;border:0;border-radius:10px;padding:14px;background:#1f6feb;color:#fff;font-size:16px;font-weight:700;text-align:center;text-decoration:none;font-family:inherit}
.lnk{color:#58a6ff;font-size:13px;text-decoration:none}
.spin{display:inline-block;width:14px;height:14px;border:2px solid #30363d;border-top-color:#58a6ff;border-radius:50%;margin-right:8px;vertical-align:-2px;animation:r 1s linear infinite}
@keyframes r{to{transform:rotate(360deg)}}
.foot{text-align:center;color:#484f58;font-size:11px;margin-top:22px}
)CSS";

// ============================================================================
// Página principal (servida da flash; os dados vêm de /d/status)
// ============================================================================
const char PAGINA[] PROGMEM = R"HTML(<!DOCTYPE html><html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#0d1117">
<title>Dosadora Prime Reef</title>
<style>
:root{--bg:#0d1117;--c1:#161b22;--c2:#1c2330;--bd:#30363d;--tx:#c9d1d9;--mu:#8b949e;--bl:#1f6feb;--lb:#58a6ff;--cy:#39c5cf;--gr:#3fb950;--am:#f0a500;--rd:#f85149}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:radial-gradient(900px 420px at 50% -180px,#11284a 0,var(--bg) 65%) no-repeat,var(--bg);color:var(--tx);font:15px/1.45 -apple-system,"Segoe UI",Roboto,sans-serif}
.wrap{max-width:440px;margin:auto;padding:18px 14px 50px}
header{display:flex;align-items:center;gap:12px}
.logo{width:44px;height:44px;border-radius:13px;background:linear-gradient(140deg,var(--bl),var(--cy));display:grid;place-items:center;box-shadow:0 6px 18px #1f6feb44;flex:none}
.brand small{display:block;font-size:10px;letter-spacing:2.5px;color:var(--lb);font-weight:700}
.brand b{font-size:19px;color:#fff}
.clk{margin-left:auto;text-align:right}
.clk b{display:block;font-size:24px;color:#fff;font-variant-numeric:tabular-nums;line-height:1.1}
.clk span{font-size:11px;color:var(--mu)}
.dot{display:inline-block;width:7px;height:7px;border-radius:50%;background:var(--gr);margin-right:5px;box-shadow:0 0 6px var(--gr);vertical-align:1px}
.dot.off{background:var(--rd);box-shadow:0 0 6px var(--rd)}
.tabbar{display:flex;gap:6px;margin:18px 0 2px}
.tab{flex:1;background:var(--c1);border:1px solid var(--bd);border-radius:10px;padding:9px 2px;color:var(--mu);font-size:11px;font-weight:700;font-family:inherit;line-height:1.5;cursor:pointer}
.tab i{font-style:normal;font-size:19px;display:block;margin-bottom:1px}
.tab.on{background:var(--bl);border-color:var(--bl);color:#fff;box-shadow:0 4px 14px #1f6feb55}
.pane{display:none}.pane.on{display:block;animation:fi .2s ease}
@keyframes fi{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:none}}
.card{background:var(--c1);border:1px solid var(--bd);border-radius:14px;padding:16px;margin-top:12px}
.card h3{margin:0 0 12px;font-size:12px;color:var(--mu);text-transform:uppercase;letter-spacing:1.2px;font-weight:700}
.grid2{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:12px}
.grid2 .card{margin:0}
.big{font-size:28px;font-weight:800;color:#fff;font-variant-numeric:tabular-nums;line-height:1.1}
.big small{font-size:14px;color:var(--mu);font-weight:600}
.mu{color:var(--mu);font-size:13px}
.bar{height:8px;background:var(--bg);border-radius:6px;overflow:hidden;margin:10px 0 6px;border:1px solid var(--bd)}
.bar>div{height:100%;width:0;background:linear-gradient(90deg,var(--bl),var(--cy));border-radius:6px;transition:width .6s}
.gal{display:flex;gap:18px;align-items:center}
.jug{position:relative;width:84px;height:118px;flex:none}
.jug .cap{position:absolute;left:50%;top:0;width:30px;height:11px;margin-left:-15px;background:#3d4654;border-radius:4px 4px 0 0}
.jug .bd{position:absolute;top:10px;left:0;right:0;bottom:0;border:2px solid #3d4654;border-radius:16px 16px 20px 20px;overflow:hidden;background:#0a0e14}
.liq{position:absolute;left:0;right:0;bottom:0;height:0;background:linear-gradient(180deg,var(--cy),var(--bl));transition:height 1s ease}
.liq svg{position:absolute;top:-8px;left:0;width:200%;height:9px;animation:wv 3.5s linear infinite}
.liq path{fill:var(--cy)}
.jug.warn .liq{background:linear-gradient(180deg,#f7c948,#c27c00)}.jug.warn .liq path{fill:#f7c948}
.jug.alarm .liq{background:linear-gradient(180deg,#ff7b72,#b91c1c)}.jug.alarm .liq path{fill:#ff7b72}
.jug .pct{position:absolute;top:10px;left:0;right:0;bottom:0;display:grid;place-items:center;font-weight:800;font-size:20px;color:#fff;text-shadow:0 1px 5px #000c}
@keyframes wv{from{transform:translateX(0)}to{transform:translateX(-50%)}}
.pump{display:flex;align-items:center;gap:12px}
.pico{width:46px;height:46px;border-radius:50%;display:grid;place-items:center;background:var(--c2);border:1px solid var(--bd);font-size:22px;flex:none;filter:grayscale(1);opacity:.6;transition:.3s}
.pump.on .pico{filter:none;opacity:1;border-color:var(--bl);animation:pl 1.2s ease-in-out infinite}
@keyframes pl{0%,100%{box-shadow:0 0 0 0 #1f6feb99}60%{box-shadow:0 0 0 9px #1f6feb00}}
.pump b{color:#fff}
button{font-family:inherit}
.btn{width:100%;border:0;border-radius:10px;padding:13px;font-size:15px;font-weight:700;color:#fff;background:var(--bl);cursor:pointer;transition:transform .08s,opacity .2s}
.btn:active{transform:scale(.97)}
.btn.g{background:#238636}.btn.r{background:#b91c1c}.btn.s{background:var(--c2);border:1px solid var(--bd);color:var(--tx)}
.btn:disabled{opacity:.4}
.btn.sm{width:auto;padding:10px 16px;font-size:14px;flex:none}
.chips{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
.chip{background:var(--c2);border:1px solid var(--bd);border-radius:12px;padding:10px 0;color:#fff;font-weight:800;font-size:20px;cursor:pointer;transition:transform .08s}
.chip:active{transform:scale(.95);border-color:var(--bl)}
.chip small{display:block;font-size:10px;color:var(--mu);font-weight:700;letter-spacing:1px}
.row{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:11px 0;border-top:1px solid #21262d}
.row.f{border-top:0;padding-top:0}
.row b{color:#fff;text-align:right;word-break:break-all}
input,select{background:var(--bg);border:1px solid var(--bd);color:#fff;border-radius:10px;padding:10px 12px;font-size:16px;width:100%;font-family:inherit}
input:focus,select:focus{outline:none;border-color:var(--lb);box-shadow:0 0 0 3px #1f6feb33}
.inl{display:flex;gap:8px}
.num{width:92px;text-align:right;font-weight:700}
.sw{position:relative;width:54px;height:31px;flex:none}
.sw input{opacity:0;width:0;height:0;position:absolute}
.sw span{position:absolute;inset:0;background:#30363d;border-radius:31px;transition:.25s;cursor:pointer}
.sw span:before{content:"";position:absolute;width:25px;height:25px;left:3px;top:3px;background:#fff;border-radius:50%;transition:.25s;box-shadow:0 1px 3px #0006}
.sw input:checked+span{background:#238636}
.sw input:checked+span:before{transform:translateX(23px)}
input[type=range]{-webkit-appearance:none;appearance:none;padding:0;height:6px;border:0;border-radius:6px;margin:6px 0 12px;background:linear-gradient(90deg,var(--bl) var(--p,0%),#30363d var(--p,0%))}
input[type=range]:focus{box-shadow:none}
input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:24px;height:24px;border-radius:50%;background:#fff;border:4px solid var(--bl);box-shadow:0 2px 6px #0009}
input[type=range]::-moz-range-thumb{width:18px;height:18px;border-radius:50%;background:#fff;border:4px solid var(--bl)}
.stp{display:flex;align-items:center;border:1px solid var(--bd);border-radius:10px;overflow:hidden;background:var(--bg)}
.stp button{width:42px;height:40px;background:var(--c2);border:0;color:#fff;font-size:20px;cursor:pointer}
.stp b{min-width:46px;text-align:center;font-size:18px;color:#fff}
.sum{background:var(--bg);border:1px dashed var(--bd);border-radius:10px;padding:12px;margin-top:12px;font-size:14px}
.sum b{color:#fff}
.warn{background:#3d2b00;color:var(--am);border:1px solid #f0a50066;border-radius:10px;padding:10px 12px;font-size:13px;margin-top:10px}
.stn{display:flex;gap:12px;margin-top:14px}
.stn:first-of-type{margin-top:0}
.stn .n{width:26px;height:26px;border-radius:50%;background:var(--bl);color:#fff;font-weight:800;display:grid;place-items:center;flex:none;font-size:13px}
.stn>div:last-child{flex:1}
canvas{width:100%;height:112px;display:block}
.lg{display:flex;gap:14px;font-size:12px;color:var(--mu);margin-top:6px;flex-wrap:wrap}
.lg i{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:5px;vertical-align:-1px}
.sig{display:inline-flex;align-items:flex-end;gap:2px;height:14px;vertical-align:-2px}
.sig i{width:4px;background:#30363d;border-radius:1px}.sig i.on{background:var(--gr)}
#toast{position:fixed;left:50%;bottom:26px;transform:translate(-50%,30px);background:#1c2330;border:1px solid var(--bd);color:#fff;padding:12px 18px;border-radius:12px;font-size:14px;opacity:0;transition:.3s;pointer-events:none;box-shadow:0 10px 30px #000a;max-width:90%;text-align:center;z-index:20}
#toast.on{opacity:1;transform:translate(-50%,0)}
#toast.er{border-color:var(--rd)}
.modal{position:fixed;inset:0;background:#000b;display:none;align-items:flex-end;justify-content:center;z-index:10}
.modal.on{display:flex}
.sheet{background:var(--c1);border:1px solid var(--bd);border-radius:18px 18px 0 0;padding:22px 18px 30px;width:100%;max-width:440px;animation:up .22s ease}
@keyframes up{from{transform:translateY(40px);opacity:0}}
.sheet p{margin:0 0 18px;font-size:16px;color:#fff}
.sheet .grid2{margin:0}
.foot{text-align:center;color:#484f58;font-size:11px;margin-top:22px}
</style></head><body><div class="wrap">

<header>
 <div class="logo"><svg width="22" height="26" viewBox="0 0 24 30"><path d="M12 1S2 13 2 19.5a10 10 0 0 0 20 0C22 13 12 1 12 1z" fill="#fff"/><path d="M7 20a5 5 0 0 0 5 5" stroke="#1f6feb" stroke-width="2.2" fill="none" stroke-linecap="round"/></svg></div>
 <div class="brand"><small>PRIME REEF</small><b>Dosadora</b></div>
 <div class="clk"><b id="hora">--:--</b><span><i class="dot off" id="cx"></i><span id="cxt">conectando</span></span></div>
</header>

<nav class="tabbar">
 <button class="tab on" data-t="painel"><i>💧</i>Painel</button>
 <button class="tab" data-t="agenda"><i>⏰</i>Agenda</button>
 <button class="tab" data-t="cal"><i>🧪</i>Calibrar</button>
 <button class="tab" data-t="sis"><i>⚙️</i>Sistema</button>
</nav>

<section class="pane on" id="p-painel">
 <div class="warn" id="rst" style="display:none;margin-top:12px">⚠ A placa reiniciou com a bomba ligada. Isso costuma ser fonte fraca ou mau contato: a dose foi interrompida. O aviso some depois da próxima dose completa.</div>
 <div class="card">
  <div class="pump" id="pump"><div class="pico">💧</div>
   <div style="flex:1;min-width:0"><b id="pt">Bomba parada</b><div class="mu" id="ps">—</div></div>
   <button class="btn r sm" id="pstop" style="display:none">■ Parar</button></div>
  <div class="bar" id="pbar" style="display:none;margin-bottom:0"><div id="pbf"></div></div>
 </div>
 <div class="card"><h3>Galão</h3>
  <div class="gal">
   <div class="jug" id="jug"><div class="cap"></div><div class="bd"><div class="liq" id="liq"><svg viewBox="0 0 200 10" preserveAspectRatio="none"><path d="M0 5Q12.5 0 25 5T50 5T75 5T100 5T125 5T150 5T175 5T200 5V10H0z"/></svg></div></div><div class="pct" id="gpct">--%</div></div>
   <div style="flex:1;min-width:0"><div class="big" id="gml">--<small> ml</small></div><div class="mu" id="gcap">—</div><div class="mu" id="gdias" style="margin-top:4px"></div>
    <button class="btn s sm" id="encher" style="margin-top:12px">🔄 Reabastecido</button></div>
  </div>
 </div>
 <div class="grid2">
  <div class="card"><h3>Hoje</h3><div class="big" id="hj">--<small> ml</small></div><div class="bar"><div id="hjb"></div></div><div class="mu" id="hjs">—</div></div>
  <div class="card"><h3>Próxima dose</h3><div class="big" id="px">--:--</div><div class="mu" id="pxs" style="margin-top:10px">—</div></div>
 </div>
 <div class="card"><h3>Dosar agora</h3>
  <div class="chips"><button class="chip" data-ml="1">1<small>ML</small></button><button class="chip" data-ml="5">5<small>ML</small></button><button class="chip" data-ml="10">10<small>ML</small></button></div>
  <div class="inl" style="margin-top:10px"><input id="mlx" type="number" inputmode="decimal" min="0.1" max="500" step="0.1" placeholder="Outro volume (ml)"><button class="btn sm" id="dx">Dosar</button></div>
 </div>
</section>

<section class="pane" id="p-agenda">
 <div class="card"><div class="row f" style="padding:0"><div><b style="color:#fff;text-align:left">Dosagem automática</b><div class="mu" id="autos">—</div></div><label class="sw"><input type="checkbox" id="auto"><span></span></label></div></div>
 <div class="card"><h3>Programação</h3>
  <div class="row f"><span>Volume por dia</span><span class="inl" style="align-items:center"><input class="num" id="mln" type="number" inputmode="decimal" min="0" max="500" step="0.5"><span class="mu">ml</span></span></div>
  <input type="range" id="ml" min="0" max="500" step="1">
  <div class="row"><span>Doses por dia</span><div class="stp"><button id="dm">−</button><b id="dv">--</b><button id="dp">+</button></div></div>
  <div class="row"><span>1ª dose do dia</span><select id="ini" style="width:112px"></select></div>
  <div class="sum" id="sum">—</div>
  <div class="warn" id="lim" style="display:none"></div>
  <button class="btn" id="salvar" style="margin-top:14px" disabled>Salvar programação</button>
 </div>
 <div class="card"><h3>Agenda de hoje</h3><canvas id="cv"></canvas>
  <div class="lg"><span><i style="background:#3fb950"></i>Feita</span><span><i style="border:2px solid #58a6ff"></i>Programada</span><span><i style="background:#484f58"></i>Não feita</span></div></div>
</section>

<section class="pane" id="p-cal">
 <div class="card"><h3>Vazão da bomba</h3><div class="big" id="vz">--<small> ml/s</small></div><div class="mu" id="vzs">—</div></div>
 <div class="card"><h3>Calibrar</h3>
  <div class="stn"><div class="n">1</div><div>Coloque a ponta da mangueira num <b style="color:#fff">copo medidor</b>. A mangueira já deve estar cheia de líquido (use "Escorvar" em Sistema).</div></div>
  <div class="stn"><div class="n">2</div><div>Ligue a bomba por exatamente 30 segundos.
   <button class="btn" id="calgo" style="margin-top:10px">▶ Ligar por 30 s</button>
   <div id="calrun" style="display:none"><div class="bar"><div id="calb"></div></div><div class="inl" style="align-items:center;justify-content:space-between"><span class="mu" id="calt"></span><button class="btn r sm" id="calx">Cancelar</button></div></div></div></div>
  <div class="stn"><div class="n">3</div><div>Quantos ml caíram no copo?
   <div class="inl" style="margin-top:10px"><input id="calml" type="number" inputmode="decimal" step="0.1" min="0.1" placeholder="ex: 28,5"><button class="btn g sm" id="calok">Salvar</button></div></div></div>
 </div>
 <div class="card"><h3>Ajuste manual</h3><div class="inl"><input id="vzm" type="number" inputmode="decimal" step="0.01" min="0.01" max="20"><button class="btn s sm" id="vzok">Salvar ml/s</button></div></div>
 <div class="card"><h3>Galão</h3><div class="mu" style="margin-bottom:8px">Capacidade do galão cheio</div>
  <div class="inl"><input id="capi" type="number" inputmode="numeric" step="50" min="100" max="20000"><button class="btn s sm" id="capok">Salvar ml</button></div></div>
</section>

<section class="pane" id="p-sis">
 <div class="card"><h3>📶 Wi-Fi</h3>
  <div class="row f"><span class="mu">Rede</span><b id="wss">—</b></div>
  <div class="row"><span class="mu">Sinal</span><span><span class="sig" id="sig"><i style="height:4px"></i><i style="height:7px"></i><i style="height:10px"></i><i style="height:14px"></i></span> <span class="mu" id="wrs"></span></span></div>
  <div class="row"><span class="mu">Endereço</span><b id="wip">—</b></div>
  <div class="row"><span class="mu">Nome na rede</span><b id="whn">—</b></div>
 </div>
 <div class="card"><h3>💧 Escorvar mangueira</h3><div class="mu" style="margin-bottom:12px">Liga a bomba para encher a mangueira. Desliga sozinha em 60 s.</div><button class="btn s" id="man">Ligar bomba</button></div>
 <div class="card"><h3>⬆️ Atualização</h3>
  <div class="row f"><span class="mu">Versão instalada</span><b id="fw">—</b></div>
  <div class="row"><span class="mu">Versão no servidor</span><b id="vr">—</b></div>
  <div class="mu" id="otam" style="margin:4px 0 12px">—</div>
  <div class="grid2" style="margin:0"><button class="btn s" id="otac">🔍 Verificar</button><button class="btn g" id="otai" disabled>Atualizar</button></div>
  <div class="row" style="margin-top:12px"><div><b style="color:#fff;text-align:left">Atualizar sozinha</b><div class="mu">Instala versões novas quando a bomba está livre</div></div><label class="sw"><input type="checkbox" id="aup"><span></span></label></div>
 </div>
 <div class="card"><h3>📁 Atualizar por arquivo</h3>
  <div class="mu" style="margin-bottom:10px">Envie um firmware .bin gerado na Arduino IDE (Sketch → Exportar binário compilado).</div>
  <input type="file" id="binf" accept=".bin">
  <input type="password" id="otap" placeholder="Senha de atualização" style="margin-top:8px">
  <div class="bar" id="upb" style="display:none"><div id="upf"></div></div>
  <button class="btn s" id="upgo" style="margin-top:10px">Enviar firmware</button>
 </div>
 <div class="card"><h3>🔧 Instalação</h3>
  <div class="row f"><span>Pino do relé</span><select id="pino" style="width:130px"></select></div>
  <div class="mu">Nesta placa o relé é o GPIO5. Só altere se a placa foi modificada; depois teste com "Escorvar mangueira".</div></div>
 <div class="card"><h3>📶 Trocar de rede</h3><div class="mu" style="margin-bottom:12px">Apaga o Wi-Fi salvo e abre a rede PrimeReef-Dosadora para configurar de novo. Também dá para fechar o jumper IO0 da placa por 5 s.</div>
  <button class="btn r" id="esq">Esquecer Wi-Fi</button></div>
 <div class="foot">Prime Reef Brasil · Dosadora 1 canal</div>
</section>

</div>
<div id="toast"></div>
<div class="modal" id="mod"><div class="sheet"><p id="mtx"></p><div class="grid2"><button class="btn s" id="mno">Cancelar</button><button class="btn" id="mok">Confirmar</button></div></div></div>

<script>
const $=i=>document.getElementById(i);
const PINOS=['GPIO5','GPIO4','GPIO12','GPIO13','GPIO14'];
let S=null,dirty=false,tm=null,fwAnt=null;
const nf=(n,d)=>Number(n).toLocaleString('pt-BR',{minimumFractionDigits:d,maximumFractionDigits:d});
const pad=n=>String(n).padStart(2,'0');
const hm=m=>pad(Math.floor(m/60))+':'+pad(m%60);
const dur=m=>{const h=Math.floor(m/60),r=m%60;return h?(h+'h'+(r?' '+pad(r)+'min':'')):(r+' min')};
const pop=m=>{let c=0;while(m){c+=m&1;m>>>=1}return c};

function toast(t,er){const e=$('toast');e.textContent=t;e.className='on'+(er?' er':'');clearTimeout(e._t);e._t=setTimeout(()=>e.className=er?'er':'',2800)}
function ask(t,ok){return new Promise(r=>{$('mtx').textContent=t;$('mok').textContent=ok||'Confirmar';$('mod').className='modal on';
 const f=v=>{$('mod').className='modal';r(v)};$('mok').onclick=()=>f(true);$('mno').onclick=()=>f(false);$('mod').onclick=e=>{if(e.target===$('mod'))f(false)}})}
function req(u,o){const c=new AbortController(),t=setTimeout(()=>c.abort(),6000);return fetch(u,Object.assign({signal:c.signal,cache:'no-store'},o||{})).then(r=>r.json()).finally(()=>clearTimeout(t))}
async function post(u,d){try{return await req(u,{method:'POST',body:new URLSearchParams(d)})}catch(e){toast('Sem conexão com a dosadora',1);return null}}
async function act(d){const j=await post('/d/acao',d);if(j){toast(j.msg,!j.ok);poll()}return j}
async function put(d,msg){const j=await post('/d/set',d);if(j){S=j;render();if(msg)toast(msg)}return j}
function net(ok){$('cx').className='dot'+(ok?'':' off');$('cxt').textContent=ok?'online':'sem conexão'}
let pid=0;
async function poll(){clearTimeout(tm);const id=++pid;try{const j=await req('/d/status');if(id!==pid)return;S=j;render();net(1)}catch(e){if(id!==pid)return;net(0)}
 clearTimeout(tm);tm=setTimeout(poll,S&&S.bomba?800:3000)}

function form(){return{ml:Math.max(0,Math.min(500,parseFloat($('mln').value)||0)),doses:parseInt($('dv').textContent)||1,ini:parseInt($('ini').value)||0}}

function render(){
 const s=S;if(!s)return;
 const on=!!s.bomba;
 $('hora').textContent=s.hv?s.hora:'--:--';
 $('rst').style.display=s.rst?'':'none';
 // Bomba
 $('pump').className='pump'+(on?' on':'');
 $('pt').textContent=on?(s.tipo==1?'Dosando '+nf(s.alvo,1)+' ml':s.tipo==2?'Escorvando':'Calibrando'):'Bomba parada';
 $('ps').textContent=on?Math.ceil(s.rest)+' s restantes':!s.auto?'Dosagem automática desligada':!s.hv?'Aguardando horário da internet':'Aguardando a próxima dose';
 $('pstop').style.display=on?'':'none';$('pbar').style.display=on?'':'none';
 $('pbf').style.width=(on&&s.dur?100*(1-s.rest/s.dur):0)+'%';
 // Galão
 const pc=s.cap>0?Math.max(0,Math.min(100,s.res/s.cap*100)):0;
 $('liq').style.height=pc+'%';$('liq').style.visibility=pc>0.5?'':'hidden';
 $('gpct').textContent=Math.round(pc)+'%';
 $('jug').className='jug'+(pc<10?' alarm':pc<20?' warn':'');
 $('gml').innerHTML=nf(s.res,0)+'<small> ml</small>';$('gcap').textContent='de '+nf(s.cap,0)+' ml';
 const d=s.ml>0?s.res/s.ml:0;
 $('gdias').textContent=s.auto&&s.ml>0?(d<1?'Acaba hoje':'Dura ≈ '+Math.floor(d)+(Math.floor(d)==1?' dia':' dias')):'';
 // Hoje
 $('hj').innerHTML=nf(s.hoje,1)+'<small> ml</small>';
 $('hjb').style.width=(s.ml>0?Math.min(100,s.hoje/s.ml*100):0)+'%';
 $('hjs').textContent='meta '+nf(s.ml,1)+' ml · '+pop(s.mask)+'/'+s.doses+' doses';
 // Próxima
 if(!s.auto){$('px').textContent='—';$('pxs').textContent='Automático desligado'}
 else if(!s.hv){$('px').textContent='--:--';$('pxs').textContent='Sem horário da internet'}
 else if(s.ml<=0){$('px').textContent='—';$('pxs').textContent='Volume zerado'}
 else{$('px').textContent=hm(s.prox);$('pxs').textContent=nf(s.mpd,1)+' ml · em '+dur(((s.prox-s.min+1440)%1440)||1440)}
 // Agenda
 $('auto').checked=!!s.auto;
 $('autos').textContent=s.auto?'Ligada — seguindo a programação':'Desligada — nenhuma dose automática';
 if(!dirty){$('mln').value=s.ml;$('ml').value=s.ml;$('dv').textContent=s.doses;$('ini').value=s.ini}
 resumo();draw();
 // Calibração
 $('vz').innerHTML=nf(s.vaz,2)+'<small> ml/s</small>';$('vzs').textContent='≈ '+nf(s.vaz*60,0)+' ml por minuto · dose máx. '+nf(s.vaz*s.max,1)+' ml';
 if(document.activeElement!==$('vzm'))$('vzm').value=Math.round(s.vaz*1000)/1000;
 if(document.activeElement!==$('capi'))$('capi').value=Math.round(s.cap);
 const cal=on&&s.tipo==3;
 $('calgo').style.display=cal?'none':'';$('calrun').style.display=cal?'':'none';$('calgo').disabled=on;
 if(cal){$('calb').style.width=(100*(1-s.rest/s.dur))+'%';$('calt').textContent='Medindo… '+Math.ceil(s.rest)+' s'}
 // Sistema
 $('wss').textContent=s.ssid||'—';$('wip').textContent=s.ip;$('whn').textContent=s.host||'—';
 $('fw').textContent='v'+s.fw;$('vr').textContent=s.vr>0?'v'+s.vr:'—';$('otam').textContent=s.otam;
 $('otai').disabled=!(s.vr>s.fwn);$('otai').textContent=s.vr>s.fwn?'Atualizar p/ v'+s.vr:'Atualizar';
 if(document.activeElement!==$('aup'))$('aup').checked=!!s.aup;
 if(fwAnt&&fwAnt!==s.fw)toast('Dosadora atualizada para v'+s.fw+' ✓');fwAnt=s.fw;
 const n=s.rssi>=-55?4:s.rssi>=-67?3:s.rssi>=-77?2:1;
 [...$('sig').children].forEach((e,i)=>e.className=i<n?'on':'');$('wrs').textContent=s.rssi+' dBm';
 const man=on&&s.tipo==2;
 $('man').textContent=man?'■ Parar bomba ('+Math.ceil(s.rest)+' s)':'💧 Ligar bomba';$('man').className='btn '+(man?'r':'s');
 $('man').disabled=on&&!man;
 if(document.activeElement!==$('pino'))$('pino').value=s.pino>=0?String(s.pino):'';
}

function resumo(){
 const f=form(),iv=Math.floor(1440/f.doses),per=f.ml/f.doses;
 $('ml').style.setProperty('--p',(f.ml/5)+'%');
 $('sum').innerHTML=f.ml<=0?'Volume zerado: nenhuma dose será feita.':
  '<b>'+f.doses+'</b> '+(f.doses>1?'doses':'dose')+' de <b>'+nf(per,2)+' ml</b>'+(f.doses>1?' a cada <b>'+dur(iv)+'</b>':' por dia')+', começando às <b>'+pad(f.ini)+':00</b>';
 const lim=S?S.vaz*S.max:1e9;
 $('lim').style.display=per>lim?'':'none';
 $('lim').textContent='⚠ Com a vazão atual, cada dose fica limitada a '+nf(lim,1)+' ml ('+(S?S.max:120)+' s de bomba). Aumente o número de doses.';
 $('salvar').disabled=!dirty;
}

function draw(){
 const c=$('cv'),s=S;if(!s||!c.offsetWidth)return;
 const r=window.devicePixelRatio||1,W=c.offsetWidth,H=112;c.width=W*r;c.height=H*r;
 const x=c.getContext('2d');x.scale(r,r);x.clearRect(0,0,W,H);
 const L=16,R=W-16,y=62,X=m=>L+(R-L)*m/1440;
 x.lineCap='round';x.lineWidth=6;x.strokeStyle='#21262d';x.beginPath();x.moveTo(L,y);x.lineTo(R,y);x.stroke();
 if(s.hv){const g=x.createLinearGradient(L,0,R,0);g.addColorStop(0,'#1f6feb');g.addColorStop(1,'#39c5cf');x.strokeStyle=g;x.globalAlpha=.45;x.beginPath();x.moveTo(L,y);x.lineTo(X(s.min),y);x.stroke();x.globalAlpha=1}
 x.font='11px -apple-system,Roboto,sans-serif';x.textAlign='center';
 for(let h=0;h<=24;h+=6){x.fillStyle='#30363d';x.fillRect(X(h*60)-.5,y+8,1,5);x.fillStyle='#8b949e';x.fillText(h+'h',X(h*60),y+27)}
 if(s.hv){const nx=X(s.min);x.fillStyle='#f0a500';x.beginPath();x.moveTo(nx-5,10);x.lineTo(nx+5,10);x.lineTo(nx,17);x.fill();x.fillRect(nx-.75,17,1.5,y-24)}
 const iv=Math.floor(1440/s.doses),rad=s.doses>16?4:s.doses>10?5:6;
 for(let k=0;k<s.doses;k++){
  const m=(s.ini*60+k*iv)%1440,px=X(m),feita=(s.mask>>>k)&1,passou=s.hv&&m<=s.min,prox=s.auto&&s.hv&&m===s.prox;
  x.beginPath();x.arc(px,y,prox?rad+2:rad,0,7);
  if(feita){x.fillStyle='#3fb950';x.fill()}
  else if(passou||!s.auto){x.fillStyle='#484f58';x.fill()}
  else{x.fillStyle='#0d1117';x.fill();x.lineWidth=2;x.strokeStyle='#58a6ff';x.stroke()}
  if(prox){x.shadowColor='#58a6ff';x.shadowBlur=12;x.beginPath();x.arc(px,y,rad+2,0,7);x.strokeStyle='#58a6ff';x.lineWidth=2;x.stroke();x.shadowBlur=0;
   x.fillStyle='#fff';x.font='bold 12px -apple-system,Roboto,sans-serif';x.textAlign=px<L+24?'left':px>R-24?'right':'center';const tw=x.measureText(hm(m)).width,tx=px<L+24?px:px>R-24?px-tw:px-tw/2;x.fillStyle='#161b22';x.fillRect(tx-4,y-31,tw+8,18);x.fillStyle='#fff';x.fillText(hm(m),px,y-17);x.textAlign='center';x.font='11px -apple-system,Roboto,sans-serif'}
 }
}

// Abas
function aba(t){document.querySelectorAll('.tab').forEach(b=>b.classList.toggle('on',b.dataset.t===t));document.querySelectorAll('.pane').forEach(p=>p.classList.toggle('on',p.id==='p-'+t));draw();try{localStorage.aba=t}catch(e){}}
document.querySelectorAll('.tab').forEach(b=>b.onclick=()=>aba(b.dataset.t));
try{if(localStorage.aba&&$('p-'+localStorage.aba))aba(localStorage.aba)}catch(e){}

// Painel
document.querySelectorAll('.chip').forEach(b=>b.onclick=async()=>{const ml=b.dataset.ml;if(await ask('Dosar '+ml+' ml agora?','Dosar'))act({a:'dose',ml})});
$('dx').onclick=async()=>{const v=parseFloat($('mlx').value);if(!(v>0)||v>500)return toast('Digite um volume entre 0,1 e 500 ml',1);if(await ask('Dosar '+nf(v,1)+' ml agora?','Dosar')){await act({a:'dose',ml:v});$('mlx').value=''}};
$('pstop').onclick=()=>act({a:'parar'});
$('encher').onclick=async()=>{if(await ask('Confirmar que o galão foi reabastecido? O nível volta para 100%.','Confirmar'))act({a:'encher'})};

// Agenda
for(let h=0;h<24;h++){const o=document.createElement('option');o.value=h;o.textContent=pad(h)+':00';$('ini').appendChild(o)}
const sujo=()=>{dirty=true;resumo()};
$('auto').onchange=e=>put({auto:e.target.checked?1:0},e.target.checked?'Dosagem automática ligada':'Dosagem automática desligada');
$('ml').oninput=()=>{$('mln').value=$('ml').value;sujo()};
$('mln').oninput=()=>{$('ml').value=$('mln').value;sujo()};
$('dm').onclick=()=>{$('dv').textContent=Math.max(1,form().doses-1);sujo()};
$('dp').onclick=()=>{$('dv').textContent=Math.min(24,form().doses+1);sujo()};
$('ini').onchange=sujo;
$('salvar').onclick=async()=>{const f=form();const j=await put(f,'Programação salva ✓');if(j){dirty=false;render()}};

// Calibração
$('calgo').onclick=async()=>{if(await ask('A bomba vai ligar por 30 segundos. A mangueira está no copo medidor?','Ligar'))act({a:'cal'})};
$('calx').onclick=()=>act({a:'parar'});
$('calok').onclick=async()=>{const v=parseFloat($('calml').value);if(!(v>0))return toast('Digite quantos ml caíram no copo',1);const j=await act({a:'calml',v});if(j&&j.ok)$('calml').value=''};
$('vzok').onclick=()=>{const v=parseFloat($('vzm').value);if(!(v>=0.01&&v<=20))return toast('Vazão entre 0,01 e 20 ml/s',1);put({vaz:v},'Vazão salva ✓')};
$('capok').onclick=()=>{const v=parseFloat($('capi').value);if(!(v>=100&&v<=20000))return toast('Capacidade entre 100 e 20.000 ml',1);put({cap:v},'Capacidade salva ✓')};

// Sistema
PINOS.forEach((p,i)=>{const o=document.createElement('option');o.value=i;o.textContent=p;$('pino').appendChild(o)});
$('man').onclick=()=>act({a:S&&S.bomba&&S.tipo==2?'parar':'manual'});
$('pino').onchange=async e=>{const i=e.target.value;if(await ask('Trocar o pino do relé para '+PINOS[i]+'?','Trocar'))put({pino:i},'Pino alterado para '+PINOS[i]);else render()};
$('esq').onclick=async()=>{if(await ask('A dosadora vai sair do Wi-Fi e criar a rede PrimeReef-Dosadora para configurar de novo. Continuar?','Esquecer'))act({a:'esquecer'})};

// Atualização
$('otac').onclick=async()=>{await act({a:'otacheck'});setTimeout(poll,4000)};
$('otai').onclick=async()=>{if(await ask('Instalar a versão '+S.vr+'? A dosadora baixa o firmware e reinicia (cerca de 1 minuto).','Atualizar'))act({a:'otainst'})};
$('aup').onchange=e=>put({aup:e.target.checked?1:0},e.target.checked?'Atualização automática ligada':'Atualização automática desligada');
$('upgo').onclick=async()=>{const f=$('binf').files[0],pw=$('otap').value;
 if(!f)return toast('Escolha o arquivo .bin',1);if(!pw)return toast('Digite a senha de atualização',1);
 if(!await ask('Enviar '+f.name+' para a dosadora? Ela reinicia no final.','Enviar'))return;
 const x=new XMLHttpRequest(),fd=new FormData();fd.append('bin',f,f.name);
 $('upb').style.display='';$('upf').style.width='0';$('upgo').disabled=true;
 x.upload.onprogress=e=>{if(e.lengthComputable)$('upf').style.width=(e.loaded/e.total*100)+'%'};
 const fim=()=>{$('upgo').disabled=false};
 x.onload=()=>{let j={};try{j=JSON.parse(x.responseText)}catch(e){}toast(j.msg||'Erro no envio',!j.ok);if(!j.ok)$('upb').style.display='none';fim()};
 x.onerror=()=>{toast('Falha no envio',1);$('upb').style.display='none';fim()};
 x.open('POST','/d/upload?senha='+encodeURIComponent(pw));x.send(fd)};

window.onresize=draw;
document.addEventListener('visibilitychange',()=>{if(!document.hidden)poll()});
poll();
</script></body></html>)HTML";
