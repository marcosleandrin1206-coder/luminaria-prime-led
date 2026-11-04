// Exemplo: dosadora de 3 canais usando o Protocolo Prime Reef v1.
// O app Prime Reef encontra esta placa na rede e monta a tela sozinho
// (leituras, botões de dosagem, ajustes de ml/dia e calibração).
//
// Hardware de exemplo: ESP32 + 3 bombas peristálticas acionadas por MOSFET/relé.

#include <WiFi.h>
#include <WebServer.h>
#include <Preferences.h>
#include <PrimeReefDevice.h>
#include <time.h>

// Troque pelos dados da sua rede (num produto final, use provisionamento por AP como na luminária).
const char* WIFI_SSID = "MinhaRede";
const char* WIFI_PASS = "MinhaSenha";
#define FW_VERSION "1"

struct Canal {
  const char* id;
  const char* nome;
  uint8_t pino;
  float mlDia;        // volume diário programado
  float vazao;        // ml por segundo (calibração)
  float dosadoHoje;   // ml
  float reservatorio; // ml restantes
  float capacidade;   // ml do galão cheio
  uint32_t desligarEm = 0; // millis() para desligar a bomba (0 = parada)
};

Canal canais[] = {
  {"ca", "Cálcio",       25, 20, 1.0, 0, 2000, 2000},
  {"kh", "Alcalinidade", 26, 20, 1.0, 0, 2000, 2000},
  {"mg", "Magnésio",     27,  5, 1.0, 0, 2000, 2000},
};
const int N = sizeof(canais) / sizeof(canais[0]);

bool automatico = true;
String modo = "Hora em hora";
int ultimaHoraDosada = -1;
int ultimoDia = -1;

WebServer server(80);
PrimeReefDevice reef("dosadora", "Dosadora 3 canais", FW_VERSION);
Preferences prefs;

void salvar() {
  prefs.begin("dosadora", false);
  for (int i = 0; i < N; i++) {
    prefs.putFloat((String(canais[i].id) + "_ml").c_str(), canais[i].mlDia);
    prefs.putFloat((String(canais[i].id) + "_vz").c_str(), canais[i].vazao);
    prefs.putFloat((String(canais[i].id) + "_rs").c_str(), canais[i].reservatorio);
  }
  prefs.putBool("auto", automatico);
  prefs.putString("modo", modo);
  prefs.end();
}

void carregar() {
  prefs.begin("dosadora", true);
  for (int i = 0; i < N; i++) {
    canais[i].mlDia = prefs.getFloat((String(canais[i].id) + "_ml").c_str(), canais[i].mlDia);
    canais[i].vazao = prefs.getFloat((String(canais[i].id) + "_vz").c_str(), canais[i].vazao);
    canais[i].reservatorio = prefs.getFloat((String(canais[i].id) + "_rs").c_str(), canais[i].reservatorio);
  }
  automatico = prefs.getBool("auto", automatico);
  modo = prefs.getString("modo", modo);
  prefs.end();
}

// Liga a bomba pelo tempo necessário para dosar `ml` (sem bloquear o loop).
void dosar(Canal& c, float ml) {
  if (ml <= 0 || c.vazao <= 0) return;
  digitalWrite(c.pino, HIGH);
  c.desligarEm = millis() + (uint32_t)(ml / c.vazao * 1000.0f);
  c.dosadoHoje += ml;
  c.reservatorio = max(0.0f, c.reservatorio - ml);
}

void atualizarAlertas() {
  for (int i = 0; i < N; i++) {
    float pct = canais[i].reservatorio / canais[i].capacidade * 100;
    if (pct < 10) reef.setAlert(canais[i].id, PrimeReefDevice::Level::Alarm, String(canais[i].nome) + ": reservatório quase vazio");
    else if (pct < 20) reef.setAlert(canais[i].id, PrimeReefDevice::Level::Warn, String(canais[i].nome) + ": reservatório abaixo de 20%");
    else reef.clearAlert(canais[i].id);
  }
}

void setup() {
  Serial.begin(115200);
  carregar();
  for (int i = 0; i < N; i++) {
    pinMode(canais[i].pino, OUTPUT);
    digitalWrite(canais[i].pino, LOW);
  }

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  while (WiFi.status() != WL_CONNECTED) delay(300);
  Serial.println(WiFi.localIP());
  configTzTime("<-03>3", "pool.ntp.org");  // horário de Brasília

  // ---- Leituras ----
  for (int i = 0; i < N; i++) {
    Canal* c = &canais[i];
    reef.addSensor((String(c->id) + "_hoje").c_str(), (String(c->nome) + " hoje").c_str(), "ml",
                   [c] { return c->dosadoHoje; }, 1);
  }
  for (int i = 0; i < N; i++) {
    Canal* c = &canais[i];
    reef.addSensor((String(c->id) + "_res").c_str(), (String(c->nome) + " (galão)").c_str(), "%",
                   [c] { return c->reservatorio / c->capacidade * 100; }, 0,
                   [](float pct) {
                     return pct < 10 ? PrimeReefDevice::Level::Alarm
                          : pct < 20 ? PrimeReefDevice::Level::Warn
                                     : PrimeReefDevice::Level::Ok;
                   });
  }

  // ---- Controles ----
  reef.addSwitch("auto", "Dosagem automática", [] { return automatico; },
                 [](bool v) { automatico = v; salvar(); });
  reef.addSelect("modo", "Distribuição", {"Hora em hora", "Uma vez ao dia"},
                 [] { return modo; }, [](const String& v) { modo = v; salvar(); });

  for (int i = 0; i < N; i++) {
    Canal* c = &canais[i];
    String id = c->id;
    reef.addSlider((id + "_ml").c_str(), (String(c->nome) + " por dia").c_str(), 0, 200, 1, "ml",
                   [c] { return c->mlDia; }, [c](float v) { c->mlDia = v; salvar(); });
    reef.addButton((id + "_dose").c_str(), (String("Dosar 1 ml de ") + c->nome).c_str(),
                   [c] { dosar(*c, 1); }, "Dosar 1 ml agora?");
    reef.addNumber((id + "_cal").c_str(), (String("Calibração ") + c->nome).c_str(), 0.1, 10, 0.01, "ml/s",
                   [c] { return c->vazao; }, [c](float v) { c->vazao = v; salvar(); });
    reef.addButton((id + "_enche").c_str(), (String("Galão de ") + c->nome + " cheio").c_str(),
                   [c] { c->reservatorio = c->capacidade; salvar(); atualizarAlertas(); },
                   "Confirmar que o galão foi reabastecido?");
  }

  reef.begin(server);  // /api/info, /api/state, /api/set + mDNS
  server.on("/", [] {
    server.send(200, "text/html",
                "<meta name=viewport content='width=device-width'><body style='background:#0d1117;color:#c9d1d9;"
                "font-family:sans-serif;text-align:center;padding-top:40px'><h2>Prime Reef Dosadora</h2>"
                "<p>Use o app Prime Reef para controlar este aparelho.</p></body>");
  });
  server.begin();
  atualizarAlertas();
}

void loop() {
  server.handleClient();
  reef.loop();

  // Desliga bombas cujo tempo terminou.
  for (int i = 0; i < N; i++) {
    if (canais[i].desligarEm && (int32_t)(millis() - canais[i].desligarEm) >= 0) {
      digitalWrite(canais[i].pino, LOW);
      canais[i].desligarEm = 0;
      salvar();
      atualizarAlertas();
    }
  }

  // Agenda automática.
  struct tm t;
  if (!automatico || !getLocalTime(&t, 0)) return;
  if (t.tm_yday != ultimoDia) {
    ultimoDia = t.tm_yday;
    for (int i = 0; i < N; i++) canais[i].dosadoHoje = 0;
  }
  if (t.tm_hour != ultimaHoraDosada) {
    ultimaHoraDosada = t.tm_hour;
    bool umaVez = (modo == "Uma vez ao dia");
    if (umaVez && t.tm_hour != 9) return;
    for (int i = 0; i < N; i++) dosar(canais[i], umaVez ? canais[i].mlDia : canais[i].mlDia / 24.0f);
  }
}
