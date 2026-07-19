# Hub 与 LabRelay 简明安装

按顺序操作：先安装 Hub，再安装锐捷路由器上的 LabRelay。


## 一、安装 Hub

在 Docker 管理页面新建 Compose 项目，粘贴下面的配置。只需要修改 5 处：

1. 两处 `192.168.x.x` 改为 Docker 宿主机的局域网 IPv4。
2. 两处 `请改成自己的MQTT密码` 改成同一个强密码。
3. `请改成APP令牌` 改成一条强随机 `APP_TOKEN`。
4. `请改成HOOK令牌` 改成另一条强随机 `HOOK_TOKEN`。
5. 如果已有 HTTPS/WSS 反向代理，把 `HUB_ADVERTISE_URL` 和 `MQTT_PUBLIC_URL` 换成自己的公网地址。
先在 Linux、NAS 或 Docker 宿主机终端生成两条不同的随机 Token：

```sh
printf 'APP_TOKEN='; openssl rand -hex 32
printf 'HOOK_TOKEN='; openssl rand -hex 32
```

复制两行输出并妥善保存。`APP_TOKEN` 与 `HOOK_TOKEN` 不要相同，也不要继续使用示例占位值。

```yaml
services:
  labprobe-mqtt:
    image: eclipse-mosquitto:2
    container_name: labprobe-mqtt
    user: "0:0"
    network_mode: host
    restart: unless-stopped
    environment:
      MQTT_USERNAME: labprobe
      MQTT_PASSWORD: "请改成自己的MQTT密码"
      MQTT_LOCAL_BIND: 127.0.0.1
    entrypoint:
      - /bin/sh
      - -ec
      - |
        mkdir -p /mosquitto/config/runtime /mosquitto/data
        rm -f /mosquitto/config/runtime/passwords /mosquitto/config/runtime/passwords.tmp
        mosquitto_passwd -b -c /mosquitto/config/runtime/passwords "$$MQTT_USERNAME" "$$MQTT_PASSWORD"
        {
          echo "user mosquitto"
          echo "persistence true"
          echo "persistence_location /mosquitto/data/"
          echo "log_dest stdout"
          echo "log_timestamp true"
          echo "connection_messages true"
          echo "log_type error"
          echo "log_type warning"
          echo "log_type notice"
          echo "log_type information"
          echo "listener 1883 $$MQTT_LOCAL_BIND"
          echo "protocol mqtt"
          echo "allow_anonymous false"
          echo "password_file /mosquitto/config/runtime/passwords"
          echo "listener 9001 0.0.0.0"
          echo "protocol websockets"
          echo "allow_anonymous false"
          echo "password_file /mosquitto/config/runtime/passwords"
        } > /mosquitto/config/runtime/mosquitto.conf
        chown -R 1883:1883 /mosquitto/config/runtime /mosquitto/data
        chmod 600 /mosquitto/config/runtime/passwords
        chmod 644 /mosquitto/config/runtime/mosquitto.conf
        exec mosquitto -c /mosquitto/config/runtime/mosquitto.conf
    volumes:
      - ./config/mqtt:/mosquitto/config/runtime
      - ./data/mqtt:/mosquitto/data
    healthcheck:
      test: ["CMD-SHELL", "mosquitto_pub -h 127.0.0.1 -p 1883 -u \"$$MQTT_USERNAME\" -P \"$$MQTT_PASSWORD\" -t labprobe/health -m ping -q 1"]
      interval: 20s
      timeout: 5s
      retries: 5

  labprobe-hub:
    image: onlychallgener/labprobe-hub:latest
    container_name: labprobe-hub
    network_mode: host
    restart: unless-stopped
    depends_on:
      labprobe-mqtt:
        condition: service_healthy
    environment:
      PORT: 58443
      TZ: Asia/Shanghai
      CONFIG_PATH: ./config/config.yaml
      CONFIG_DIR: ./config
      DATA_DIR: ./data
      BACKUPS_DIR: ./backups
      LOGS_DIR: ./logs
      HUB_NAME: LabProbe Hub
      HUB_ADVERTISE_URL: http://192.168.x.x:58443
      PRIMARY_ROUTER_NAME: ""
      APP_TOKEN: "请改成APP令牌"
      HOOK_TOKEN: "请改成HOOK令牌"
      MQTT_PUBLIC_URL: ws://192.168.x.x:9001/mqtt
      MQTT_INTERNAL_HOST: 127.0.0.1
      MQTT_INTERNAL_PORT: 1883
      MQTT_USERNAME: labprobe
      MQTT_PASSWORD: "请改成自己的MQTT密码"
      MQTT_TOPIC_PREFIX: labprobe/hub
    volumes:
      - ./data:/app/data
      - ./config:/app/config
      - ./backups:/app/backups
      - ./logs:/app/logs
```

启动后确认 `labprobe-hub` 和 `labprobe-mqtt` 都显示运行中。


## 二、配置 APP

1. 打开 APP 的“我的 / 设置”。
2. Hub 地址填写 `http://192.168.x.x:58443`。
3. APP Token 填写 Compose 中的 `APP_TOKEN`。
4. HOOK Token 填写 Compose 中的 `HOOK_TOKEN`。
5. 保存并立即校准。

APP 的 API 请求使用 `APP_TOKEN`。两个令牌都会由 Android Keystore 加密保存。


## 三、安装 LabRelay

SSH 登录已适配的锐捷路由器，执行：

```sh
wget -O /tmp/labprobe-install.sh https://lab.net86.dynv6.net:27772/agent/install.sh \
&& sh /tmp/labprobe-install.sh
```

安装时按提示操作：

1. 是否安装：输入 `Y`。
2. 自动发现的 Hub 是否正确：正确则输入 `Y`。
3. 输入 Compose 中配置的 `HOOK_TOKEN`。
4. 最终确认：输入 `Y`。

也可以先设置 `HUB_URL` 与 `HOOK_TOKEN` 环境变量再运行安装器。重新填写令牌时执行 `sh /tmp/labprobe-install.sh configure`。

完成后执行：

```sh
labrelay status
labrelay test-hub --config /etc/labprobe/agent.json
tail -f /tmp/labprobe/labrelay-agent.log
```


## 四、Token 怎么填

- `APP_TOKEN`：Hub 与 APP 填写相同值，仅供 APP 管理和同步 API 使用。
- `HOOK_TOKEN`：Hub、LabRelay、Lucky 与 Webhook 填写相同值，供路由器上报和 Hook 接口使用。
- `MQTT_PASSWORD`：必须使用自己的强密码，与两个 Token 相互独立。
- 三项凭据都不要使用示例占位值，也不要复用同一个值。
