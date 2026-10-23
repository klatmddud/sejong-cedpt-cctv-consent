# 서버 구성

Oracle Cloud Ubuntu 24.04 (x86_64, RAM 1GB, swap 4GB) 한 대에 컨테이너 3개가 `web` Docker 네트워크로 연결되어 있다.

| 컨테이너 | 이미지 | 역할 |
|---|---|---|
| `caddy` | `caddy:2` | 80/443 공개, Let's Encrypt 인증서 자동 발급, `cctv-consent:8080`으로 프록시 |
| `cctv-consent` | `ghcr.io/klatmddud/sejong-cedpt-cctv-consent` | 앱. 외부 포트 없음. GitHub Actions가 `main` push마다 교체 |
| `cctv-db` | `postgres:17` | DB. 외부 포트 없음. 데이터는 `cctv_db_data` volume |

비밀 값은 서버에만 있고 저장소와 GitHub에는 없다.

- `~/cctv-consent/db.env`: `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` (DB 컨테이너용)
- `~/cctv-consent/app.env`: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `ADMIN_USERNAME`, `ADMIN_PASSWORD_HASH` (앱 컨테이너용, `deploy.yml`이 `--env-file`로 전달)

## 관리자 계정 설정·변경

관리자 페이지(`/admin`)는 공용 계정 하나로 로그인한다. 학생과 같은 로그인 화면(`/login`)을 쓰며, 아이디가 관리자
아이디(`ADMIN_USERNAME`)와 같으면 관리자 로그인, 숫자뿐이면 학번으로 보고 세종대 포털로 확인한다. 학번과 겹치지 않도록
관리자 아이디에는 영문자가 하나 이상 있어야 한다(없으면 앱이 시작되지 않는다). 비밀번호는 BCrypt 해시로만 서버에 둔다.
영문자가 섞인 아이디로 로그인에 실패하면 관리자 아이디가 아니어도 같은 IP에서 15분에 5번까지만 받는다. 그 뒤 15분 동안은
그 IP에서 관리자 로그인도 막힌다(학번만 넣는 학생 로그인 실패는 세지 않는다).
담당자가 바뀌면 같은 방법으로 비밀번호를 바꾼다.

1. 서버에서 해시를 만든다. 비밀번호를 두 번 입력하면 `$2a$14$...` 형태의 해시가 출력된다.
   입력한 비밀번호는 화면과 셸 기록에 남지 않는다. cost는 12로 한다. Caddy 기본값(14)은 이 서버에서 확인 한 번이
   3~6초 걸려 로그인이 느려진다(12는 1초 안팎).
   ```bash
   docker exec -it caddy caddy hash-password --bcrypt-cost 12
   ```
2. `~/cctv-consent/app.env`를 열어(`nano ~/cctv-consent/app.env`) 아래 두 줄을 넣거나 바꾼다.
   ```
   ADMIN_USERNAME=관리자 아이디
   ADMIN_PASSWORD_HASH=1번에서 출력된 해시
   ```
3. 컨테이너를 다시 만들어야 반영된다(`docker restart`는 env 파일을 다시 읽지 않음).
   GitHub Actions에서 마지막 배포 실행을 Re-run 하거나 `main`에 push 한다.

`ADMIN_PASSWORD_HASH`가 없으면 배포가 기존 앱을 내리기 전에 중단된다.

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
docker logs cctv-consent 2>&1 | grep audit        # 관리자 작업 감사 로그

## 보안 운영 메모

- **관리자 비밀번호**: 공용 계정 하나로 모든 학생의 서명과 동의서를 내려받을 수 있다. 아이디는 `admin` 대신 다른 값을 쓰고,
  비밀번호는 무작위 16자 이상으로 만든다(`openssl rand -base64 18`). 담당자가 바뀌면 위 방법으로 바로 바꾼다.
- **감사 로그**: 관리자 로그인 성공·실패·차단, 명부 반영, 과목 삭제, 동의서 일괄 다운로드를 `audit` 로거로 남긴다
  (시각, IP, 작업, 건수. 학번·이름은 남기지 않는다). 컨테이너를 다시 만들면 로그가 사라지므로 필요하면 그 전에 저장한다.
- **동의서 파기**: 매일 04:00(KST)에 파기 예정일(학기 종료 1년 뒤)이 된 학기를 과목, 수강, 동의서와 함께 지운다.
  1학기는 6월 30일, 2학기는 12월 31일에 끝난다.
- **학생 로그인 제한**: 세종대 포털은 5회 실패하면 잠기므로 이 사이트에서는 학번당 24시간에 3회, IP당 30분에 10회 실패하면 막는다.
  같은 학번은 동시에 한 번만, 포털 호출은 서버 전체에서 동시에 8개까지만(빈자리를 3초까지 기다림) 보낸다.
  IP당 실패는 비밀번호가 틀린 것으로 확인된 뒤에 세므로, 한 강의실(같은 IP)에서 함께 로그인해도 잠기지 않는다.
  제한 기록은 메모리에만 있어 재시작하면 풀린다.
- **남의 학번 잠그기 공격**: 누구든 남의 학번으로 3번 틀리면 그 학생은 24시간 로그인하지 못한다(포털 계정 보호 때문에 피할 수 없음).
  서버 전체 실패가 10분에 30회를 넘으면 감사 로그에 `학생 로그인 실패 급증`을 남기고 그동안 IP당 한도를 2회로 줄인다.
  이 로그가 보이면 공격을 의심한다. 잠긴 학생은 앱을 재시작하면 풀린다. 완전히 막으려면 CAPTCHA가 필요하다.
- **관리자 로그인**: 영문 아이디 실패는 IP당 15분에 5회로, 동시에 보내도 넘지 않는다. 로그인에 성공해도 그 IP의 다른 실패는
  지우지 않는다(같은 공유기를 쓰는 사람이 관리자 로그인 때마다 다시 시도하지 못하게). BCrypt 확인은 서버 전체에서 동시에 2개까지만 한다.
- **요청 크기**: 로그인 확인 전에도 본문을 읽으므로 Caddyfile에서 1MB로 막고, 명부 업로드(`/admin/roster`)만 20MB까지 받는다.
  Caddyfile은 자동 배포되지 않는다. 고치면 `scp deploy/Caddyfile oracle:~/caddy/Caddyfile` 후 위의 reload 명령을 실행한다.
- **배포 액션**: `deploy.yml`의 `appleboy/ssh-action`은 서버 SSH 키를 받으므로 커밋 SHA로 고정했다. 버전을 올릴 때는
  [릴리스](https://github.com/appleboy/ssh-action/releases)를 확인하고 SHA를 바꾼다.
- **로컬 전용 설정**: `application-local.properties`와 `local` 패키지(테스트 계정)는 배포 jar에 들어가지 않는다(`build.gradle`의 `bootJar`).
