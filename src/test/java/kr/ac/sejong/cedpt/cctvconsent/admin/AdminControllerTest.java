package kr.ac.sejong.cedpt.cctvconsent.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import kr.ac.sejong.cedpt.cctvconsent.PostgresTestSupport;
import kr.ac.sejong.cedpt.cctvconsent.roster.EnrollmentSync;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterImportService;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterTestFixtures;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest extends PostgresTestSupport {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EnrollmentSync sync;

	@BeforeEach
	void clean() {
		jdbc.execute("TRUNCATE semester CASCADE");
	}

	@Test
	void adminPagesRequireLogin() throws Exception {
		mvc.perform(get("/admin"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
		// 예전 관리자 로그인 주소도 같은 로그인 화면으로 보낸다
		mvc.perform(get("/admin/login")).andExpect(redirectedUrl("/login"));
	}

	@Test
	void separatesAdminAndStudentPages() throws Exception {
		mvc.perform(get("/admin").with(user("20010001").roles("STUDENT"))).andExpect(status().isForbidden());
		mvc.perform(get("/consents").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
	}

	@Test
	void publicPagesAreOpen() throws Exception {
		mvc.perform(get("/")).andExpect(status().isOk());
		mvc.perform(get("/login")).andExpect(status().isOk());
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void unknownPathsAreDenied() throws Exception {
		mvc.perform(get("/actuator/env").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
	}

	@Test
	void logsInWithAdminAccount() throws Exception {
		// 학생과 같은 /login에서, 영문자가 있는 아이디는 관리자 계정으로 확인한다
		mvc.perform(formLogin("/login").user(ADMIN_USERNAME).password(ADMIN_PASSWORD))
				.andExpect(authenticated().withRoles("ADMIN"))
				.andExpect(redirectedUrl("/admin"));
		MvcResult failed = mvc.perform(formLogin("/login").user(ADMIN_USERNAME).password("wrong"))
				.andExpect(unauthenticated())
				.andExpect(redirectedUrl("/login?error"))
				.andReturn();
		mvc.perform(get("/login").session((MockHttpSession) failed.getRequest().getSession()))
				.andExpect(content().string(Matchers.containsString("아이디 또는 비밀번호가 올바르지 않습니다.")));
	}

	@Test
	void countsEveryLetterUsernameFailureAgainstAdminLimit() throws Exception {
		// 관리자 아이디가 아닌 영문 아이디도 같은 IP 제한에 세므로, 잠금 메시지로 관리자 아이디를 알아낼 수 없다
		for (int i = 0; i < 5; i++) {
			loginFrom("10.0.0.9", "nobody" + i, "pw").andExpect(unauthenticated());
		}
		MvcResult locked = loginFrom("10.0.0.9", "someone", "pw").andExpect(unauthenticated()).andReturn();
		mvc.perform(get("/login").session((MockHttpSession) locked.getRequest().getSession()))
				.andExpect(content().string(Matchers.containsString("15분 뒤 다시 시도하세요")));
		loginFrom("10.0.0.9", ADMIN_USERNAME, ADMIN_PASSWORD).andExpect(unauthenticated());

		// 다른 IP는 막지 않는다. 앞뒤 공백은 뗀다
		loginFrom("10.0.0.10", " " + ADMIN_USERNAME + " ", ADMIN_PASSWORD).andExpect(authenticated().withRoles("ADMIN"));
	}

	private org.springframework.test.web.servlet.ResultActions loginFrom(String ip, String username, String password)
			throws Exception {
		return mvc.perform(post("/login").param("username", username).param("password", password).with(csrf())
				.with(request -> {
					request.setRemoteAddr(ip);
					return request;
				}));
	}

	@Test
	void clearsEarlierFailureAfterSuccessfulLogin() throws Exception {
		MockHttpSession session = new MockHttpSession();
		mvc.perform(post("/login").session(session).param("username", ADMIN_USERNAME).param("password", "wrong")
				.with(csrf()));
		MvcResult ok = mvc.perform(post("/login").session(session).param("username", ADMIN_USERNAME)
						.param("password", ADMIN_PASSWORD).with(csrf()))
				.andExpect(redirectedUrl("/admin"))
				.andReturn();

		assertThat(((MockHttpSession) ok.getRequest().getSession())
				.getAttribute(org.springframework.security.web.WebAttributes.AUTHENTICATION_EXCEPTION)).isNull();
	}

	@Test
	void refusesAdminUsernameWithoutLetters() {
		assertThatThrownBy(() -> new SecurityConfig().adminAccount("12345678", "$2y$04$abc"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void sendsSecurityHeaders() throws Exception {
		mvc.perform(get("/login"))
				.andExpect(header().string("Content-Security-Policy", Matchers.containsString("script-src 'self'")))
				.andExpect(header().string("Content-Security-Policy", Matchers.containsString("frame-ancestors 'none'")))
				.andExpect(header().string("Referrer-Policy", "same-origin"));
	}

	@Test
	void uploadsRosterAndShowsSemester() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001", "20010002"),
				RosterTestFixtures.course("000305-001", "센B209", "20010003"))));

		mvc.perform(multipart("/admin/roster").file(file)
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(flash().attributeExists("result"));

		mvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("2026-2학기")))
				.andExpect(content().string(Matchers.containsString("2027-12-31")));
	}

	@Test
	void listsEnrollmentsFoundOnlyThroughStudentLogin() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001"))));
		mvc.perform(multipart("/admin/roster").file(file).with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"));
		// 명부에 있는 학생의 로그인은 명부에 없는 수강으로 세지 않는다
		sync.sync("20010001", "명부학생", 2026, "2", List.of(
				new EnrollmentSync.EnrolledCourse("000304-001", "과목000304-001", "김교수", "월10:30-12:00", "센B201")));
		sync.sync("20990007", "타과학생", 2026, "2", List.of(
				new EnrollmentSync.EnrolledCourse("009999-001", "타과과목", "이교수", "목10:00-12:00", "센B206")));

		mvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("학생 로그인으로만 확인된 수강이 1건")))
				.andExpect(content().string(Matchers.containsString("data-dialog=\"sjpt-only\"")))
				.andExpect(content().string(Matchers.containsString("<td class=\"tabular\">20990007</td>")))
				.andExpect(content().string(Matchers.containsString("<td>타과과목</td>")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("<td class=\"tabular\">20010001</td>"))));
	}

	@Test
	void showsSemesterCoursesAsListAndTimetable() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001", "20010002"),
				RosterTestFixtures.course("000400-001", "센B203", "20010003"))));
		mvc.perform(multipart("/admin/roster").file(file)
				.with(user("admin").roles("ADMIN")).with(csrf()));
		long semesterId = jdbc.queryForObject("select id from semester", Long.class);

		mvc.perform(get("/admin/semesters/{id}", semesterId).with(user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("미제출 3건")))
				.andExpect(content().string(Matchers.containsString("class=\"course-row\"")))
				.andExpect(content().string(Matchers.containsString("id=\"course-000304-001\"")))
				.andExpect(content().string(Matchers.containsString("20010002")))
				.andExpect(content().string(Matchers.containsString("학생 명단")))
				.andExpect(content().string(Matchers.containsString("class=\"status missing\"")));

		mvc.perform(get("/admin/semesters/{id}", semesterId).param("view", "timetable").param("room", "센B203")
						.with(user("admin").roles("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(content().string(Matchers.containsString("class=\"timetable-block has-missing\"")))
				.andExpect(content().string(Matchers.containsString("data-dialog=\"course-000400-001\"")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("class=\"course-row\""))));
	}

	@Test
	void downloadsOneCourseAsPdfAndSeveralAsZip() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001"),
				RosterTestFixtures.course("000400-001", "센B203", "20010003"))));
		mvc.perform(multipart("/admin/roster").file(file).with(user("admin").roles("ADMIN")).with(csrf()));
		long semesterId = jdbc.queryForObject("select id from semester", Long.class);

		mvc.perform(get("/admin/semesters/{id}", semesterId).with(user("admin").roles("ADMIN")))
				.andExpect(content().string(Matchers.containsString("name=\"codes\" value=\"000304-001\"")));

		byte[] zip = mvc.perform(post("/admin/semesters/{id}/consents/download", semesterId)
						.param("codes", "000304-001", "000400-001", "000304-001")
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/zip"))
				.andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment;")))
				.andExpect(header().string("Content-Disposition", Matchers.containsString(".zip")))
				.andReturn().getResponse().getContentAsByteArray();
		List<String> names = new java.util.ArrayList<>();
		try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
			for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				names.add(entry.getName());
				try (var document = org.apache.pdfbox.Loader.loadPDF(in.readAllBytes())) {
					assertThat(document.getNumberOfPages()).isEqualTo(2);
				}
			}
		}
		assertThat(names).containsExactly("01_000304-001_과목000304-001.pdf", "02_000400-001_과목000400-001.pdf");

		byte[] pdf = mvc.perform(post("/admin/semesters/{id}/consents/download", semesterId)
						.param("codes", "000400-001")
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf"))
				.andReturn().getResponse().getContentAsByteArray();
		try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
			assertThat(document.getNumberOfPages()).isEqualTo(2);
		}

		mvc.perform(post("/admin/semesters/{id}/consents/download", semesterId)
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/semesters/" + semesterId))
				.andExpect(flash().attribute("error", Matchers.containsString("과목을 고르세요")));
	}

	@Test
	void deletesCourseOnlyWhenCodeIsTypedAgain() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001"),
				RosterTestFixtures.course("000400-001", "센B203", "20010003"))));
		mvc.perform(multipart("/admin/roster").file(file)
				.with(user("admin").roles("ADMIN")).with(csrf()));
		long semesterId = jdbc.queryForObject("select id from semester", Long.class);

		mvc.perform(post("/admin/semesters/{id}/courses/delete", semesterId).param("code", "000304-001")
						.param("confirmCode", "000304-002").param("consentCount", "0")
						.param("view", "timetable").param("room", "센B201")
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/semesters/" + semesterId + "?view=timetable&room=%EC%84%BCB201"))
				.andExpect(flash().attributeExists("error"));
		assertThat(jdbc.queryForObject("select count(*) from course", Integer.class)).isEqualTo(2);

		mvc.perform(post("/admin/semesters/{id}/courses/delete", semesterId).param("code", "000304-001")
						.param("confirmCode", " 000304-001 ").param("consentCount", "0")
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/semesters/" + semesterId + "?view=list"))
				.andExpect(flash().attribute("notice", Matchers.containsString("000304-001")));
		assertThat(jdbc.queryForObject("select count(*) from course", Integer.class)).isEqualTo(1);
	}

	@Test
	void resetsSemesterOnlyWithExactConfirmText() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001"))));
		mvc.perform(multipart("/admin/roster").file(file).with(user("admin").roles("ADMIN")).with(csrf()));
		long semesterId = jdbc.queryForObject("select id from semester", Long.class);
		mvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
				.andExpect(content().string(Matchers.containsString("data-expected=\"2026-2학기 초기화\"")));

		mvc.perform(post("/admin/semesters/{id}/reset", semesterId).param("confirmText", "2026-2학기")
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin"))
				.andExpect(flash().attribute("error", Matchers.containsString("2026-2학기 초기화")));
		assertThat(jdbc.queryForObject("select count(*) from semester", Integer.class)).isEqualTo(1);

		mvc.perform(post("/admin/semesters/{id}/reset", semesterId).param("confirmText", " 2026-2학기 초기화 ")
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin"))
				.andExpect(flash().attribute("notice", Matchers.containsString("초기화했습니다")));
		assertThat(jdbc.queryForObject("select count(*) from semester", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from roster_upload", Integer.class)).isZero();

		mvc.perform(post("/admin/semesters/{id}/reset", semesterId).param("confirmText", "2026-2학기 초기화")
						.with(user("20010001").roles("STUDENT")).with(csrf()))
				.andExpect(status().isForbidden());
	}

	@Test
	void uploadsSeveralRosterFilesTogether() throws Exception {
		MockMultipartFile first = new MockMultipartFile("file", "소융.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001"))));
		MockMultipartFile second = new MockMultipartFile("file", "AI융.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000400-001", "센B203", "20010002"))));

		mvc.perform(multipart("/admin/roster").file(first).file(second)
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(result -> assertThat(((RosterImportService.Result) result.getFlashMap().get("result"))
						.courseCount()).isEqualTo(2));
	}

	@Test
	void asksBeforeDeletingConsentsOfStudentsMissingFromRoster() throws Exception {
		sync.sync("20010009", "정정취소", 2026, "2", List.of(
				new EnrollmentSync.EnrolledCourse("000304-001", "과목000304-001", "김교수", "월10:30-12:00", "센B201")));
		long enrollmentId = jdbc.queryForObject("select id from enrollment", Long.class);
		long consentId = jdbc.queryForObject("""
				insert into consent (enrollment_id, language, signer_name, signed_at)
				values (?, 'KO', '정정취소', now()) returning id""", Long.class, enrollmentId);
		jdbc.update("insert into consent_document (consent_id, signature_png, pdf) values (?, ?, ?)",
				consentId, new byte[] {1}, new byte[] {1});
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, RosterTestFixtures.roster(List.of(
				RosterTestFixtures.course("000304-001", "센B201", "20010001"))));
		MockHttpSession session = new MockHttpSession();

		mvc.perform(multipart("/admin/roster").file(file).session(session)
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(flash().attributeCount(0));
		mvc.perform(get("/admin/roster").session(session).with(user("admin").roles("ADMIN")))
				.andExpect(content().string(Matchers.containsString("동의서 1건이 삭제됩니다")))
				.andExpect(content().string(Matchers.containsString("20010009")));
		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isEqualTo(1);

		mvc.perform(post("/admin/roster/confirm").session(session).with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(flash().attributeExists("result"));

		assertThat(jdbc.queryForObject("select count(*) from consent", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from enrollment where student_id = '20010009'", Integer.class))
				.isZero();
		assertThat(session.getAttribute("pendingRoster")).isNull();

		// 확인을 두 번 보내도 한 번만 반영된다
		mvc.perform(post("/admin/roster/confirm").session(session).with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(flash().attribute("error", Matchers.containsString("확인할 업로드가 없습니다")));
		assertThat(jdbc.queryForObject("select count(*) from roster_upload", Integer.class)).isEqualTo(1);
	}

	@Test
	void cancelDropsPendingRoster() throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute("pendingRoster", "보류 중");

		mvc.perform(post("/admin/roster/cancel").session(session).with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"));

		assertThat(session.getAttribute("pendingRoster")).isNull();
	}

	@Test
	void showsParseErrorForInvalidRoster() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, "not xlsx".getBytes());

		mvc.perform(multipart("/admin/roster").file(file)
						.with(user("admin").roles("ADMIN")).with(csrf()))
				.andExpect(redirectedUrl("/admin/roster"))
				.andExpect(flash().attribute("error", Matchers.containsString("xlsx")));
	}

	@Test
	void rejectsUploadWithoutCsrfToken() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "명부.xlsx", null, new byte[] {1});

		mvc.perform(multipart("/admin/roster").file(file)
						.with(user("admin").roles("ADMIN")))
				.andExpect(status().isForbidden());
	}

}
