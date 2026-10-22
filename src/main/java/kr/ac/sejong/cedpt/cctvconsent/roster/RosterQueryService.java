package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 화면용 조회. 학생 수는 수강 중인 학생(최근 명부 또는 sjpt에서 확인됨)만 센다.
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

	/**
	 * @param times 시간표 보기용 칸. 강의시간 형식을 읽지 못하면 비어 있다.
	 * @param students 수강 중 학생과 동의서 제출 여부, 학번 순
	 * @param enrollmentCount 수강 중이 아닌 것까지 포함한 수강 행 수(과목을 지우면 함께 지워진다)
	 * @param consentCount 받은 동의서 수(과목을 지우면 함께 지워진다)
	 */
	public record CourseSummary(String code, String name, String professor, String schedule, String room,
			long studentCount, List<CourseTime> times, List<StudentSummary> students, long enrollmentCount,
			long consentCount) {

		public int unsubmittedCount() {
			return (int) students.stream().filter(student -> !student.submitted()).count();
		}

	}

	/** @param inSjpt 학생 로그인(sjpt) 시간표에서 확인된 수강. 학생이 이번 학기에 로그인했다는 뜻이다 */
	public record StudentSummary(String studentId, String name, boolean submitted, boolean inSjpt) {
	}

	/** 명부에 없고 학생 로그인(sjpt)으로만 확인된 수강 */
	public record SjptOnlyEnrollment(String studentId, String studentName, String courseName, String code,
			String professor, String room, String schedule) {
	}

	public record UploadSummary(String semester, String fileName, OffsetDateTime uploadedAt, int courseCount,
			int enrollmentCount, int addedCount, int removedCount, int deletedCount, int deletedConsentCount) {
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

	/** 학기가 없으면(방금 초기화, 파기된 경우) 비어 있다. */
	public List<CourseSummary> courses(long semesterId) {
		Semester semester = semesters.findById(semesterId).orElse(null);
		if (semester == null) {
			return List.of();
		}
		Map<Long, Long> counts = enrollments.countActiveByCourse(semester).stream()
				.collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
		Map<Long, List<StudentSummary>> students = enrollments.findActiveStudents(semesterId).stream()
				.collect(Collectors.groupingBy(row -> ((Number) row[0]).longValue(),
						Collectors.mapping(
								row -> new StudentSummary((String) row[1], (String) row[2], (Boolean) row[3],
										(Boolean) row[4]),
								Collectors.toList())));
		Map<Long, Object[]> all = enrollments.countAllByCourse(semesterId).stream()
				.collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), Function.identity()));
		return courses.findBySemester(semester).stream()
				.sorted(Comparator.comparing(Course::getRoom).thenComparing(Course::getCode))
				.map(course -> new CourseSummary(course.getCode(), course.getName(), course.getProfessor(),
						course.getSchedule(), course.getRoom(), counts.getOrDefault(course.getId(), 0L),
						CourseTime.parse(course.getSchedule()), students.getOrDefault(course.getId(), List.of()),
						count(all, course, 1), count(all, course, 2)))
				.toList();
	}

	private static long count(Map<Long, Object[]> rows, Course course, int column) {
		Object[] row = rows.get(course.getId());
		return row == null ? 0 : ((Number) row[column]).longValue();
	}

	/** 명부 없이 학생 로그인(sjpt)으로만 확인된 수강 중인 수강, 강의실, 학수번호, 학번 순 */
	public List<SjptOnlyEnrollment> sjptOnlyEnrollments(long semesterId) {
		return enrollments.findSjptOnly(semesterId).stream()
				.map(row -> new SjptOnlyEnrollment((String) row[0], (String) row[1], (String) row[2], (String) row[3],
						(String) row[4], (String) row[5], (String) row[6]))
				.toList();
	}

	/** 이 학기에 마지막으로 명부를 반영한 시각. 올린 적이 없으면 비어 있다. */
	public Optional<OffsetDateTime> lastRosterUploadAt(long semesterId) {
		return uploads.findTopBySemesterIdOrderByIdDesc(semesterId).map(RosterUpload::getUploadedAt);
	}

	public List<UploadSummary> recentUploads() {
		return uploads.findTop10ByOrderByUploadedAtDesc().stream()
				.map(upload -> new UploadSummary(
						upload.getSemester().getYear() + "-" + upload.getSemester().getTerm() + "학기",
						upload.getFileName(), upload.getUploadedAt(), upload.getCourseCount(),
						upload.getEnrollmentCount(), upload.getAddedCount(), upload.getRemovedCount(),
						upload.getDeletedCount(), upload.getDeletedConsentCount()))
				.toList();
	}

	private SemesterSummary summarize(Semester semester) {
		return new SemesterSummary(semester.getId(), semester.getYear(), semester.getTerm(), semester.getEndsOn(),
				semester.getPurgeOn(), (int) courses.countBySemester(semester),
				enrollments.countActiveBySemester(semester));
	}

}
