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

## 广告学习模块

文本 AI 审核内置广告学习缓存。它不会自动乱写永久规则，而是把 DeepSeek 已判定为广告的文本归一化后保存到 MySQL。下次遇到同样结构的广告时，机器人会直接本地删除，不再重复调用 DeepSeek。

处理顺序：

```text
固定关键词/正则规则
-> 本地广告学习缓存
-> DeepSeek 判断未知文本
-> DeepSeek 判为广告后写入缓存
```

归一化会把常见变量统一成模板：

- 链接：`URL`
- Telegram 用户名：`TGUSER`
- 电话号码：`PHONE`
- 空格、标点、大小写、全角半角差异会被压缩

例如：

```text
点击下方链接五折买苹果 +91 6295 349 663
```

会形成类似模板：

```text
点击下方链接五折买苹果PHONE
```

缓存表 `ad_learning_sample` 会在应用启动时自动创建，现有 VPS 不需要手工导 SQL。你可以这样查看学习样本：

```bash
cd /opt/mydigital-bot
sudo docker compose exec mysql sh -c 'mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "SELECT id, spam_chance, source, rule_status, hit_count, group_id, LEFT(sample_text, 80) AS sample FROM ad_learning_sample ORDER BY last_seen DESC LIMIT 20;"'
```

应用启动完成后会先推送一次候选规则；之后每天 09:30（Asia/Shanghai）机器人会把高频候选广告样本发到对应群里。触发条件：

- `hit_count >= 3`
- `spam_chance >= 8`
- `rule_status = pending`
- 每个群每天最多推送 5 条

候选消息会带两个按钮：

- `✅加入规则`：只有群管理员可点。确认后会把样本转换成静默删除规则，写入当前群 `keywords`，并立即刷新运行时规则。
- `❌忽略`：只有群管理员可点。确认忽略后该样本标记为 `ignored`，不会继续每天反复推送。

手动回复广告执行 `/ban` 或 `/dban` 时，机器人也会把被回复消息的文本或 caption 写入学习样本。纯图片且没有 caption 的广告暂时不会进入文本学习库。

注意：该模块只有在对应群组 AI 状态为 `open` 时工作。AI 关闭时不会调用 DeepSeek，也不会产生新的 AI 广告学习样本。

## 群组规则格式

在群组里发送 `/setbot`，进入 `规则设置`，点击 `添加群组规则` 后，机器人会在 15 分钟内接受你输入的一条规则。手动添加时不要带系统自动生成的 UUID，也不要使用导出文件里的 `$$$` 前缀。

基本格式：

```text
正则表达式===回复内容
```

匹配后删除违规消息，并发送一条提醒，提醒 30 秒后删除：

```text
(?=.*(广告词1|广告词2)).*(联系|私聊)===发现$(memberName)疑似广告，消息已删除。&&del=x=0、y=30
```

只自动回复，不删除原消息：

```text
下载|安装|教程===客户端教程地址：https://example.com
```

回复里带按钮：

```text
官网|导航===请选择入口&&btns=官网$$https://example.com%%备用入口$$https://backup.example.com
```

回复里带图片或视频，链接必须是 `https://`：

```text
活动|优惠===活动说明&&photo=https://example.com/poster.jpg
```

支持的附加项：

- `btns=按钮文字$$URL`：URL 按钮，同一行多个按钮用 `%%` 分隔。
- `photo=https://...`：发送图片。
- `video=https://...`：发送视频。
- `del=x=0、y=30`：命中后删除原消息，`x` 是原消息延迟删除秒数，`0` 表示立即删除；`y` 是机器人提醒延迟删除秒数。
- `welcome=...`、`intoGroupBan=...`、`crontab=...` 属于进阶规则，建议先用基础关键词/正则规则跑稳定。

你现有的 `-1001328976723.txt` 里有大量旧格式规则，例如：

```text
$$$关键词1&&&关键词2|||关键词3===>回复内容
```

这类规则不能直接整行贴到 `/setbot` 的添加输入框。要改成当前格式，例如把触发条件改成正则，去掉 `$$$`，把 `===>` 换成 `===`，再按需要追加 `&&del=x=0、y=30`。

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
