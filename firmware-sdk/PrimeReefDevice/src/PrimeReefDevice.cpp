#include "PrimeReefDevice.h"

#if defined(ESP8266)
#include <ESP8266WiFi.h>
#include <ESP8266mDNS.h>
#else
#include <ESPmDNS.h>
#include <WiFi.h>
#endif

PrimeReefDevice::PrimeReefDevice(const char* type, const char* model, const char* fwVersion)
    : _type(type), _model(model), _fw(fwVersion), _name(model) {}

// ---------------- Cadastro ----------------

void PrimeReefDevice::addSensor(const char* id, const char* label, const char* unit, NumGetter get,
                                uint8_t decimals, LevelFn levelFn) {
  Item it;
  it.kind = Kind::Sensor;
  it.id = id;
  it.label = label;
  it.unit = unit ? unit : "";
  it.numGet = get;
  it.decimals = decimals;
  it.levelFn = levelFn;
  _items.push_back(it);
}

void PrimeReefDevice::addTextSensor(const char* id, const char* label, TextGetter get,
                                    std::function<Level()> levelFn) {
  Item it;
  it.kind = Kind::TextSensor;
  it.id = id;
  it.label = label;
  it.textGet = get;
  it.textLevelFn = levelFn;
  _items.push_back(it);
}

void PrimeReefDevice::addSwitch(const char* id, const char* label, BoolGetter get, BoolSetter set) {
  Item it;
  it.kind = Kind::Switch;
  it.id = id;
  it.label = label;
  it.boolGet = get;
  it.boolSet = set;
  _items.push_back(it);
}

void PrimeReefDevice::addSlider(const char* id, const char* label, float min, float max, float step,
                                const char* unit, NumGetter get, NumSetter set) {
  Item it;
  it.kind = Kind::Slider;
  it.id = id;
  it.label = label;
  it.min = min;
  it.max = max;
  it.step = step;
  it.unit = unit ? unit : "";
  it.decimals = decimalsFor(step);
  it.numGet = get;
  it.numSet = set;
  _items.push_back(it);
}

void PrimeReefDevice::addNumber(const char* id, const char* label, float min, float max, float step,
                                const char* unit, NumGetter get, NumSetter set) {
  addSlider(id, label, min, max, step, unit, get, set);
  _items.back().kind = Kind::Number;
}

void PrimeReefDevice::addSelect(const char* id, const char* label, std::vector<String> options,
                                TextGetter get, TextSetter set) {
  Item it;
  it.kind = Kind::Select;
  it.id = id;
  it.label = label;
  it.options = options;
  it.textGet = get;
  it.textSet = set;
  _items.push_back(it);
}

void PrimeReefDevice::addButton(const char* id, const char* label, Action action, const char* confirm) {
  Item it;
  it.kind = Kind::Button;
  it.id = id;
  it.label = label;
  it.action = action;
  it.confirm = confirm ? confirm : "";
  _items.push_back(it);
}

void PrimeReefDevice::setAlert(const char* key, Level level, const String& message) {
  for (auto& a : _alerts) {
    if (a.key == key) {
      a.level = level;
      a.message = message;
      return;
    }
  }
  _alerts.push_back({String(key), level, message});
}

void PrimeReefDevice::clearAlert(const char* key) {
  for (size_t i = 0; i < _alerts.size(); i++) {
    if (_alerts[i].key == key) {
      _alerts.erase(_alerts.begin() + i);
      return;
    }
  }
}

// ---------------- Rede ----------------

String PrimeReefDevice::deviceId() const {
  uint8_t mac[6];
  WiFi.macAddress(mac);
  char buf[16];
  snprintf(buf, sizeof(buf), "-%02x%02x%02x", mac[3], mac[4], mac[5]);
  return _type + buf;
}

void PrimeReefDevice::loop() {
#if defined(ESP8266)
  MDNS.update();
#endif
}

void PrimeReefDevice::begin(PrimeReefWebServer& server, bool startMdns) {
  _server = &server;
  server.on("/api/info", HTTP_GET, [this]() { handleInfo(); });
  server.on("/api/state", HTTP_GET, [this]() { handleState(); });
  server.on("/api/set", HTTP_GET, [this]() { handleSet(); });

  if (startMdns) MDNS.begin(deviceId().c_str());
  MDNS.addService("primereef", "tcp", 80);
  MDNS.addServiceTxt("primereef", "tcp", "type", _type.c_str());
  MDNS.addServiceTxt("primereef", "tcp", "api", "1");
  MDNS.addServiceTxt("primereef", "tcp", "id", deviceId().c_str());
  MDNS.addServiceTxt("primereef", "tcp", "fw", _fw.c_str());
}

void PrimeReefDevice::sendJson(int code, const String& body) {
  _server->sendHeader("Access-Control-Allow-Origin", "*");
  _server->sendHeader("Cache-Control", "no-store");
  _server->send(code, "application/json", body);
}

void PrimeReefDevice::handleInfo() { sendJson(200, infoJson()); }

void PrimeReefDevice::handleState() { sendJson(200, stateJson()); }

void PrimeReefDevice::handleSet() {
  String id = _server->arg("id");
  String v = _server->arg("v");
  Item* it = find(id);
  if (!it) {
    sendJson(400, "{\"error\":\"Controle desconhecido: " + esc(id) + "\"}");
    return;
  }
  switch (it->kind) {
    case Kind::Switch:
      it->boolSet(v == "1" || v == "true" || v == "on");
      break;
    case Kind::Slider:
    case Kind::Number: {
      float f = v.toFloat();
      if (v.length() == 0 || f < it->min || f > it->max) {
        sendJson(400, "{\"error\":\"Valor fora da faixa (" + num(it->min, it->decimals) + " a " +
                          num(it->max, it->decimals) + ")\"}");
        return;
      }
      it->numSet(f);
      break;
    }
    case Kind::Select: {
      bool ok = false;
      for (auto& o : it->options) ok = ok || (o == v);
      if (!ok) {
        sendJson(400, "{\"error\":\"Opção inválida\"}");
        return;
      }
      it->textSet(v);
      break;
    }
    case Kind::Button:
      it->action();
      break;
    default:
      sendJson(400, "{\"error\":\"Este item é somente leitura\"}");
      return;
  }
  sendJson(200, stateJson());
}

PrimeReefDevice::Item* PrimeReefDevice::find(const String& id) {
  for (auto& it : _items)
    if (it.id == id) return &it;
  return nullptr;
}

// ---------------- JSON ----------------

String PrimeReefDevice::infoJson() const {
  String s = "{\"api\":1,\"id\":\"" + esc(deviceId()) + "\",\"type\":\"" + esc(_type) +
             "\",\"model\":\"" + esc(_model) + "\",\"name\":\"" + esc(_name) + "\",\"fw\":\"" +
             esc(_fw) + "\"}";
  return s;
}

String PrimeReefDevice::stateJson() const {
  String sensors, controls, alerts;
  for (const auto& it : _items) {
    String base = "{\"id\":\"" + esc(it.id) + "\",\"label\":\"" + esc(it.label) + "\"";
    switch (it.kind) {
      case Kind::Sensor: {
        float v = it.numGet();
        String e = base + ",\"value\":" + num(v, it.decimals);
        if (it.unit.length()) e += ",\"unit\":\"" + esc(it.unit) + "\"";
        if (it.levelFn) e += ",\"status\":\"" + String(levelName(it.levelFn(v))) + "\"";
        sensors += (sensors.length() ? "," : "") + e + "}";
        break;
      }
      case Kind::TextSensor: {
        String e = base + ",\"value\":\"" + esc(it.textGet()) + "\"";
        if (it.textLevelFn) e += ",\"status\":\"" + String(levelName(it.textLevelFn())) + "\"";
        sensors += (sensors.length() ? "," : "") + e + "}";
        break;
      }
      case Kind::Switch:
        controls += (controls.length() ? "," : "") + base + ",\"type\":\"switch\",\"value\":" +
                    (it.boolGet() ? "true" : "false") + "}";
        break;
      case Kind::Slider:
      case Kind::Number: {
        String e = base + ",\"type\":\"" + (it.kind == Kind::Slider ? "slider" : "number") +
                   "\",\"value\":" + num(it.numGet(), it.decimals) + ",\"min\":" + num(it.min, it.decimals) +
                   ",\"max\":" + num(it.max, it.decimals) + ",\"step\":" + num(it.step, 3);
        if (it.unit.length()) e += ",\"unit\":\"" + esc(it.unit) + "\"";
        controls += (controls.length() ? "," : "") + e + "}";
        break;
      }
      case Kind::Select: {
        String opts;
        for (const auto& o : it.options) opts += (opts.length() ? ",\"" : "\"") + esc(o) + "\"";
        controls += (controls.length() ? "," : "") + base + ",\"type\":\"select\",\"value\":\"" +
                    esc(it.textGet()) + "\",\"options\":[" + opts + "]}";
        break;
      }
      case Kind::Button: {
        String e = base + ",\"type\":\"button\"";
        if (it.confirm.length()) e += ",\"confirm\":\"" + esc(it.confirm) + "\"";
        controls += (controls.length() ? "," : "") + e + "}";
        break;
      }
    }
  }
  for (const auto& a : _alerts) {
    alerts += (alerts.length() ? "," : "") + String("{\"level\":\"") + levelName(a.level) +
              "\",\"msg\":\"" + esc(a.message) + "\"}";
  }
  return "{\"sensors\":[" + sensors + "],\"controls\":[" + controls + "],\"alerts\":[" + alerts + "]}";
}

String PrimeReefDevice::esc(const String& s) {
  String o;
  o.reserve(s.length() + 4);
  for (size_t i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '"' || c == '\\') {
      o += '\\';
      o += c;
    } else if (c == '\n') {
      o += "\\n";
    } else if ((uint8_t)c < 0x20) {
      o += ' ';
    } else {
      o += c;
    }
  }
  return o;
}

String PrimeReefDevice::num(float v, uint8_t decimals) {
  if (isnan(v) || isinf(v)) return "null";
  String s(v, (unsigned int)decimals);
  if (s.indexOf('.') >= 0) {  // remove zeros à direita: 1.500 -> 1.5, 2.000 -> 2
    while (s.endsWith("0")) s.remove(s.length() - 1);
    if (s.endsWith(".")) s.remove(s.length() - 1);
  }
  return s;
}

uint8_t PrimeReefDevice::decimalsFor(float step) {
  if (step >= 1) return 0;
  if (step >= 0.1f) return 1;
  if (step >= 0.01f) return 2;
  return 3;
}

const char* PrimeReefDevice::levelName(Level l) {
  switch (l) {
    case Level::Warn: return "warn";
    case Level::Alarm: return "alarm";
    case Level::Info: return "info";
    default: return "ok";
  }
}
