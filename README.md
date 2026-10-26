<div align="center">

# sejong-cedpt-cctv

세종대학교 컴퓨터공학과 실습실 수업 수강생에게<br>
CCTV 촬영 및 이용 동의서를 받는 웹 서비스입니다.

[![Build and Deploy](https://github.com/klatmddud/sejong-cedpt-cctv-consent/actions/workflows/deploy.yml/badge.svg)](https://github.com/klatmddud/sejong-cedpt-cctv-consent/actions/workflows/deploy.yml)
![Java 25](https://img.shields.io/badge/Java-25-007396)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-4169E1)
[![License: MIT](https://img.shields.io/badge/License-MIT-lightgrey)](LICENSE)

[서비스 바로가기](https://sejong-cedpt-cctv.duckdns.org) · [운영 문서](deploy/README.md) · [문의](mailto:klatmddud@gmail.com)

</div>

## 소개

대상 실습실은 **B201 · B202 · B203 · B206 · B207**입니다.

| 사용자 | 할 수 있는 일 |
|---|---|
| 수강생 | 세종대 포털 계정으로 로그인해, 본인이 실습실에서 수강 중인 강의의 CCTV 동의서를 조회하고 서명해 제출합니다. |
| 관리자 | 각 실습실 TA, 장비조교, 학과사무실 조교 등이 강의별 수강명부와 동의서를 관리합니다. |

### 운영 정보

| 항목 | 내용 |
|---|---|
| 기준 학기 | 2026년 2학기 |
| 관리자 | 컴퓨터공학과 장비조교 김승영 |
| 서비스 주소 | https://sejong-cedpt-cctv.duckdns.org |
| 문의 | klatmddud@gmail.com |

## 기술 스택

| 구분 | 사용 |
|---|---|
| 언어·런타임 | Java 25 |
| 프레임워크 | Spring Boot 4.1 (Web MVC, Security, Data JPA, Thymeleaf, Actuator) |
| DB | PostgreSQL 17, Flyway 마이그레이션 |
| 문서 처리 | Apache PDFBox(동의서 PDF), fastexcel(수강명부 xlsx) |
| 배포 | Docker, GitHub Actions, GHCR, Caddy(HTTPS) |

## 시작하기

### 로컬 실행

JDK 25가 필요합니다. 터미널에서 실행하거나, IDE에서 `CctvConsentApplication`의 `main`을 실행합니다.

```bash
./gradlew bootRun
```

소스에서 실행하면 `local` 프로필이 기본 프로필로 켜집니다. 따로 설정할 것은 없고, `spring.profiles.active`로 다른 프로필을 지정하면 켜지지 않습니다.
`local` 프로필은 Docker나 PostgreSQL 설치 없이 내장 PostgreSQL을 띄우고(`build/local-postgres`), 세종대 포털 대신 테스트 계정으로 로그인합니다.
`local` 패키지(테스트 계정)와 로컬 전용 설정(`application-local.properties`)은 배포 jar에 포함되지 않아 운영 서버에서는 켜지지 않습니다.

> [!NOTE]
> 로컬 실행용 내장 PostgreSQL 바이너리는 macOS Apple Silicon(`darwin-arm64v8`)용만 포함되어 있습니다.
> 다른 환경에서는 `build.gradle`의 `developmentOnly` 바이너리를 바꿔야 합니다.

### 테스트 계정

http://localhost:8080/login 에서 로그인합니다. 아이디가 관리자 아이디이면 관리자로, 숫자이면 학생으로 로그인합니다.

| 계정 | 아이디 | 비밀번호 | 비고 |
|---|---|---|---|
| 관리자 | `admin` | `local-admin` | |
| 학생 | `20990001` | `test1234` | 테스트 수업 2개에 자동 등록 |
| 수업 없는 학생 | `20990002` | `test1234` | 빈 화면 확인용 |

### 테스트

```bash
./gradlew test
```

테스트도 내장 PostgreSQL(macOS Apple Silicon, Linux x86_64)을 사용해 실제 DB에서 실행합니다.
세종대 포털 실계정 테스트(`SjptClientLiveTest`)는 저장소 루트의 `.env`에 아래 값이 있을 때만 실행하고, 없으면 건너뜁니다.

```
SEJONG_TEST_ID=학번
SEJONG_TEST_PASSWORD=포털 비밀번호
```

> [!WARNING]
> `.env`에는 실제 포털 비밀번호가 들어갑니다. 커밋하지 마세요(`.gitignore`에 등록되어 있습니다).

## 설정

운영 환경에서는 환경 변수로 값을 전달합니다.

| 환경 변수 | 설명 |
|---|---|
| `DB_URL` | JDBC URL (예: `jdbc:postgresql://cctv-db:5432/cctv_consent`) |
| `DB_USERNAME`, `DB_PASSWORD` | DB 계정 |
| `ADMIN_USERNAME` | 관리자 아이디. 학번과 겹치지 않도록 영문자가 하나 이상 있어야 합니다 |
| `ADMIN_PASSWORD_HASH` | 관리자 비밀번호의 BCrypt 해시 |

대상 강의실은 `application.properties`의 `consent.target-rooms`로 정합니다. 명부의 강의실 표기에서 건물 약자를 뗀 값(`센B201` → `B201`)과 비교합니다.

## 배포

`main`에 push하면 GitHub Actions(`.github/workflows/deploy.yml`)가 Docker 이미지를 만들어 GHCR에 올리고, 서버에 SSH로 접속해 컨테이너를 교체합니다.
서버는 Caddy(HTTPS), 앱, PostgreSQL 컨테이너 3개로 구성됩니다. 비밀 값은 서버에만 둡니다.

서버 초기 설정, 관리자 비밀번호 변경, 운영 명령, 보안 운영 메모는 [`deploy/README.md`](deploy/README.md)를 참고하세요.

## 프로젝트 구조

```
src/main/java/kr/ac/sejong/cedpt/cctvconsent/
├── auth/      세종대 포털·sjpt 로그인, 수강 과목 조회, 로그인 시도 제한
├── consent/   동의서 서명, PDF 생성, 일괄 다운로드
├── roster/    학기·과목·수강, 수강명부 xlsx 반영, 대상 강의실, 학기 파기
├── admin/     관리자 화면, 보안 설정, 감사 로그
└── local/     local 프로필 전용 내장 DB와 테스트 계정 (배포 jar 제외)
src/main/resources/
├── consent/         원본 동의서 PDF (한국어, 영어)
├── db/migration/    Flyway 마이그레이션
└── templates/       Thymeleaf 화면 (student, admin)
deploy/              Caddyfile, 서버 운영 문서
```

## 라이선스

[MIT](LICENSE)
