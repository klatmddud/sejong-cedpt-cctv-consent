package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학생이 로그인할 때 sjpt에서 받은 본인 수강 과목을 반영한다. 대상 강의실 수업만 저장한다.
 *
 * sjpt에 있는 수강은 inSjpt=true, 이 학생의 같은 학기 수강 중 sjpt에 없는 것은 inSjpt=false로 표시한다.
 * 명부 값(inLatestRoster)은 건드리지 않고, 수강 행을 지우지 않으므로 이미 받은 동의는 유지된다.
 * 명부에 없는 학기면 기본 종료일로 학기를 만든다. 나중에 명부를 올리면 관리자가 넣은 종료일로 바뀐다.
 * 명부 업로드가 학기 행을 잠그고 있으면 3초까지만 기다린다. 로그인이 DB 연결을 오래 잡아 두지 않게 하기 위해서이며,
 * 시간을 넘기면 예외가 나고 학생은 이전 수강 정보로 보게 된다.
 */
@Service
public class EnrollmentSync {

	/** sjpt 수강 과목 한 줄. room은 TargetRooms가 읽는 형식("센B201", "호302/센B205"). */
	public record EnrolledCourse(String code, String name, String professor, String schedule, String room) {
	}

	private static final int MAX_CODE_LENGTH = 20;

	private final SemesterRepository semesters;
	private final CourseRepository courses;
	private final EnrollmentRepository enrollments;
	private final TargetRooms targetRooms;
	private final JdbcTemplate jdbc;

	EnrollmentSync(SemesterRepository semesters, CourseRepository courses, EnrollmentRepository enrollments,
			TargetRooms targetRooms, JdbcTemplate jdbc) {
		this.semesters = semesters;
		this.courses = courses;
		this.enrollments = enrollments;
		this.targetRooms = targetRooms;
		this.jdbc = jdbc;
	}

	/**
	 * @param term "1" 또는 "2" (명부의 학기 표기와 같음)
	 */
	@Transactional
	public void sync(String studentId, String studentName, int year, String term, List<EnrolledCourse> enrolled) {
		Map<String, EnrolledCourse> targets = enrolled.stream()
				.filter(course -> targetRooms.includes(course.room()))
				// 학수번호는 과목을 찾는 키라 자를 수 없다. 칸(20자)에 맞지 않는 것은 형식이 다른 값으로 보고 뺀다
				.filter(course -> course.code() != null && course.code().length() <= MAX_CODE_LENGTH)
				.collect(Collectors.toMap(EnrolledCourse::code, Function.identity(), (first, second) -> first));
		if (targets.isEmpty() && semesters.findByYearAndTerm(year, term).isEmpty()) {
			return;
		}

		// 이 트랜잭션에만 적용된다
		jdbc.execute("SET LOCAL lock_timeout = '3s'");
		LocalDate endsOn = defaultEndsOn(year, term);
		semesters.insertIfAbsent(year, term, endsOn, endsOn.plusYears(1));
		Semester semester = semesters.findForUpdate(year, term).orElseThrow();

		// 학기 과목 전체가 아니라 이 학생이 듣는 과목만 읽는다(학기 행을 잠근 채로 도는 로그인 경로)
		Map<String, Course> courseByCode = targets.isEmpty() ? new HashMap<>()
				: courses.findBySemesterAndCodeIn(semester, targets.keySet()).stream()
						.collect(Collectors.toMap(Course::getCode, Function.identity()));
		Map<String, Enrollment> mine = enrollments.findBySemesterAndStudentId(semester, studentId).stream()
				.collect(Collectors.toMap(enrollment -> enrollment.getCourse().getCode(), Function.identity()));

		for (EnrolledCourse target : targets.values()) {
			// 과목 정보는 명부가 기준이므로 이미 있는 과목은 덮어쓰지 않는다
			Course course = courseByCode.computeIfAbsent(target.code(), code -> {
				Course created = new Course(semester, code);
				created.update(target.name(), target.professor(), target.schedule(), target.room());
				return courses.save(created);
			});
			Enrollment enrollment = mine.computeIfAbsent(target.code(), code -> new Enrollment(course, studentId));
			enrollment.confirmBySjpt(studentName);
			enrollments.save(enrollment);
		}

		Set<String> confirmed = targets.keySet();
		mine.forEach((code, enrollment) -> {
			if (!confirmed.contains(code)) {
				enrollment.markNotInSjpt();
			}
		});
	}

	/** 명부가 아직 없을 때 쓰는 학기 종료일: 1학기 8월 31일, 2학기 다음 해 2월 말일. */
	static LocalDate defaultEndsOn(int year, String term) {
		return "1".equals(term) ? LocalDate.of(year, 8, 31) : YearMonth.of(year + 1, 2).atEndOfMonth();
	}

}
