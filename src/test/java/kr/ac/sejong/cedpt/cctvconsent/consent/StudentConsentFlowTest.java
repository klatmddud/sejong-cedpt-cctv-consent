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
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.PostgresTestSupport;
import kr.ac.sejong.cedpt.cctvconsent.auth.SejongAuthException;
import kr.ac.sejong.cedpt.cctvconsent.auth.SejongStudent;
import kr.ac.sejong.cedpt.cctvconsent.auth.StudentAuthenticator;
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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class StudentConsentFlowTest extends PostgresTestSupport {

	private static final String STUDENT = "20010001";
	private static final String OTHER = "20010002";

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
		byte[] roster = RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", STUDENT, OTHER),
				RosterTestFixtures.course("000305-001", "센B206", STUDENT),
				RosterTestFixtures.course("000306-001", "센B209", STUDENT)));
		rosterImport.importRoster("명부.xlsx", new ByteArrayInputStream(roster), LocalDate.now().plusMonths(3));
	}

	@Test
	void logsInWithPortalAccount() throws Exception {
		when(authenticator.authenticate(STUDENT, "pw"))
				.thenReturn(new SejongStudent(STUDENT, "홍길동", "컴퓨터공학과", "2", "재학"));

		mvc.perform(formLogin("/login").user(STUDENT).password("pw"))
				.andExpect(authenticated().withRoles("STUDENT"))
				.andExpect(redirectedUrl("/consents"));
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

		mvc.perform(post("/consents/sign").with(student(STUDENT)).with(csrf())
						.param("enrollmentIds", String.valueOf(first), String.valueOf(second))
						.param("agreed", "true")
						.param("signature", SignatureFixtures.dataUrl(SignatureFixtures.png())))
				.andExpect(redirectedUrl("/consents"))
				.andExpect(flash().attribute("submitted", 2));

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
				.andExpect(redirectedUrl("/consents"));

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
		mvc.perform(get("/"))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("/login?lang=en")));
		mvc.perform(get("/login").param("lang", "en"))
				.andExpect(content().string(Matchers.containsString("Sejong portal password")));
	}

	@Test
	void consentPagesRequireStudentLogin() throws Exception {
		mvc.perform(get("/consents")).andExpect(redirectedUrl("/login"));
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
