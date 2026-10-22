package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 파기 예정일(학기 종료 1년 뒤)이 된 학기를 매일 새벽 4시에 지운다. 동의서에 고지한 보유 기간을 지키기 위한 것이다.
 * 과목, 수강, 동의서(서명, PDF), 명부 업로드 이력이 ON DELETE CASCADE로 함께 지워진다.
 * 서버가 그 시각에 꺼져 있었으면 다음 날 지운다.
 */
@Component
public class SemesterPurge {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final Logger audit = LoggerFactory.getLogger("audit");

	private final JdbcTemplate jdbc;

	SemesterPurge(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
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
