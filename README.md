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

## Endpoints

- `GET /api/v1/prices?symbols=ETH,USDC,OP&currency=usd`
- `GET /api/v1/prices/by-contract?chainId=10&contractAddresses=0x...,0x...&currency=usd`
- `GET /health`

## Local run (Docker)

Create an env file (see `env.example`) then:

```bash
cp env.example env.local
# edit env.local (set keys)
docker compose --env-file env.local up --build
```

Service listens on `http://127.0.0.1:${PORT}`.

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


