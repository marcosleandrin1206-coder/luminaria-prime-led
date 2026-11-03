// PrimeReefDevice — Protocolo Prime Reef v1 (ver docs/PROTOCOLO.md)
//
// Uso básico:
//   WebServer server(80);
//   PrimeReefDevice reef("dosadora", "Dosadora 4 canais", "1");
//   reef.addSensor("temp", "Temperatura", "°C", [] { return lerTemperatura(); });
//   reef.addSwitch("auto", "Dosagem automática", [] { return autoOn; }, [](bool v) { autoOn = v; });
//   ... depois de conectar no Wi-Fi:
//   reef.begin(server);   // registra /api/info, /api/state, /api/set e o mDNS
//   server.begin();
//   loop(): server.handleClient();
#pragma once

#include <Arduino.h>
#include <WebServer.h>
#include <functional>
#include <vector>

class PrimeReefDevice {
 public:
  enum class Level { Ok, Info, Warn, Alarm };

  using NumGetter = std::function<float()>;
  using NumSetter = std::function<void(float)>;
  using BoolGetter = std::function<bool()>;
  using BoolSetter = std::function<void(bool)>;
  using TextGetter = std::function<String()>;
  using TextSetter = std::function<void(const String&)>;
  using Action = std::function<void()>;
  using LevelFn = std::function<Level(float)>;

  // type: "luminaria", "dosadora", "bomba", "ato", "controlador", "sensor"...
  PrimeReefDevice(const char* type, const char* model, const char* fwVersion);

  // Nome amigável exibido no app (padrão: o modelo). Pode ser trocado em tempo de execução.
  void setName(const String& name) { _name = name; }

  // ----- Sensores (somente leitura) -----
  // levelFn opcional: classifica o valor como ok/warn/alarm (o app colore a leitura).
  void addSensor(const char* id, const char* label, const char* unit, NumGetter get,
                 uint8_t decimals = 1, LevelFn levelFn = nullptr);
  void addTextSensor(const char* id, const char* label, TextGetter get,
                     std::function<Level()> levelFn = nullptr);

  // ----- Controles -----
  void addSwitch(const char* id, const char* label, BoolGetter get, BoolSetter set);
  void addSlider(const char* id, const char* label, float min, float max, float step,
                 const char* unit, NumGetter get, NumSetter set);
  void addNumber(const char* id, const char* label, float min, float max, float step,
                 const char* unit, NumGetter get, NumSetter set);
  void addSelect(const char* id, const char* label, std::vector<String> options,
                 TextGetter get, TextSetter set);
  // confirm != nullptr: o app pergunta antes de executar.
  void addButton(const char* id, const char* label, Action action, const char* confirm = nullptr);

  // ----- Alertas (exibidos no topo do app) -----
  void setAlert(const char* key, Level level, const String& message);
  void clearAlert(const char* key);

  // Registra as rotas no servidor e anuncia o serviço mDNS "_primereef._tcp".
  // startMdns=false se o firmware já chamou MDNS.begin() por conta própria.
  void begin(WebServer& server, bool startMdns = true);

  // "<tipo>-xxxxxx" (últimos 3 bytes do MAC). Também é o hostname mDNS.
  String deviceId() const;

  String infoJson() const;
  String stateJson() const;

 private:
  enum class Kind { Sensor, TextSensor, Switch, Slider, Number, Select, Button };

  struct Item {
    Kind kind;
    String id, label, unit, confirm;
    float min = 0, max = 100, step = 1;
    uint8_t decimals = 1;
    std::vector<String> options;
    NumGetter numGet;
    NumSetter numSet;
    BoolGetter boolGet;
    BoolSetter boolSet;
    TextGetter textGet;
    TextSetter textSet;
    Action action;
    LevelFn levelFn;
    std::function<Level()> textLevelFn;
  };

  struct AlertEntry {
    String key;
    Level level;
    String message;
  };

  String _type, _model, _fw, _name;
  std::vector<Item> _items;
  std::vector<AlertEntry> _alerts;
  WebServer* _server = nullptr;

  Item* find(const String& id);
  void handleInfo();
  void handleState();
  void handleSet();
  void sendJson(int code, const String& body);

  static String esc(const String& s);
  static String num(float v, uint8_t decimals);
  static uint8_t decimalsFor(float step);
  static const char* levelName(Level l);
};
