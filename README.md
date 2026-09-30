# p1mqtt

Bridges the P1 meter HTTP push from [Domoticz](https://www.domoticz.com/) (or a DSMR reader that speaks
the same `svalue` format) into Home Assistant via MQTT, including **MQTT discovery**.

It listens for a request such as:

```
GET http://192.168.1.120:8267/p1meter.php?type=command&param=udevice&idx=294&nvalue=0&svalue=mEVLT=10584731;mEVHT=10414854;meOLT=3255478;mEOHT=7207975;mEAV=418;mEAT=0
```

and republishes the values to MQTT. It replies with the Domoticz-compatible body
`{"status":"OK","title":"Update Device","published":8,"unchanged":0}` so the sender keeps working
unmodified, and it sends **HTTP 200** even when only part of the reading is present.

## Quick start

```sh
mvn clean package
java -jar target/p1mqtt.jar --mqtt.dryRun=true
```

In dry-run mode nothing is sent to a broker: every message that *would* be published is logged instead.
Point a browser or curl at the URL printed at startup, e.g.:

```sh
curl "http://127.0.0.1:8267/p1meter.php?type=command&param=udevice&idx=294&nvalue=0&svalue=mEVLT=10584731;mEVHT=10414854;meOLT=3255478;mEOHT=7207975;mEAV=418;mEAT=0"
```

Then configure your real broker:

```sh
java -jar target/p1mqtt.jar \
  --mqtt.server=homeassistant.local \
  --mqtt.username=p1mqtt \
  --mqtt.password='secret'
```

## Configuration

Every setting can be given in four ways, highest precedence first:

1. command line: `--mqtt.server=ha.local`
2. environment variable: `P1MQTT_MQTT_SERVER=ha.local` (dots become underscores, camelCase becomes `_`)
3. a properties file: `--config=/etc/p1mqtt/application.properties`
4. built-in defaults in `src/main/resources/application.properties`

| key | default | meaning |
| --- | --- | --- |
| `server.bind` | *(all interfaces)* | address to bind, e.g. `192.168.1.120` |
| `server.port` | `8267` | HTTP listen port |
| `server.path` | `/p1meter.php` | expected path (any path is still accepted, but this is shown/logged) |
| `server.token` | *(empty)* | if set, calls must add `&token=...` (else HTTP 401) |
| `server.threads` | `4` | HTTP worker threads |
| `server.maxBodyBytes` | `16384` | maximum POST body size |
| `server.logRequests` | `true` | log each request |
| `mqtt.enabled` | `true` | set `false` to disable publishing entirely (logs instead) |
| `mqtt.server` | *(empty)* | **broker host, required unless dry-run** |
| `mqtt.port` | `1883` | broker port |
| `mqtt.username` / `mqtt.password` | *(empty)* | broker credentials (optional) |
| `mqtt.clientId` | `p1mqtt` | MQTT client id |
| `mqtt.topicPrefix` | `p1meter` | prefix for state/availability topics |
| `mqtt.discoveryPrefix` | `homeassistant` | Home Assistant discovery prefix |
| `mqtt.qos` | `1` | `0`, `1` or `2` |
| `mqtt.retain` | `true` | retain discovery + availability messages |
| `mqtt.retainState` | `false` | retain state messages too |
| `mqtt.ssl` | `false` | use TLS with the JVM default trust store |
| `mqtt.keepAliveSeconds` | `60` | keep-alive interval |
| `mqtt.dryRun` | `false` | log messages instead of publishing |
| `ha.deviceName` | `P1 Meter` | device name shown in Home Assistant |
| `ha.deviceId` | `p1_meter` | device id, also used in entity ids |
| `ha.deviceManufacturer` / `ha.deviceModel` | *(empty)* | optional device metadata |
| `ha.expireAfterSeconds` | `0` | if > 0, add `expire_after` to entities (mark them unavailable when updates stop) |
| `ha.energyScale` | `0.001` | scale applied to the raw counters (Wh → kWh) |
| `ha.includeDiagnosticSensors` | `true` | add the per-tariff diagnostic counters |
| `publish.republishIntervalSeconds` | `60` | re-send the last known state if no reading arrives within this period |
| `publish.jsonState` | `true` | also publish all raw values as one JSON document |
| `log.level` | `INFO` | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |

`--help` prints every known key.

## Entities

The P1 payload contains six keys. They are mapped like this:

| raw key | entity | unit | device class | state class |
| --- | --- | --- | --- | --- |
| `mEAV` | `power_usage` | W | power | measurement |
| `mEAT` | `power_returned` | W | power | measurement |
| `mEVLT` + `mEVHT` | `energy_imported` | kWh | energy | total_increasing |
| `meOLT` + `meOHT` | `energy_exported` | kWh | energy | total_increasing |
| `mEVLT` | `energy_imported_low` | kWh | energy | measurement (diagnostic) |
| `mEVHT` | `energy_imported_high` | kWh | energy | measurement (diagnostic) |
| `meOLT` | `energy_exported_low` | kWh | energy | measurement (diagnostic) |
| `meOHT` | `energy_exported_high` | kWh | energy | measurement (diagnostic) |

The four counters are raw values in Wh, so they are multiplied by `ha.energyScale` before being
published in kWh. The `total_increasing` totals are what you want for the Home Assistant energy
dashboard; the per-tariff sensors are marked *diagnostic* and can be disabled with
`ha.includeDiagnosticSensors=false`.

### Topics

| purpose | topic | retain |
| --- | --- | --- |
| discovery | `homeassistant/sensor/p1_meter_<entity>/config` | yes |
| state | `p1meter/<entity>/state` | `mqtt.retainState` |
| all raw values | `p1meter/state` (JSON) | `mqtt.retainState` |
| availability | `p1meter/availability` (`online` / `offline` via MQTT LWT) | yes |

State messages are only sent when a value actually changes; unchanged values are counted in the
response body. If no reading arrives for `publish.republishIntervalSeconds`, the last known values are
republished so `total_increasing` sensors do not look stale.

## Monitoring

* `GET /health` – JSON status: `status` (`ok` / `degraded`), counters, last reading, last published
  sensor values, MQTT connection state.
* `GET /` – small HTML status page (auto-refreshes).
* `GET /anything-else` – only accepts the reading methods (`GET`, `POST`) and requires `svalue`.

`status` is `degraded` while the broker is unreachable. The bridge keeps running and reconnects
automatically; discovery and availability are re-published after every reconnect.

## Run as a service

```sh
sudo useradd --system --no-create-home --shell /usr/sbin/nologin p1mqtt
sudo mkdir -p /opt/p1mqtt /etc/p1mqtt
sudo cp target/p1mqtt.jar /opt/p1mqtt/
sudo cp systemd/p1mqtt.service /etc/systemd/system/
sudo install -m 640 -o root -g p1mqtt /dev/null /etc/p1mqtt/application.properties  # then edit with broker settings
sudo systemctl daemon-reload
sudo systemctl enable --now p1mqtt
```

The unit in `systemd/p1mqtt.service` reads `/etc/p1mqtt/application.properties`. Keep the broker
password out of the unit file and out of version control.

## Run as a Docker container

The image is a JRE plus the one shaded `p1mqtt.jar`; jackson, the HiveMQ/Netty client and logback
are compiled into the jar, so nothing is resolved at run time.

```sh
cp .env.example .env      # set MQTT_SERVER (plus credentials if the broker needs them)
docker compose up -d --build
docker compose logs -f
```

`compose.yaml` uses `restart: unless-stopped`, so the container comes back after a reboot or a
crash and only stays down when you stop it deliberately (`docker compose stop`). Without compose:

```sh
docker build -t p1mqtt:1.0.0 .
docker run -d --name p1mqtt --restart unless-stopped \
  -p 8267:8267 \
  -e P1MQTT_MQTT_SERVER=homeassistant.local \
  -e P1MQTT_MQTT_PORT=1883 \
  -e P1MQTT_MQTT_USERNAME=p1mqtt \
  -e P1MQTT_MQTT_PASSWORD='secret' \
  p1mqtt:1.0.0
```

What to know:

* **8267** is the only port the container listens on; compose publishes it as `8267:8267`, and
  `P1MQTT_HTTP_PORT` in `.env` changes only the host side. Leave `server.bind` empty (or
  `0.0.0.0`) so the port is reachable from outside the container.
* **The MQTT port is not opened by this container.** p1mqtt is an MQTT *client*: it opens an
  outbound connection to the broker on `MQTT_PORT` (1883 by default) and never accepts MQTT
  connections itself, so publishing it here would forward nothing. Publish the port on the broker
  (`ports: - "1883:1883"` in its compose file) for the hosts that need to reach it.
* Every key in the configuration table above can be set as an environment variable by prefixing
  it with `P1MQTT_`, upper-casing it and turning `.` and camelCase into `_`:
  `mqtt.clientId` → `P1MQTT_MQTT_CLIENT_ID`. Environment variables take precedence over a
  properties file; the optional file mount is in `compose.yaml`, commented out.
* `JAVA_TOOL_OPTIONS` goes straight to the JVM if you want to cap the heap or change the GC.
* The image healthcheck polls `GET /health`, which answers 200 while the HTTP server is up (an
  unreachable broker shows up as `"status":"degraded"` in the body, not as an unhealthy
  container). It targets `127.0.0.1:8267`, so override it in compose if you set `server.bind` to a
  specific LAN address.
* Logs go to stdout, so `docker compose logs` shows everything; compose rotates them at 3 × 10 MB.
* `docker build --build-arg SKIP_TESTS=false .` includes the test suite in the image build.


## Notes and caveats

* **Point the sender at this host.** If your meter currently posts to another Domoticz host, change
  its URL to `http://<this-host>:8267/p1meter.php` (the path is accepted either way).
* **`meOLT`/`meOHT` upper bound.** The sample payload has `mEOHT` (7207975) larger than `meOLT`
  (3255478), which is the opposite of what most DSMR meters report for the export registers. The
  values are published exactly as received (only scaled); verify against your meter if the export
  totals look off.
* Messages are **not** persisted across restarts; `total_increasing` sensors in Home Assistant keep
  their history, so a restart does not create false energy spikes.
* TLS uses the JVM default trust store; there is no option for custom certificates yet.

## Development

```sh
mvn test          # unit + integration tests, including a real MQTT 5 handshake
mvn clean package # builds target/p1mqtt.jar (shaded, runnable)
```

The test suite spins up a minimal in-process MQTT 5 broker (`TestMqttBroker`) so the real
[HiveMQ client](https://github.com/hivemq/hivemq-mqtt-client) is exercised end to end without any
external dependency.
