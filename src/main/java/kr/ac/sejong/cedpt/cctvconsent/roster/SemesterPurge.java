package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파기 예정일(학기 종료 1년 뒤)이 된 학기를 매일 새벽 4시에 지운다. 동의서에 고지한 보유 기간을 지키기 위한 것이다.
 * 과목, 수강, 동의서(서명, PDF), 명부 업로드 이력이 ON DELETE CASCADE로 함께 지워진다.
 * 서버가 그 시각에 꺼져 있었으면 다음 날 지운다.
 * 관리자가 학기를 초기화할 때(reset)도 같은 방식으로 그 학기 전체를 지운다.
 */
@Component
public class SemesterPurge {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final Logger audit = LoggerFactory.getLogger("audit");

	private final JdbcTemplate jdbc;

	SemesterPurge(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 초기화로 지운 학기와 건수 */
	public record Reset(String label, int courseCount, int enrollmentCount, int consentCount) {
	}

	/** 초기화하지 않았다. 학기가 없거나(다른 관리자가 먼저 지웠을 수 있다) 확인 문구가 다르다. 메시지는 화면에 그대로 보인다. */
	public static class ResetRefusedException extends RuntimeException {

		ResetRefusedException(String message) {
			super(message);
		}

	}

	/**
	 * 학기 하나와 그 과목, 수강, 동의서(서명, PDF), 명부 업로드 이력을 모두 지운다. 되돌릴 수 없다.
	 * 명부 업로드, 학생 로그인 동기화와 겹치지 않도록 학기 행을 잠근 뒤 지운다.
	 *
	 * @param confirmText "2026-2학기 초기화"처럼 학기 이름과 "초기화". 앞뒤 공백은 무시한다
	 */
	@Transactional
	public Reset reset(long semesterId, String confirmText) {
		List<String> labels = jdbc.queryForList(
				"select year || '-' || term || '학기' from semester where id = ? for update", String.class, semesterId);
		if (labels.isEmpty()) {
			throw new ResetRefusedException("학기를 찾을 수 없습니다. 이미 초기화되었을 수 있습니다.");
		}
		String expected = labels.get(0) + " 초기화";
		if (confirmText == null || !expected.equals(confirmText.strip())) {
			throw new ResetRefusedException(
					"입력한 문구가 \"" + expected + "\"와(과) 다릅니다. 학기를 초기화하지 않았습니다.");
		}
		int courses = count("select count(*) from course where semester_id = ?", semesterId);
		int enrollments = count("""
				select count(*) from enrollment e join course c on c.id = e.course_id
				where c.semester_id = ?""", semesterId);
		int consents = count("""
				select count(*) from consent s
				join enrollment e on e.id = s.enrollment_id
				join course c on c.id = e.course_id
				where c.semester_id = ?""", semesterId);
		jdbc.update("delete from semester where id = ?", semesterId);
		return new Reset(labels.get(0), courses, enrollments, consents);
	}

	private int count(String sql, long semesterId) {
		Integer count = jdbc.queryForObject(sql, Integer.class, semesterId);
		return count == null ? 0 : count;
	}

	@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
	public void purgeExpired() {
		purgeExpired(LocalDate.now(SEOUL));
	}

	/** @return 지운 학기 수 */
	int purgeExpired(LocalDate today) {
		Integer consents = jdbc.queryForObject("""
				select count(*) from consent s
				join enrollment e on e.id = s.enrollment_id
				join course c on c.id = e.course_id
				join semester m on m.id = c.semester_id
				where m.purge_on <= ?""", Integer.class, today);
		int semesters = jdbc.update("delete from semester where purge_on <= ?", today);
		if (semesters > 0) {
			audit.info("파기: 파기 예정일이 지난 학기 {}개, 동의서 {}건 삭제", semesters, consents);
		}
		return semesters;
	}

}
