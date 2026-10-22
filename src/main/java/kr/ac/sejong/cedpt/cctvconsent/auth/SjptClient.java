package kr.ac.sejong.cedpt.cctvconsent.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 세종대 일반행정정보(sjpt.sejong.ac.kr)로 학생을 확인하고 학생 본인의 수강 과목 목록을 조회한다.
 *
 * 흐름: 포털 로그인 → sjpt SSO → 사용자 정보 → 상단/좌측 메뉴 조회 → 권한 초기화 → 수강내역조회.
 * sjpt는 WebSquare 기반 JSON API이며, 대부분 요청에 addParam(실행 메뉴, 사용자, 로그인 시각)이 있어야 한다.
 * 권한 초기화는 좌측 메뉴를 조회해 서버 세션에 메뉴 권한이 올라간 뒤에만 된다 (그 전에는 서버 NullPointerException).
 * 수강내역조회 메뉴 코드는 대학원생/학부생마다 다르므로(대학원생: SELF_STUD_GRADSELF_SUB_30SELF_MENU_10SueReqLesnQ)
 * 좌측 메뉴에서 화면 경로로 찾는다.
 * 세션, 쿠키, 리다이렉트 제한은 SejongSession을 따른다.
 */
@Component
public class SjptClient implements StudentAuthenticator {

	private static final URI SSO_URI = URI.create("https://sjpt.sejong.ac.kr/main/view/Login/doSsoLogin.do?p=");
	private static final URI USER_INFO_URI = URI.create("https://sjpt.sejong.ac.kr/main/sys/UserInfo/initUserInfo.do");
	private static final URI TOP_MENU_URI =
			URI.create("https://sjpt.sejong.ac.kr/main/view/Menu/doListUserMenuListTop.do");
	private static final URI LEFT_MENU_URI =
			URI.create("https://sjpt.sejong.ac.kr/main/view/Menu/doListUserMenuListLeft.do");
	private static final URI USER_ROLE_URI = URI.create("https://sjpt.sejong.ac.kr/main/sys/UserRole/initUserRole.do");
	private static final URI COURSE_LIST_URI =
			URI.create("https://sjpt.sejong.ac.kr/sch/sch/sue/SueReqLesnQ/doList.do");

	/** 수강내역조회 화면 경로. 좌측 메뉴의 PGM_FULL_PATH_URL과 비교해 메뉴 코드(KEY_PG_ID)를 찾는다. */
	private static final String COURSE_LIST_PAGE = "/sch/modules/sue/SueReqLesnQ.xml";
	/** 학사 시스템 구분 */
	private static final String SCH_SYSTEM = "SCH";

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Logger log = LoggerFactory.getLogger(SjptClient.class);

	private final Clock clock;

	@Autowired
	public SjptClient() {
		this(Clock.systemUTC());
	}

	SjptClient(Clock clock) {
		this.clock = clock;
	}

	/**
	 * sjpt 사용자 정보로 학번과 이름을 확인하고, 같은 세션으로 현재 학기 수강 과목을 조회한다.
	 * 수강 과목 조회만 실패하면 로그인은 성공시키고 courses를 null로 돌려준다.
	 */
	@Override
	public StudentLogin authenticate(String studentId, String password) {
		AcademicTerm term = AcademicTerm.current(clock);
		return inSession(studentId, password, (session, user) -> {
			SejongStudent student = new SejongStudent(user.userNo(), user.name());
			try {
				return new StudentLogin(student, term, courses(session, user, term));
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				log.warn("sjpt 수강 과목 조회 중단 ({}-{}학기)", term.year(), term.term());
				return new StudentLogin(student, term, null);
			}
			catch (SejongAuthException | IOException ex) {
				log.warn("sjpt 수강 과목 조회 실패 ({}-{}학기): {}", term.year(), term.term(), ex.getMessage());
				return new StudentLogin(student, term, null);
			}
			catch (RuntimeException ex) {
				// 응답 파싱 오류 등의 메시지에는 응답 내용(개인정보)이 들어갈 수 있어 예외 종류만 남긴다
				log.warn("sjpt 수강 과목 조회 실패 ({}-{}학기): {}", term.year(), term.term(), ex.getClass().getName());
				log.debug("sjpt 수강 과목 조회 실패", ex);
				return new StudentLogin(student, term, null);
			}
		});
	}

	/** 현재 학기(Asia/Seoul 기준) 수강 과목. */
	public List<SjptCourse> courses(String studentId, String password) {
		return courses(studentId, password, AcademicTerm.current(clock));
	}

	public List<SjptCourse> courses(String studentId, String password, AcademicTerm term) {
		return inSession(studentId, password, (session, user) -> courses(session, user, term));
	}

	/** sjpt에 들어간 세션으로 하는 작업 */
	@FunctionalInterface
	private interface SessionAction<T> {

		T run(SejongSession session, SjptUser user) throws IOException, InterruptedException;

	}

	/**
	 * @param loginKey 로그인 키 (RUNNING_SEJONG). addParam에 넣는 값이므로 로그에 남기지 않는다.
	 */
	private record SjptUser(String userNo, String loginKey, String name) {
	}

	/**
	 * 새 세션으로 포털에 로그인하고 sjpt에 들어가 작업을 실행한다.
	 * 학번, 비밀번호 형식은 호출하는 쪽(StudentAuthenticationProvider)이 먼저 확인한다.
	 */
	private static <T> T inSession(String studentId, String password, SessionAction<T> action) {
		String id = studentId.trim();

		try (SejongSession session = new SejongSession()) {
			session.login(id, password, SSO_URI.getHost());
			return action.run(session, enter(session, id));
		}
		catch (IOException ex) {
			throw new SejongAuthException(SejongAuthException.Reason.UNAVAILABLE, "세종대 서버에 연결할 수 없습니다.", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new SejongAuthException(SejongAuthException.Reason.UNAVAILABLE, "세종대 서버 요청이 중단되었습니다.", ex);
		}
	}

	/**
	 * 포털에 로그인한 세션으로 sjpt SSO에 들어가 사용자 정보를 받는다.
	 */
	private static SjptUser enter(SejongSession session, String studentId) throws IOException, InterruptedException {
		HttpResponse<String> sso = session.get(SSO_URI);
		if (!SSO_URI.getHost().equals(sso.uri().getHost())) {
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"sjpt SSO 로그인에 실패했습니다: " + sso.uri().getHost() + sso.uri().getPath());
		}

		JsonNode userInfo = call(session, USER_INFO_URI, null, Map.of()).path("dm_UserInfo");
		String userNo = text(userInfo, "INTG_USR_NO");
		String loginKey = text(userInfo, "RUNNING_SEJONG");
		String name = text(userInfo, "INTG_USR_NM");
		if (userNo.isEmpty() || loginKey.isEmpty() || name.isEmpty()) {
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"sjpt SSO 세션이 만들어지지 않았습니다 (사용자 정보 없음).");
		}
		if (!userNo.equals(studentId)) {
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"sjpt에서 조회된 학번이 입력한 학번과 다릅니다.");
		}
		return new SjptUser(userNo, loginKey, name);
	}

	/**
	 * sjpt에 들어간 세션으로 수강 과목을 조회한다.
	 */
	private static List<SjptCourse> courses(SejongSession session, SjptUser user, AcademicTerm term)
			throws IOException, InterruptedException {
		String userNo = user.userNo();
		String loginKey = user.loginKey();

		// 메뉴가 열리지 않은 메인 화면의 addParam: 실행 메뉴 없이 사용자와 시스템 로그인 시각만 보낸다
		Map<String, String> mainParams = new LinkedHashMap<>();
		mainParams.put("_runIntgUsrNo", userNo);
		mainParams.put("_runningSejong", loginKey);
		JsonNode top = call(session, TOP_MENU_URI, mainParams, Map.of("dm_CoMessage", Map.of()));
		String systemLoginAt = text(top.path("dm_UserInfo"), "SYS_LOGIN_DT");
		if (systemLoginAt.isEmpty()) {
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"sjpt 메뉴 응답에 로그인 시각(SYS_LOGIN_DT)이 없습니다.");
		}
		mainParams.put("_runPgLoginDt", systemLoginAt);
		String menuKey = findCourseListMenu(session, top.path("dl_UserMenuListTop"), mainParams);

		Map<String, String> runParams = new LinkedHashMap<>();
		runParams.put("_runPgmKey", menuKey);
		runParams.put("_runSysKey", SCH_SYSTEM);
		runParams.put("_runIntgUsrNo", userNo);
		runParams.put("_runningSejong", loginKey);
		runParams.put("_runPgLoginDt", systemLoginAt);

		Map<String, String> roleParams = new LinkedHashMap<>();
		roleParams.put("pbForceLog", "false");
		roleParams.putAll(runParams);
		JsonNode role = call(session, USER_ROLE_URI, roleParams, Map.of());
		String loginAt = text(role.path("dm_UserRole"), "PG_LOGIN_DT");
		String organization = text(role.path("dm_UserInfoSch"), "ORGN_CLSF_CD");
		if (loginAt.isEmpty() || organization.isEmpty()) {
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"sjpt 권한 초기화 응답에 로그인 시각(PG_LOGIN_DT) 또는 소속 구분(ORGN_CLSF_CD)이 없습니다.");
		}
		runParams.put("_runPgLoginDt", loginAt);

		Map<String, String> search = new LinkedHashMap<>();
		search.put("ORGN_CLSF_CD", organization);
		search.put("STUDENT_NO", userNo);
		search.put("YEAR_SMT", term.year() + term.smtCd());
		search.put("REQ_LOG_CD", "");
		search.put("CDT", "");
		search.put("CNT", "");
		search.put("YEAR", String.valueOf(term.year()));
		search.put("SMT_CD", term.smtCd());
		search.put("SMT_CD_NM", term.smtCdName());

		JsonNode rows = call(session, COURSE_LIST_URI, runParams, Map.of("dm_search", search)).path("dl_main");
		// 빈 배열은 수강 과목 없음이지만, dl_main 자체가 없으면 응답 형식이 바뀐 것이다
		if (!rows.isArray()) {
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"sjpt 수강내역 응답에 과목 목록(dl_main)이 없습니다.");
		}
		List<SjptCourse> courses = new ArrayList<>();
		for (JsonNode row : rows) {
			courses.add(new SjptCourse(
					text(row, "CURI_NO"),
					text(row, "CLASS"),
					text(row, "CURI_CLASS"),
					text(row, "CURI_NM"),
					text(row, "DEPT_ALIAS"),
					text(row, "CURI_TYPE_CD_NM"),
					text(row, "CDT"),
					text(row, "TIME_ALL"),
					text(row, "YEAR_SMT"),
					text(row, "ORGN_CLSF_NM")));
		}
		return courses;
	}

	/**
	 * 학사(SCH) 상단 메뉴마다 좌측 메뉴를 조회해 수강내역조회 화면의 메뉴 코드를 찾는다.
	 * 좌측 메뉴 조회는 서버 세션에 메뉴 권한을 올리므로 권한 초기화 전에 꼭 거쳐야 한다.
	 */
	private static String findCourseListMenu(SejongSession session, JsonNode topMenus, Map<String, String> mainParams)
			throws IOException, InterruptedException {
		for (JsonNode topMenu : topMenus) {
			if (!SCH_SYSTEM.equals(text(topMenu, "SYSTEM_DIV"))) {
				continue;
			}
			Map<String, String> request = new LinkedHashMap<>();
			request.put("MENU_SYS_ID", text(topMenu, "MENU_CD"));
			request.put("SYSTEM_DIV", SCH_SYSTEM);
			request.put("MENU_SYS_NM", text(topMenu, "MENU_NM"));
			JsonNode left = call(session, LEFT_MENU_URI, mainParams, Map.of("dm_ReqLeftMenu", request));
			String menuKey = courseListMenuKey(left.path("dl_UserMenuListLeft"));
			if (!menuKey.isEmpty()) {
				return menuKey;
			}
		}
		throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
				"sjpt 메뉴에서 수강내역조회 화면을 찾을 수 없습니다.");
	}

	/** 좌측 메뉴 목록에서 수강내역조회 화면의 메뉴 코드. 없으면 빈 문자열. */
	static String courseListMenuKey(JsonNode leftMenus) {
		for (JsonNode menu : leftMenus) {
			if (COURSE_LIST_PAGE.equals(text(menu, "PGM_FULL_PATH_URL"))) {
				return text(menu, "KEY_PG_ID");
			}
		}
		return "";
	}

	/**
	 * sjpt JSON API 호출. 응답에 _SUBMIT_ERROR_가 있으면 그 메시지로 예외를 던진다.
	 *
	 * @param runParams addParam으로 보낼 값, 없으면 null
	 */
	private static JsonNode call(SejongSession session, URI uri, Map<String, String> runParams, Object body)
			throws IOException, InterruptedException {
		URI target = runParams == null ? uri
				: URI.create(uri + "?addParam=" + SjptAddParam.encode(JSON.writeValueAsString(runParams)));
		HttpResponse<String> response = session.postJson(target, JSON.writeValueAsString(body));
		return parseResponse(uri.getPath(), response.body());
	}

	static JsonNode parseResponse(String path, String body) {
		JsonNode json;
		try {
			json = JSON.readTree(body);
		}
		catch (JacksonException ex) {
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"sjpt " + path + " 응답이 JSON이 아닙니다 (로그인 세션이 없을 수 있음).");
		}
		if (json == null || !json.isObject()) {
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"sjpt " + path + " 응답 형식이 예상과 다릅니다.");
		}
		JsonNode error = json.get("_SUBMIT_ERROR_");
		if (error != null) {
			String message = text(error, "ERRMSG");
			throw new SejongAuthException(SejongAuthException.Reason.UNEXPECTED_RESPONSE,
					"sjpt " + path + " 오류: " + (message.isEmpty() ? "알 수 없는 오류" : message));
		}
		return json;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? "" : value.asString().trim();
	}

}
