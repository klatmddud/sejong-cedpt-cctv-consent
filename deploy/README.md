# 서버 구성

Oracle Cloud Ubuntu 24.04 (x86_64, RAM 1GB, swap 4GB) 한 대에 컨테이너 3개가 `web` Docker 네트워크로 연결되어 있다.

| 컨테이너 | 이미지 | 역할 |
|---|---|---|
| `caddy` | `caddy:2` | 80/443 공개, Let's Encrypt 인증서 자동 발급, `cctv-consent:8080`으로 프록시 |
| `cctv-consent` | `ghcr.io/klatmddud/sejong-cedpt-cctv-consent` | 앱. 외부 포트 없음. GitHub Actions가 `main` push마다 교체 |
| `cctv-db` | `postgres:17` | DB. 외부 포트 없음. 데이터는 `cctv_db_data` volume |

비밀 값은 서버에만 있고 저장소와 GitHub에는 없다.

- `~/cctv-consent/db.env`: `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` (DB 컨테이너용)
- `~/cctv-consent/app.env`: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (앱 컨테이너용, `deploy.yml`이 `--env-file`로 전달)

## 처음 설정 (한 번만)

```bash
docker network create web

# DB: 비밀번호를 서버에서 생성하고 두 env 파일에 같은 값으로 저장
mkdir -p ~/cctv-consent && chmod 700 ~/cctv-consent
PW=$(openssl rand -hex 24); umask 077
printf "POSTGRES_USER=cctv_consent\nPOSTGRES_PASSWORD=%s\nPOSTGRES_DB=cctv_consent\n" "$PW" > ~/cctv-consent/db.env
printf "DB_URL=jdbc:postgresql://cctv-db:5432/cctv_consent\nDB_USERNAME=cctv_consent\nDB_PASSWORD=%s\n" "$PW" > ~/cctv-consent/app.env
unset PW
docker run -d --name cctv-db --restart unless-stopped --network web --memory 256m \
  --env-file ~/cctv-consent/db.env -v cctv_db_data:/var/lib/postgresql/data postgres:17 \
  -c shared_buffers=64MB -c max_connections=20

# HTTPS
mkdir -p ~/caddy   # 로컬에서: scp deploy/Caddyfile oracle:~/caddy/Caddyfile
docker run -d --name caddy --restart unless-stopped --network web -p 80:80 -p 443:443 \
  -v ~/caddy/Caddyfile:/etc/caddy/Caddyfile:ro -v caddy_data:/data -v caddy_config:/config caddy:2
```

앱 컨테이너는 `main`에 push하면 `.github/workflows/deploy.yml`이 띄운다. DB 테이블은 앱이 시작할 때 Flyway가 만든다.

## 자주 쓰는 명령

```bash
docker logs -f cctv-consent                       # 앱 로그
docker exec caddy caddy reload --config /etc/caddy/Caddyfile   # Caddyfile 수정 후
docker exec -it cctv-db psql -U cctv_consent      # DB 접속
```
