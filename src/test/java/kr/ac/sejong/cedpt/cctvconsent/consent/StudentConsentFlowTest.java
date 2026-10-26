package kr.ac.sejong.cedpt.cctvconsent.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.PostgresTestSupport;
import kr.ac.sejong.cedpt.cctvconsent.auth.SejongAuthException;
import kr.ac.sejong.cedpt.cctvconsent.auth.SejongStudent;
import kr.ac.sejong.cedpt.cctvconsent.auth.SjptCourse;
import kr.ac.sejong.cedpt.cctvconsent.auth.StudentAuthenticator;
import kr.ac.sejong.cedpt.cctvconsent.auth.StudentLogin;
import kr.ac.sejong.cedpt.cctvconsent.auth.AcademicTerm;
import kr.ac.sejong.cedpt.cctvconsent.auth.StudentPrincipal;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterImportService;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterTestFixtures;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class StudentConsentFlowTest extends PostgresTestSupport {

	private static final String STUDENT = "20010001";
	private static final String OTHER = "20010002";
	// RosterTestFixtures 명부와 같은 학기
	private static final AcademicTerm TERM = new AcademicTerm(2026, 2);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RosterImportService rosterImport;

	@MockitoBean
	StudentAuthenticator authenticator;

	@MockitoSpyBean
	ConsentPdfRenderer renderer;

	@Autowired
	kr.ac.sejong.cedpt.cctvconsent.auth.LoginAttemptLimiter limiter;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE semester CASCADE");
		// 학기 종료일이 지나지 않아야 학생이 동의할 수 있으므로 지금 학기 명부로 만든다
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		String title = today.getYear() + "년도 " + (today.getMonthValue() <= 6 ? 1 : 2) + "학기 출석부";
		byte[] roster = RosterTestFixtures.roster(title, List.of(
				RosterTestFixtures.course("000304-001", "센B201", STUDENT, OTHER),
				RosterTestFixtures.course("000305-001", "센B206", STUDENT),
				RosterTestFixtures.course("000306-001", "센B209", STUDENT)));
		rosterImport.importRoster(rosterImport.read(List.of(new RosterImportService.UploadedFile("명부.xlsx", roster))), List.of());
	}

	@Test
	void logsInWithPortalAccount() throws Exception {
		when(authenticator.authenticate(STUDENT, "pw"))
				.thenReturn(new StudentLogin(new SejongStudent(STUDENT, "홍길동"), TERM, List.of()));

		mvc.perform(formLogin("/login").user(STUDENT).password("pw"))
				.andExpect(authenticated().withRoles("STUDENT"))
				.andExpect(redirectedUrl("/consents"));
	}

	@Test
	void loginAddsSjptCoursesInTargetRooms() throws Exception {
		when(authenticator.authenticate(STUDENT, "pw")).thenReturn(new StudentLogin(new SejongStudent(STUDENT, "홍길동"),
				TERM, List.of(
						sjptCourse("000304-001", "과목000304-001", "월10:30-12:00(김교수/센B201)"),
						sjptCourse("000400-001", "정정기간추가과목", "화10:30-12:00(김교수/센B203)"),
						sjptCourse("000401-001", "대상아닌강의실과목", "수10:30-12:00(김교수/센B209)"))));

		MockHttpSession session = login(STUDENT);

		mvc.perform(get("/consents").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("정정기간추가과목")))
				.andExpect(content().string(Matchers.containsString("과목000305-001")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("대상아닌강의실과목"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("수강 정보를 새로 불러오지 못해"))));
	}

	@Test
	void loginSucceedsWhenCourseLookupFails() throws Exception {
		when(authenticator.authenticate(STUDENT, "pw"))
				.thenReturn(new StudentLogin(new SejongStudent(STUDENT, "홍길동"), TERM, null));

		MockHttpSession session = login(STUDENT);

		mvc.perform(get("/consents").session(session))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("수강 정보를 새로 불러오지 못해")))
				.andExpect(content().string(Matchers.containsString("과목000304-001")));
	}

	@Test
	void blocksStudentIdAfterThreeWrongPasswords() throws Exception {
		String id = "20019999";
		when(authenticator.authenticate(anyString(), anyString()))
				.thenThrow(new SejongAuthException(SejongAuthException.Reason.INVALID_CREDENTIALS, "wrong"));

		for (int i = 0; i < 3; i++) {
			mvc.perform(formLogin("/login").user(id).password("wrong")).andExpect(unauthenticated());
		}
		mvc.perform(formLogin("/login").user(id).password("wrong")).andExpect(unauthenticated());

		// 네 번째는 세종대 포털에 보내지 않는다 (포털 계정 잠김 방지)
		verify(authenticator, times(3)).authenticate(id, "wrong");
	}

	@Test
	void tightensPerIpLimitWhileWrongPasswordsSpikeAcrossStudents() throws Exception {
		when(authenticator.authenticate(anyString(), anyString()))
				.thenThrow(new SejongAuthException(SejongAuthException.Reason.INVALID_CREDENTIALS, "wrong"));
		try {
			// 남의 학번을 잠그려는 공격: IP를 바꿔 가며 학번마다 한 번씩 틀린다. 서버 전체로 10분에 30번이면 급증으로 본다
			for (int i = 0; i < 30; i++) {
				mvc.perform(loginFrom("10.9.0." + i, "2003%04d".formatted(i), "wrong")).andExpect(unauthenticated());
			}
			// 급증 중에는 새 IP 하나가 학번 2개까지만 포털에 묻는다
			for (int i = 1; i <= 3; i++) {
				mvc.perform(loginFrom("10.9.1.1", "2004000" + i, "wrong")).andExpect(unauthenticated());
			}
			verify(authenticator).authenticate("20040002", "wrong");
			verify(authenticator, never()).authenticate("20040003", "wrong");
		}
		finally {
			limiter.reset("student-all");
		}
	}

	private static org.springframework.test.web.servlet.RequestBuilder loginFrom(String ip, String id, String password) {
		return post("/login").param("username", id).param("password", password).with(csrf()).with(request -> {
			request.setRemoteAddr(ip);
			return request;
		});
	}

	@Test
	void doesNotTightenPerIpLimitDuringPortalOutage() throws Exception {
		when(authenticator.authenticate(anyString(), anyString()))
				.thenThrow(new SejongAuthException(SejongAuthException.Reason.UNAVAILABLE, "down"));
		try {
			// 포털이 죽은 동안 여러 강의실에서 30명이 로그인한다. 공격이 아니므로 급증으로 보지 않는다
			for (int i = 0; i < 30; i++) {
				mvc.perform(loginFrom("10.9.4." + i, "2007%04d".formatted(i), "pw")).andExpect(unauthenticated());
			}
			doThrow(new SejongAuthException(SejongAuthException.Reason.INVALID_CREDENTIALS, "wrong"))
					.when(authenticator).authenticate(anyString(), anyString());
			// 평소 한도(30분에 10회)라 새 IP가 학번 3개를 모두 포털에 묻는다(급증 중이면 2개)
			for (int i = 1; i <= 3; i++) {
				mvc.perform(loginFrom("10.9.5.1", "2008000" + i, "wrong")).andExpect(unauthenticated());
			}
			verify(authenticator).authenticate("20080003", "wrong");
		}
		finally {
			// limiter는 테스트끼리 공유하므로 이 테스트가 남긴 기록을 지운다
			limiter.reset("student-all");
			limiter.reset("student-ip:10.9.5.1");
			for (int i = 0; i < 30; i++) {
				limiter.reset("student-ip:10.9.4." + i);
			}
			for (int i = 1; i <= 3; i++) {
				limiter.reset("student:2008000" + i);
			}
		}
	}

	@Test
	void doesNotCountPortalOutageAsWrongPassword() throws Exception {
		String id = "20019998";
		when(authenticator.authenticate(anyString(), anyString()))
				.thenThrow(new SejongAuthException(SejongAuthException.Reason.UNAVAILABLE, "down"));

		for (int i = 0; i < 4; i++) {
			mvc.perform(formLogin("/login").user(id).password("pw")).andExpect(unauthenticated());
		}

		verify(authenticator, times(4)).authenticate(id, "pw");
	}

	@Test
	void sendsOnlyOneConcurrentLoginOfSameStudentToPortal() throws Exception {
		String id = "20019997";
		CountDownLatch portalCalled = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(authenticator.authenticate(anyString(), anyString())).thenAnswer(invocation -> {
			portalCalled.countDown();
			release.await(5, TimeUnit.SECONDS);
			throw new SejongAuthException(SejongAuthException.Reason.INVALID_CREDENTIALS, "wrong");
		});

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> first = executor.submit(() -> {
				mvc.perform(formLogin("/login").user(id).password("wrong")).andExpect(unauthenticated());
				return null;
			});
			assertThat(portalCalled.await(5, TimeUnit.SECONDS)).isTrue();

			// 첫 시도가 포털 응답을 기다리는 동안 같은 학번 시도는 포털에 보내지 않는다
			mvc.perform(formLogin("/login").user(id).password("wrong")).andExpect(unauthenticated());
			release.countDown();
			first.get(5, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}
		verify(authenticator, times(1)).authenticate(id, "wrong");
	}

	@Test
	void doesNotCountLoginsInProgressAgainstPerIpLimit() throws Exception {
		String ip = "10.9.2.1";
		String waiting = "20050001";
		String other = "20050002";
		when(authenticator.authenticate(anyString(), eq("wrong")))
				.thenThrow(new SejongAuthException(SejongAuthException.Reason.INVALID_CREDENTIALS, "wrong"));
		CountDownLatch portalCalled = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(authenticator.authenticate(waiting, "pw")).thenAnswer(invocation -> {
			portalCalled.countDown();
			release.await(5, TimeUnit.SECONDS);
			return new StudentLogin(new SejongStudent(waiting, "홍길동"), TERM, List.of());
		});
		when(authenticator.authenticate(other, "pw"))
				.thenReturn(new StudentLogin(new SejongStudent(other, "김철수"), TERM, List.of()));

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			// IP 한도(30분에 10번)에 하나 모자라게 틀려 둔다
			for (int i = 0; i < 9; i++) {
				mvc.perform(loginFrom(ip, "2005%04d".formatted(100 + i), "wrong")).andExpect(unauthenticated());
			}
			// 한 강의실(같은 IP)에서 함께 로그인할 때: 포털 응답을 기다리는 로그인을 실패로 미리 세지 않는다
			Future<?> first = executor.submit(() -> {
				mvc.perform(loginFrom(ip, waiting, "pw")).andExpect(authenticated());
				return null;
			});
			assertThat(portalCalled.await(5, TimeUnit.SECONDS)).isTrue();
			mvc.perform(loginFrom(ip, other, "pw")).andExpect(authenticated());
			release.countDown();
			first.get(5, TimeUnit.SECONDS);

			// 비밀번호가 틀린 것은 센다: 열 번째에서 한도가 찬다
			mvc.perform(loginFrom(ip, "20050200", "wrong")).andExpect(unauthenticated());
			mvc.perform(loginFrom(ip, "20050201", "wrong")).andExpect(unauthenticated());
			verify(authenticator, never()).authenticate("20050201", "wrong");
		}
		finally {
			executor.shutdownNow();
			limiter.reset("student-all");
		}
	}

	@Test
	void rejectsNonNumericStudentIdWithoutCallingPortal() throws Exception {
		mvc.perform(formLogin("/login").user("admin").password("x")).andExpect(unauthenticated());

		verify(authenticator, never()).authenticate(anyString(), anyString());
	}

	@Test
	void listsOnlyTargetRoomCourses() throws Exception {
		mvc.perform(get("/consents").with(student(STUDENT)))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("과목000304-001")))
				.andExpect(content().string(Matchers.containsString("과목000305-001")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("과목000306-001"))));
	}

	@Test
	void submitsSeveralCoursesWithOneSignature() throws Exception {
		long first = enrollmentId(STUDENT, "000304-001");
		long second = enrollmentId(STUDENT, "000305-001");

		MvcResult done = mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf())
						.param("enrollmentIds", String.valueOf(first), String.valueOf(second))
						.param("agreed", "true")
						.param("signature", SignatureFixtures.dataUrl(SignatureFixtures.png())))
				.andExpect(redirectedUrl("/consents/complete"))
				.andReturn();
		mvc.perform(get("/consents/complete").with(student(STUDENT))
						.session((MockHttpSession) done.getRequest().getSession()))
				.andExpect(content().string(Matchers.containsString("과목000304-001")))
				.andExpect(content().string(Matchers.containsString("과목000305-001")));

		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from consent_document", Integer.class)).isEqualTo(2);
		mvc.perform(get("/consents").with(student(STUDENT)))
				.andExpect(content().string(Matchers.containsString("모든 수업의 동의서를 제출했습니다.")));

		// 이미 제출한 수업이 섞이면 아무것도 저장하지 않는다
		mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf())
						.param("enrollmentIds", String.valueOf(first))
						.param("agreed", "true")
						.param("signature", SignatureFixtures.dataUrl(SignatureFixtures.png())))
				.andExpect(redirectedUrl("/consents/sign"))
				.andExpect(flash().attribute("error", Matchers.containsString("이미 제출")));
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isEqualTo(2);
	}

	@Test
	void rejectsSubmissionWithTooManyCourses() throws Exception {
		String[] ids = java.util.stream.LongStream.rangeClosed(1, 31).mapToObj(String::valueOf).toArray(String[]::new);

		mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf())
						.param("enrollmentIds", ids)
						.param("agreed", "true")
						.param("signature", SignatureFixtures.dataUrl(SignatureFixtures.png())))
				.andExpect(redirectedUrl("/consents/sign"))
				.andExpect(flash().attributeExists("error"));
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isZero();
	}

	@Test
	void englishScreenStoresKoreanPdf() throws Exception {
		mvc.perform(get("/consents/sign").with(student(STUDENT)).cookie(new Cookie("lang", "en")))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("Retained for 60 days and then destroyed")))
				.andExpect(content().string(Matchers.containsString("Submit consent for 2 course(s)")));

		long enrollmentId = enrollmentId(STUDENT, "000304-001");
		mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf()).cookie(new Cookie("lang", "en"))
						.param("enrollmentIds", String.valueOf(enrollmentId))
						.param("agreed", "true")
						.param("signature", SignatureFixtures.dataUrl(SignatureFixtures.png())))
				.andExpect(redirectedUrl("/consents/complete"));

		assertThat(jdbc.queryForObject("select language from consent", String.class)).isEqualTo("EN");
		byte[] pdf = mvc.perform(get("/consents/" + enrollmentId + "/pdf").with(student(STUDENT)))
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf"))
				.andReturn().getResponse().getContentAsByteArray();
		try (var document = Loader.loadPDF(pdf)) {
			String text = new PDFTextStripper().getText(document);
			assertThat(text).contains("동의인").contains("홍길동").doesNotContain("Consent to CCTV Recording");
		}
	}

	@Test
	void englishScreenShowsEnglishPdfWithoutChangingStoredOne() throws Exception {
		long enrollmentId = enrollmentId(STUDENT, "000304-001");
		submit(STUDENT, enrollmentId);
		byte[] stored = jdbc.queryForObject("select pdf from consent_document", byte[].class);

		var response = mvc.perform(get("/consents/" + enrollmentId + "/pdf").with(student(STUDENT))
						.cookie(new Cookie("lang", "en")))
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf"))
				.andReturn().getResponse();
		assertThat(response.getHeader("Content-Disposition")).contains("CCTV_Consent_");
		try (var document = Loader.loadPDF(response.getContentAsByteArray())) {
			String text = new PDFTextStripper().getText(document);
			assertThat(text).contains("Consent to CCTV Recording").contains("홍길동");
		}

		// 저장한 공식 기록은 한글본 그대로다
		assertThat(jdbc.queryForObject("select pdf from consent_document", byte[].class)).isEqualTo(stored);
		mvc.perform(get("/consents/" + enrollmentId + "/pdf").with(student(STUDENT)))
				.andExpect(content().bytes(stored));
	}

	@Test
	void showsErrorsInChosenLanguage() throws Exception {
		long enrollmentId = enrollmentId(STUDENT, "000304-001");

		mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf()).cookie(new Cookie("lang", "en"))
						.param("enrollmentIds", String.valueOf(enrollmentId))
						.param("signature", SignatureFixtures.dataUrl(SignatureFixtures.png())))
				.andExpect(flash().attribute("error", "Check the box to agree."));
	}

	@Test
	void requiresCourseAgreementAndSignature() throws Exception {
		long enrollmentId = enrollmentId(STUDENT, "000304-001");

		mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf())
						.param("agreed", "true").param("signature", SignatureFixtures.dataUrl(SignatureFixtures.png())))
				.andExpect(flash().attribute("error", Matchers.containsString("수업")));
		mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf())
						.param("enrollmentIds", String.valueOf(enrollmentId))
						.param("signature", SignatureFixtures.dataUrl(SignatureFixtures.png())))
				.andExpect(flash().attribute("error", Matchers.containsString("동의합니다")));
		mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf())
						.param("enrollmentIds", String.valueOf(enrollmentId))
						.param("agreed", "true").param("signature", SignatureFixtures.dataUrl(SignatureFixtures.blankPng())))
				.andExpect(flash().attribute("error", Matchers.containsString("서명")));

		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isZero();
	}

	@Test
	void cannotSubmitAnotherStudentsEnrollment() throws Exception {
		long mine = enrollmentId(STUDENT, "000304-001");
		long others = enrollmentId(OTHER, "000304-001");

		mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf())
						.param("enrollmentIds", String.valueOf(mine), String.valueOf(others))
						.param("agreed", "true").param("signature", SignatureFixtures.dataUrl(SignatureFixtures.png())))
				.andExpect(status().isNotFound());
		mvc.perform(get("/consents/" + others + "/pdf").with(student(STUDENT))).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isZero();
	}

	@Test
	void landingOffersLanguageChoice() throws Exception {
		// 스크립트 없이도 고른 언어로 로그인 화면(/login?lang=en)으로 가는 GET 폼
		mvc.perform(get("/"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("action=\"/login\" method=\"get\"")))
				.andExpect(content().string(Matchers.containsString("name=\"lang\" value=\"en\"")))
				.andExpect(content().string(Matchers.containsString("data-cta-en=\"Continue\"")));
		mvc.perform(get("/login").param("lang", "en"))
				.andExpect(content().string(Matchers.containsString("Sejong portal password")));
	}

	@Test
	void consentPagesRequireStudentLogin() throws Exception {
		mvc.perform(get("/consents")).andExpect(redirectedUrl("/login"));
	}

	@Test
	void cachesVersionedStaticFilesButNeverPersonalPages() throws Exception {
		String html = mvc.perform(get("/login")).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
				.andReturn().getResponse().getContentAsString();
		java.util.regex.Matcher css = java.util.regex.Pattern.compile("/css/student-[0-9a-f]{32}\\.css").matcher(html);
		assertThat(css.find()).isTrue();
		mvc.perform(get(css.group()))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", Matchers.containsString("max-age=31536000")));

		long enrollmentId = enrollmentId(STUDENT, "000304-001");
		submit(STUDENT, enrollmentId);
		mvc.perform(get("/consents").with(student(STUDENT)))
				.andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
		mvc.perform(get("/consents/" + enrollmentId + "/pdf").with(student(STUDENT)))
				.andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
	}

	@Test
	void signFormPreselectsChosenCourse() throws Exception {
		long first = enrollmentId(STUDENT, "000304-001");
		long second = enrollmentId(STUDENT, "000305-001");

		mvc.perform(get("/consents/sign").param("enrollmentId", String.valueOf(second)).with(student(STUDENT)))
				.andExpect(status().isOk())
				.andExpect(content().string(checkedCourse(second)))
				.andExpect(content().string(Matchers.not(checkedCourse(first))));

		// 남의 수강이면 무시하고 모든 수업을 골라 둔다
		long others = enrollmentId(OTHER, "000304-001");
		mvc.perform(get("/consents/sign").param("enrollmentId", String.valueOf(others)).with(student(STUDENT)))
				.andExpect(status().isOk())
				.andExpect(content().string(checkedCourse(first)))
				.andExpect(content().string(checkedCourse(second)))
				.andExpect(content().string(Matchers.not(Matchers.containsString("value=\"" + others + "\""))));
	}

	/** 그 수강의 체크박스가 체크된 채로 그려졌는지. */
	private static org.hamcrest.Matcher<String> checkedCourse(long enrollmentId) {
		return Matchers.matchesPattern("(?s).*<input [^>]*value=\"" + enrollmentId + "\"[^>]*checked=\"checked\".*");
	}

	@Test
	void showsCompletionPageOnlyAfterSubmit() throws Exception {
		// 제출한 적이 없으면 내 수업으로
		mvc.perform(get("/consents/complete").with(student(STUDENT))).andExpect(redirectedUrl("/consents"));

		MockHttpSession session = new MockHttpSession();
		mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf()).session(session)
						.param("enrollmentIds", String.valueOf(enrollmentId(STUDENT, "000304-001")))
						.param("agreed", "true")
						.param("signature", SignatureFixtures.dataUrl(SignatureFixtures.png())))
				.andExpect(redirectedUrl("/consents/complete"));
		// 새로 고치거나 언어를 바꿔 다시 열어도 보인다
		for (String lang : List.of("ko", "en")) {
			mvc.perform(get("/consents/complete").param("lang", lang).with(student(STUDENT)).session(session))
					.andExpect(status().isOk())
					.andExpect(content().string(Matchers.containsString("과목000304-001")));
		}
		// 내 수업으로 가면 지운다
		mvc.perform(get("/consents").with(student(STUDENT)).session(session)).andExpect(status().isOk());
		mvc.perform(get("/consents/complete").with(student(STUDENT)).session(session))
				.andExpect(redirectedUrl("/consents"));
	}

	@Test
	void keepsChosenCoursesWhenSubmitIsRejected() throws Exception {
		long first = enrollmentId(STUDENT, "000304-001");
		long second = enrollmentId(STUDENT, "000305-001");

		MvcResult rejected = mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf())
						.param("enrollmentIds", String.valueOf(second))
						.param("agreed", "true")
						.param("signature", "data:image/png;base64,AAAA"))
				.andExpect(redirectedUrl("/consents/sign"))
				.andReturn();
		mvc.perform(get("/consents/sign").with(student(STUDENT)).flashAttrs(rejected.getFlashMap()))
				.andExpect(content().string(checkedCourse(second)))
				.andExpect(content().string(Matchers.not(checkedCourse(first))));
	}

	@Test
	void sendsBackToFormWhenPdfRenderIsBusy() throws Exception {
		long second = enrollmentId(STUDENT, "000305-001");
		doThrow(new ConsentPdfRenderer.RenderBusyException()).when(renderer)
				.render(any(), anyString(), any(), any());

		// 503 오류 화면이 아니라 작성 화면으로 돌아가 고른 수업을 남긴다
		MvcResult busy = mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf())
						.param("enrollmentIds", String.valueOf(second))
						.param("agreed", "true")
						.param("signature", SignatureFixtures.dataUrl(SignatureFixtures.png())))
				.andExpect(redirectedUrl("/consents/sign"))
				.andExpect(flash().attribute("error", Matchers.containsString("잠시 후")))
				.andReturn();
		mvc.perform(get("/consents/sign").with(student(STUDENT)).flashAttrs(busy.getFlashMap()))
				.andExpect(content().string(checkedCourse(second)));
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isZero();
	}

	private void submit(String studentId, long enrollmentId) throws Exception {
		mvc.perform(post("/consents/sign").with(student(studentId)).with(csrf())
						.param("enrollmentIds", String.valueOf(enrollmentId))
						.param("agreed", "true")
						.param("signature", SignatureFixtures.dataUrl(SignatureFixtures.png())))
				.andExpect(redirectedUrl("/consents/complete"));
	}

	private MockHttpSession login(String studentId) throws Exception {
		MvcResult result = mvc.perform(formLogin("/login").user(studentId).password("pw"))
				.andExpect(authenticated())
				.andReturn();
		return (MockHttpSession) result.getRequest().getSession(false);
	}

	private static SjptCourse sjptCourse(String code, String name, String schedule) {
		String[] parts = code.split("-");
		return new SjptCourse(parts[0], parts[1], code, name, "컴퓨터공학과", "전공필수", "3.0", schedule, "2026/2학기", "대학");
	}

	private long enrollmentId(String studentId, String courseCode) {
		return jdbc.queryForObject("""
				select e.id from enrollment e join course c on c.id = e.course_id
				where e.student_id = ? and c.code = ?""", Long.class, studentId, courseCode);
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor student(String studentId) {
		Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
				new StudentPrincipal(studentId, "홍길동"), null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
		return authentication(auth);
	}

}
