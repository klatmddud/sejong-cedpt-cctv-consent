package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.io.InputStream;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수강명부를 DB에 반영한다. 대상 강의실 수업만 저장한다.
 *
 * 같은 학기 명부를 다시 올리면 새 학생을 추가하고, 명부에서 빠진 학생은 inLatestRoster=false로 표시한다.
 * 이전 명부에 있던 수강 행은 지우지 않으므로 이미 받은 동의는 유지된다.
 *
 * 명부는 수강정정 기간이 끝난 뒤 올리는 확정본이다. 명부에 있는 수업인데 그 수업 명단에 없는 수강은
 * - 한 번도 명부에 없었던 수강(학생 로그인 때 sjpt로만 확인된 수강): 정정 기간에 취소한 것으로 보고 동의서와 함께 삭제한다.
 * - 이전 명부에 있었던 수강: 지우지 않고 수강 중이 아닌 것으로 표시한다(inSjpt도 해제, 다시 로그인하면 sjpt 기준으로 갱신).
 * 명부에 없는 수업의 수강은 명부와 sjpt의 강의실 표기가 달랐을 수 있으므로 건드리지 않는다.
 * 삭제할 수강에 동의서가 있으면 관리자가 그 목록을 확인해야(confirmedDeletions) 반영한다.
 */
@Service
public class RosterImportService {

	private final SemesterRepository semesters;
	private final CourseRepository courses;
	private final EnrollmentRepository enrollments;
	private final RosterUploadRepository uploads;
	private final TargetRooms targetRooms;

	public RosterImportService(SemesterRepository semesters, CourseRepository courses,
			EnrollmentRepository enrollments, RosterUploadRepository uploads, TargetRooms targetRooms) {
		this.semesters = semesters;
		this.courses = courses;
		this.enrollments = enrollments;
		this.uploads = uploads;
		this.targetRooms = targetRooms;
	}

	public record Result(int year, String term, int courseCount, int enrollmentCount, int studentCount,
			int addedCount, int removedCount, int deletedCount, int deletedConsentCount) {
	}

	/** 동의서와 함께 삭제될 수강 */
	public record ConsentDeletion(long enrollmentId, String studentId, String studentName, String courseCode,
			String courseName) implements Serializable {
	}

	/** 삭제할 수강에 동의서가 있어 관리자 확인이 필요하다. 이 예외로 트랜잭션이 롤백되어 아무것도 반영되지 않는다. */
	public static class ConfirmationRequiredException extends RuntimeException {

		private final List<ConsentDeletion> deletions;

		ConfirmationRequiredException(List<ConsentDeletion> deletions) {
			super("명부에 없는 수강의 동의서 " + deletions.size() + "건이 삭제됩니다.");
			this.deletions = deletions;
		}

		public List<ConsentDeletion> getDeletions() {
			return deletions;
		}

	}

	/**
	 * @param confirmedDeletions 관리자가 확인한 동의서 삭제 목록(ConfirmationRequiredException으로 보여 준 것)
	 * @throws ConfirmationRequiredException 삭제할 동의서 중 확인하지 않은 것이 있음
	 */
	@Transactional
	public Result importRoster(String fileName, InputStream xlsx, LocalDate semesterEndsOn,
			Collection<ConsentDeletion> confirmedDeletions) {
		RosterFile roster = RosterParser.parse(xlsx);
		List<RosterFile.Course> targets = roster.courses().stream()
				.filter(course -> targetRooms.includes(course.room()))
				.toList();
		if (targets.isEmpty()) {
			throw new RosterParseException("대상 강의실을 쓰는 수업이 명부에 없습니다.");
		}

		// 같은 학기 첫 학생 로그인이 동시에 학기를 만들어도 충돌하지 않도록 EnrollmentSync와 같은 방식으로 만든다
		semesters.insertIfAbsent(roster.year(), roster.term(), semesterEndsOn, semesterEndsOn.plusYears(1));
		Semester semester = semesters.findForUpdate(roster.year(), roster.term()).orElseThrow();
		semester.changeEndsOn(semesterEndsOn);

		Map<String, Course> courseByCode = courses.findBySemester(semester).stream()
				.collect(Collectors.toMap(Course::getCode, Function.identity()));
		Map<String, Enrollment> enrollmentByKey = new HashMap<>();
		Set<String> previouslyActive = new HashSet<>();
		for (Enrollment enrollment : enrollments.findBySemester(semester)) {
			String key = key(enrollment.getCourse().getCode(), enrollment.getStudentId());
			enrollmentByKey.put(key, enrollment);
			if (enrollment.isInLatestRoster()) {
				previouslyActive.add(key);
			}
			enrollment.markRemovedFromRoster();
		}

		int added = 0;
		int enrollmentCount = 0;
		Set<String> students = new HashSet<>();
		Set<String> nowActive = new HashSet<>();
		for (RosterFile.Course target : targets) {
			Course course = courseByCode.get(target.code());
			if (course == null) {
				course = new Course(semester, target.code());
				course.update(target.name(), target.professor(), target.schedule(), target.room());
				courses.save(course);
				courseByCode.put(target.code(), course);
			}
			else {
				course.update(target.name(), target.professor(), target.schedule(), target.room());
			}

			for (RosterFile.Student student : target.students()) {
				String key = key(target.code(), student.studentId());
				Enrollment enrollment = enrollmentByKey.get(key);
				if (enrollment == null) {
					enrollment = new Enrollment(course, student.studentId());
					enrollment.update(student.name(), student.department(), student.grade());
					enrollments.save(enrollment);
				}
				else {
					enrollment.update(student.name(), student.department(), student.grade());
				}
				// 명부에서 빠졌다가 다시 들어온 학생도 추가로 센다
				if (!previouslyActive.contains(key)) {
					added++;
				}
				nowActive.add(key);
				students.add(student.studentId());
				enrollmentCount++;
			}
		}

		Set<String> rosterCodes = courseCodes(targets);
		List<Enrollment> cancelled = new ArrayList<>();
		enrollmentByKey.forEach((key, enrollment) -> {
			if (!rosterCodes.contains(enrollment.getCourse().getCode()) || nowActive.contains(key)) {
				return;
			}
			if (enrollment.isEverInRoster()) {
				enrollment.markNotInSjpt();
			}
			else {
				cancelled.add(enrollment);
			}
		});

		Set<Long> signed = new HashSet<>();
		if (!cancelled.isEmpty()) {
			List<Long> ids = cancelled.stream().map(Enrollment::getId).toList();
			// 지우기 전에 잠가서, 확인하는 사이 학생이 동의서를 내면 그 동의서가 확인 없이 지워지지 않게 한다
			enrollments.lockByIds(ids);
			signed.addAll(enrollments.findSignedIds(ids));
		}
		Set<Long> confirmed = confirmedDeletions.stream().map(ConsentDeletion::enrollmentId).collect(Collectors.toSet());
		if (!confirmed.containsAll(signed)) {
			throw new ConfirmationRequiredException(cancelled.stream()
					.filter(enrollment -> signed.contains(enrollment.getId()))
					.map(enrollment -> new ConsentDeletion(enrollment.getId(), enrollment.getStudentId(),
							enrollment.getStudentName(), enrollment.getCourse().getCode(), enrollment.getCourse().getName()))
					.toList());
		}
		// consent, consent_document는 ON DELETE CASCADE로 함께 삭제된다
		enrollments.deleteAll(cancelled);

		previouslyActive.removeAll(nowActive);
		int removed = previouslyActive.size();
		uploads.save(new RosterUpload(semester, fileName, targets.size(), enrollmentCount, added, removed,
				cancelled.size(), signed.size()));
		return new Result(roster.year(), roster.term(), targets.size(), enrollmentCount, students.size(), added, removed,
				cancelled.size(), signed.size());
	}

	private static Set<String> courseCodes(List<RosterFile.Course> courses) {
		return courses.stream().map(RosterFile.Course::code).collect(Collectors.toSet());
	}

	private static String key(String courseCode, String studentId) {
		return courseCode + "/" + studentId;
	}

}
