package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * local 프로필 전용: 테스트 학생(20990001)이 동의서를 써 볼 수 있도록 테스트 수업 2개에 수강 등록한다.
 * 앱을 시작할 때마다 확인하므로, 명부를 다시 올려 테스트 수강이 빠져도 다시 살아난다.
 */
@Component
@Profile("local")
class LocalTestEnrollments implements ApplicationRunner {

	private static final String STUDENT_ID = "20990001";

	private final SemesterRepository semesters;
	private final CourseRepository courses;
	private final EnrollmentRepository enrollments;

	LocalTestEnrollments(SemesterRepository semesters, CourseRepository courses, EnrollmentRepository enrollments) {
		this.semesters = semesters;
		this.courses = courses;
		this.enrollments = enrollments;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		Semester semester = semesters.findAll().stream()
				.filter(s -> !s.getEndsOn().isBefore(LocalDate.now()))
				.max((a, b) -> a.getYear() != b.getYear() ? Integer.compare(a.getYear(), b.getYear())
						: a.getTerm().compareTo(b.getTerm()))
				.orElseGet(() -> semesters.save(new Semester(LocalDate.now().getYear(), "2",
						LocalDate.of(LocalDate.now().getYear(), 12, 31))));

		List<Course> existing = courses.findBySemester(semester);
		for (String[] test : List.of(
				new String[] {"TEST-001", "테스트 실습1", "센B201"},
				new String[] {"TEST-002", "테스트 실습2", "센B206"})) {
			Course course = existing.stream().filter(c -> c.getCode().equals(test[0])).findFirst().orElseGet(() -> {
				Course created = new Course(semester, test[0]);
				created.update(test[1], "테스트교수", "월10:30-12:00", test[2]);
				return courses.save(created);
			});
			Enrollment enrollment = enrollments.findBySemester(semester).stream()
					.filter(e -> e.getCourse().getId().equals(course.getId()) && e.getStudentId().equals(STUDENT_ID))
					.findFirst()
					.orElseGet(() -> new Enrollment(course, STUDENT_ID));
			enrollment.update("테스트학생", "컴퓨터공학과", "4");
			enrollments.save(enrollment);
		}
	}

}
