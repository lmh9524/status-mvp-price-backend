# Status MVP Price Backend (Spring Boot + Redis)

This service provides token USD prices for `status-mvp` without exposing third‑party API keys to the mobile app.

## Features

- Public deployment ready (binds `0.0.0.0`, uses `PORT`)
- Redis required (cache + simple response caching)
- Price sources (priority):
  1. CoinGecko Pro (primary)
  2. CoinMarketCap (fallback)
  3. Binance (fallback; uses `USDT` pairs, treats `USDT≈USD`)
  4. Stablecoin fallback (USDC/USDT/DAI/BUSD -> $1.00)

Optional:
- CoinGecko public API fallback (rate-limited): set `COINGECKO_ALLOW_PUBLIC=true` when you don't have a Pro key.

## Endpoints

- `GET /api/v1/prices?symbols=ETH,USDC,OP&currency=usd`
- `GET /api/v1/prices/by-contract?chainId=10&contractAddresses=0x...,0x...&currency=usd`
- `GET /health`

## VEILX (BSC) on-chain pricing

CoinGecko may not list VEILX. This service can price **VEILX** directly from BSC by calling PancakeSwap V2 Router
`getAmountsOut(1 VEILX -> USDT)` via `eth_call` (treating `USDT≈USD` for MVP).

Enable by setting:

- `BSC_RPC_URL` (BSC mainnet RPC endpoint)
- `VEILX_CONTRACT_ADDRESS` (VEILX token contract on BSC)

Optional overrides:

- `PANCAKE_ROUTER_V2_ADDRESS` (default is PancakeSwap V2 router)
- `BSC_USDT_CONTRACT_ADDRESS` (default is BSC USDT)

## Local run (Docker)

Create an env file (see `env.example`) then:

```bash
cp env.example env.local
# edit env.local (set keys)
docker compose --env-file env.local up --build
```

Service listens on `http://127.0.0.1:${PORT}`.

## Server run (systemd, recommended)

If you're running the jar directly (e.g. `nohup java -jar ...`), you may lose environment variables across sessions/reboots.
Use systemd so your backend auto-starts and secrets are kept in a root-only env file.

Templates:
- `deploy/systemd/status-mvp-price-backend.service`
- `deploy/systemd/status-mvp-price-backend.env.example`

Install (Amazon Linux 2023 example):

```bash
cd /home/ec2-user/status-mvp/status-mvp-price-backend

sudo mkdir -p /etc/status-mvp-price-backend
sudo cp deploy/systemd/status-mvp-price-backend.env.example /etc/status-mvp-price-backend/status-mvp-price-backend.env
sudo chmod 600 /etc/status-mvp-price-backend/status-mvp-price-backend.env
sudo chown root:root /etc/status-mvp-price-backend/status-mvp-price-backend.env

# Edit env file and set COINGECKO_PRO_API_KEY (and optional vars)
sudo vi /etc/status-mvp-price-backend/status-mvp-price-backend.env

sudo cp deploy/systemd/status-mvp-price-backend.service /etc/systemd/system/status-mvp-price-backend.service

sudo systemctl daemon-reload
sudo systemctl enable --now status-mvp-price-backend

sudo systemctl status status-mvp-price-backend --no-pager
journalctl -u status-mvp-price-backend -f
```

## Adding more tokens

- **Symbol prices** (`/api/v1/prices`) use a built-in `symbol -> CoinGecko id` map and will try sources in
  this order: CoinGecko Pro → CoinMarketCap → Binance.
- To support additional symbols without code changes, set:
  - `COINGECKO_SYMBOL_ID_OVERRIDES=SYMBOL=id,SYMBOL2=id2`
  - Example: `COINGECKO_SYMBOL_ID_OVERRIDES=WETH=weth,WBTC=wrapped-bitcoin`

## Public deployment (IP:PORT)

- Bind is already `0.0.0.0`.
- Open your firewall for `${PORT}`.
- Run on a server:

```bash
docker compose --env-file /path/to/env.local up -d --build
```

Then your app can call:

- `http://<SERVER_IP>:<PORT>/api/v1/prices?symbols=ETH,USDC&currency=usd`

## Notes

- Do **not** put API keys into the mobile app. Keep them in server environment variables.
- This is a minimal MVP implementation; you can add auth, stricter rate limiting, and more price sources later.


