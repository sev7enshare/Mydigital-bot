# Mydigital-bot Telegram 群管机器人

这是从 `youshandefeiyang/feiyangdigital-bot` fork 后整理的自用部署版本。项目基于 Spring Boot、Telegrambot API、MySQL、Redis，可用于 Telegram 群组管理、关键词/正则处理、入群验证、频道马甲广告拦截、刷屏检测，并可选接入 DeepSeek 与 Google Cloud Vision 做 AI 文本和媒体审核。

## 部署方式

推荐直接部署在 VPS 上，用 Docker Compose 运行。默认使用 `longPolling` 模式，不需要域名，不需要公网 webhook，只要服务器能访问 Telegram API 即可。

你的 OVH SG VPS 配置足够运行本项目。首次建议只开放 SSH，不要把机器人 HTTP 端口暴露到公网。

## 一键准备

```bash
curl -fsSL -o start.sh https://raw.githubusercontent.com/sev7enshare/Mydigital-bot/main/start.sh
chmod +x start.sh
sudo ./start.sh
```

脚本会：

- 保留已有 `/home/feiyangdigitalbotconf/config.json`，不会删除你的配置。
- 首次部署时下载空白 `config.json` 和 `bot.sql`。
- 下载当前 fork 源码到 `/opt/mydigital-bot`。
- 自动生成 `/opt/mydigital-bot/.env`，包含随机 MySQL 密码。
- 默认把应用 HTTP 端口绑定到 `127.0.0.1:38455`，longPolling 模式下不会暴露公网。

## 配置机器人

编辑：

```bash
sudo nano /home/feiyangdigitalbotconf/config.json
```

至少填写：

```json
{
  "botConfig": {
    "mode": "longPolling",
    "name": "你的机器人用户名，不带 @",
    "token": "BotFather 给你的 token",
    "path": ""
  }
}
```

`openAIApiKey`、`googleServiceAccount` 可以先留空。建议先启用关键词、正则、频道马甲、入群验证等本地规则，确认稳定后再开启 AI 审核，避免误封和 API 成本失控。

## 启动

```bash
cd /opt/mydigital-bot
sudo docker compose up -d --build
```

查看日志：

```bash
cd /opt/mydigital-bot
sudo docker compose logs -f feiyangdigital-bot
```

停止：

```bash
cd /opt/mydigital-bot
sudo docker compose stop
```

重启：

```bash
cd /opt/mydigital-bot
sudo docker compose restart
```

## 安全注意事项

- 不要把填好 token/API key/Google 私钥的 `config.json` 提交到 GitHub。
- `/opt/mydigital-bot/.env` 包含数据库密码，权限应保持 `600`。
- 默认 `BOT_BIND_ADDR=127.0.0.1`，longPolling 模式不要改成 `0.0.0.0`。
- 如果使用 webhook，必须配置 HTTPS 反向代理，并额外评估 `/feiyangdigitalbot` 接口暴露风险。
- Bot 加入群组后只授予必要管理员权限：删除消息、封禁用户、限制用户即可。
- AI 审核存在误判。建议先在小群或测试群观察，再放到主群。

## 更新

重新运行安装脚本会备份旧的 `/opt/mydigital-bot` 源码目录，但会保留 `/home/feiyangdigitalbotconf/config.json`。

```bash
curl -fsSL -o start.sh https://raw.githubusercontent.com/sev7enshare/Mydigital-bot/main/start.sh
chmod +x start.sh
sudo ./start.sh
cd /opt/mydigital-bot
sudo docker compose up -d --build
```

数据库卷不会被自动删除。删除 `mysql-data` 前必须先备份数据库。
