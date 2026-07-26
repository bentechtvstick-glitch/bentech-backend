# BenTech TV Stick Backend

Backend API for BenTech TV Stick IPTV service.

## Stack
- Node.js + Express
- LowDB (JSON file database)
- JWT Authentication
- Input validation, pagination, audit logging, error handling

## Endpoints
- GET /api/health — Health check
- GET /api/version — Version info
- POST /api/auth/login — Admin login (JWT)
- POST /api/auth/activate — Device activation
- GET /api/dashboard — Dashboard counts
- CRUD on: customers, devices, channels, programs, banners, popups, tickers, live-events, logs, settings

## Deployment
Hosted on Render with persistent disk for db.json.