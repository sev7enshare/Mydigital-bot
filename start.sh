#!/usr/bin/env bash
set -euo pipefail

REPO="sev7enshare/Mydigital-bot"
REF="${REF:-main}"
APP_DIR="${APP_DIR:-/opt/mydigital-bot}"
CONF_DIR="${CONF_DIR:-/home/feiyangdigitalbotconf}"
TMP_DIR="$(mktemp -d)"
BACKUP=""

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1"
    exit 1
  fi
}

gen_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 32 | tr -d '\n'
  else
    tr -dc 'A-Za-z0-9' </dev/urandom | head -c 48
  fi
}

need_cmd curl
need_cmd tar

mkdir -p "$CONF_DIR"

if [ ! -f "$CONF_DIR/config.json" ]; then
  curl -fsSL "https://raw.githubusercontent.com/${REPO}/${REF}/config/config.json" \
    -o "$CONF_DIR/config.json"
  chmod 600 "$CONF_DIR/config.json"
  echo "Created $CONF_DIR/config.json. Fill in botConfig.name and botConfig.token before starting."
else
  echo "Keeping existing $CONF_DIR/config.json"
fi

if [ ! -f "$CONF_DIR/bot.sql" ]; then
  curl -fsSL "https://raw.githubusercontent.com/${REPO}/${REF}/config/bot.sql" \
    -o "$CONF_DIR/bot.sql"
else
  echo "Keeping existing $CONF_DIR/bot.sql"
fi

ARCHIVE="$TMP_DIR/source.tar.gz"
curl -fsSL "https://github.com/${REPO}/archive/${REF}.tar.gz" -o "$ARCHIVE"
tar -xzf "$ARCHIVE" -C "$TMP_DIR"
SRC_DIR="$(find "$TMP_DIR" -maxdepth 1 -type d -name 'Mydigital-bot-*' | head -n 1)"

if [ -z "$SRC_DIR" ] || [ ! -d "$SRC_DIR" ]; then
  echo "Failed to unpack source archive."
  exit 1
fi

if [ -d "$APP_DIR" ]; then
  BACKUP="${APP_DIR}.bak.$(date +%Y%m%d%H%M%S)"
  mv "$APP_DIR" "$BACKUP"
  echo "Backed up existing app directory to $BACKUP"
fi
mkdir -p "$(dirname "$APP_DIR")"
mv "$SRC_DIR" "$APP_DIR"

if [ -n "$BACKUP" ] && [ -f "$BACKUP/.env" ]; then
  cp "$BACKUP/.env" "$APP_DIR/.env"
  chmod 600 "$APP_DIR/.env"
  echo "Restored existing $APP_DIR/.env"
fi

if [ ! -f "$APP_DIR/.env" ]; then
  cat > "$APP_DIR/.env" <<EOF
MYSQL_ROOT_PASSWORD=$(gen_secret)
MYSQL_DATABASE=bot
MYSQL_USER=bot
MYSQL_PASSWORD=$(gen_secret)
BOT_BIND_ADDR=127.0.0.1
BOT_HTTP_PORT=38455
EOF
  chmod 600 "$APP_DIR/.env"
  echo "Created $APP_DIR/.env with random database passwords."
else
  echo "Keeping existing $APP_DIR/.env"
fi

echo
echo "Next steps:"
echo "1. Edit $CONF_DIR/config.json and fill botConfig.name and botConfig.token."
echo "2. Start the bot:"
echo "   cd $APP_DIR && docker compose up -d --build"
echo "3. View logs:"
echo "   cd $APP_DIR && docker compose logs -f feiyangdigital-bot"
