# Deployment Security Checklist

Use this checklist before running the bot in a production Telegram group.

## Secrets

- Never commit a filled `/home/feiyangdigitalbotconf/config.json`.
- Keep Telegram bot token, DeepSeek/OpenAI API keys, and Google service account private keys outside Git.
- Keep `/opt/mydigital-bot/.env` permission-restricted, for example `chmod 600 /opt/mydigital-bot/.env`.
- Rotate any token that has ever appeared in chat logs, screenshots, shell history, or a public repository.

## Network

- Prefer `longPolling` mode for VPS deployment.
- Keep `BOT_BIND_ADDR=127.0.0.1` unless you intentionally deploy webhook mode behind HTTPS.
- Do not expose MySQL or Redis ports to the public internet.
- If webhook mode is enabled later, put it behind a reverse proxy and review the `/feiyangdigitalbot` endpoint before exposing it.

## Docker

- The compose file builds from this fork by default instead of pulling an unpinned upstream `latest` image.
- MySQL uses a dedicated application user instead of application access through `root`.
- Redis is pinned to `redis:7-alpine` instead of `latest`.
- Back up the MySQL volume before removing Docker volumes.

## Telegram Group Permissions

Give the bot only the permissions it needs:

- Delete messages
- Ban users
- Restrict users

Avoid unnecessary owner-level or invite-management privileges.

## Moderation Rollout

Recommended rollout order:

1. Add the bot to a test group.
2. Enable keyword and regex rules first.
3. Enable anti-flood and channel sender spam checks.
4. Enable new-member verification.
5. Enable AI text/media checks only after observing false positives and API cost.

AI moderation can misclassify real users. Keep an admin review path available during the first days of deployment.
