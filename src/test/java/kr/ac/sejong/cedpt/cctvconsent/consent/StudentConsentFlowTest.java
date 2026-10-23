package kr.ac.sejong.cedpt.cctvconsent.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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
