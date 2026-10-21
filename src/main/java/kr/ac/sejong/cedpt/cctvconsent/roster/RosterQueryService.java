package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 화면용 조회. 학생 수는 가장 최근 명부에 있는 학생(inLatestRoster)만 센다.
 */
@Service
@Transactional(readOnly = true)
public class RosterQueryService {

	public record SemesterSummary(Long id, int year, String term, LocalDate endsOn, LocalDate purgeOn,
			int courseCount, long enrollmentCount) {

		public String label() {
			return year + "-" + term + "학기";
		}

	}

	public record CourseSummary(String code, String name, String professor, String schedule, String room,
			long studentCount) {
	}

	public record UploadSummary(String semester, String fileName, OffsetDateTime uploadedAt, int courseCount,
			int enrollmentCount, int addedCount, int removedCount) {
	}

	private final SemesterRepository semesters;
	private final CourseRepository courses;
	private final EnrollmentRepository enrollments;
	private final RosterUploadRepository uploads;

	public RosterQueryService(SemesterRepository semesters, CourseRepository courses,
			EnrollmentRepository enrollments, RosterUploadRepository uploads) {
		this.semesters = semesters;
		this.courses = courses;
		this.enrollments = enrollments;
		this.uploads = uploads;
	}

	public List<SemesterSummary> semesters() {
		return semesters.findAll(Sort.by(Sort.Direction.DESC, "year", "term")).stream()
				.map(this::summarize)
				.toList();
	}

	public Optional<SemesterSummary> semester(long id) {
		return semesters.findById(id).map(this::summarize);
	}

	public List<CourseSummary> courses(long semesterId) {
		Semester semester = semesters.findById(semesterId).orElseThrow();
		Map<Long, Long> counts = enrollments.countActiveByCourse(semester).stream()
				.collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
		return courses.findBySemester(semester).stream()
				.sorted(Comparator.comparing(Course::getRoom).thenComparing(Course::getCode))
				.map(course -> new CourseSummary(course.getCode(), course.getName(), course.getProfessor(),
						course.getSchedule(), course.getRoom(), counts.getOrDefault(course.getId(), 0L)))
				.toList();
	}

	public List<UploadSummary> recentUploads() {
		return uploads.findTop10ByOrderByUploadedAtDesc().stream()
				.map(upload -> new UploadSummary(
						upload.getSemester().getYear() + "-" + upload.getSemester().getTerm() + "학기",
						upload.getFileName(), upload.getUploadedAt(), upload.getCourseCount(),
						upload.getEnrollmentCount(), upload.getAddedCount(), upload.getRemovedCount()))
				.toList();
	}

	private SemesterSummary summarize(Semester semester) {
		return new SemesterSummary(semester.getId(), semester.getYear(), semester.getTerm(), semester.getEndsOn(),
				semester.getPurgeOn(), courses.findBySemester(semester).size(),
				enrollments.countActiveBySemester(semester));
	}

}
