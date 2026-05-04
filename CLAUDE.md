# CLAUDE.md

使用中文进行交互

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PlayEdu is an open-source enterprise training platform built with Java (Spring Boot 3) + React 18, using a frontend/backend separation architecture. It supports video learning, department management, learner progress tracking, and private video storage.

## Repository Structure

```
playedu-api/      # Spring Boot 3 backend (multi-module Maven)
playedu-admin/    # Admin dashboard (React 18 + Ant Design 5 + Vite)
playedu-pc/       # PC learner portal (React 18 + Ant Design 5 + Vite)
playedu-h5/       # Mobile H5 learner portal (React 18 + antd-mobile + Vite)
```

## Backend (playedu-api)

Multi-module Maven project with Java 17 + Spring Boot 3.3.4 + MyBatis-Plus.

**Modules:**
- `playedu-api` — HTTP entry point, controllers split into `backend/` (admin) and `frontend/` (learner)
- `playedu-common` — Shared utilities, domain entities, mappers, services, annotations, exceptions
- `playedu-system` — System config, admin users, roles, LDAP, departments
- `playedu-course` — Course, chapter, hour, attachment, learner progress logic
- `playedu-resource` — Resource/file management, cloud storage (Aliyun OSS, Tencent COS)

**Build & Run:**
```bash
cd playedu-api
./mvnw clean package -DskipTests       # Build JAR
./mvnw test                             # Run all tests
./mvnw test -pl playedu-common -Dtest=SomeTest  # Run single test
./mvnw spring-boot:run -pl playedu-api  # Run locally
```

**Key tech:** MySQL, MyBatis-Plus, in-memory cache (no Redis), Spring AOP for logging/auth, WebSocket.

## Frontend (Admin / PC / H5)

All three frontends use the same toolchain: React 18, TypeScript, Vite, pnpm.

```bash
pnpm install    # Install dependencies
pnpm dev        # Dev server
pnpm build      # Production build (tsc + vite build)
```

- **Admin** (`playedu-admin`): Ant Design 5, Redux Toolkit, React Router 6, ECharts — port 9900
- **PC** (`playedu-pc`): Ant Design 5, Redux Toolkit, React Router 6, SASS — port 9800
- **H5** (`playedu-h5`): antd-mobile, Redux Toolkit, React Router 6, SASS — port 9801

## Docker / Full Stack

```bash
docker-compose up -d    # Start all services (API port 9700, admin 9900, PC 9800, H5 9801)
```

Default admin credentials: `admin@playedu.xyz / playedu`

## Architecture Notes

- **Auth:** JWT-based; backend/frontend controllers are separated by URL prefix (`/backend/` vs `/frontend/`)
- **Storage:** S3-compatible private buckets (Aliyun OSS / Tencent COS); resources use signed URLs, not public URLs
- **Cache:** In-memory cache (no Redis dependency)
- **LDAP:** Optional LDAP sync for users/departments, tracked with sync detail records
- **Permissions:** Role-based for admin users; learner access controlled by department enrollment
