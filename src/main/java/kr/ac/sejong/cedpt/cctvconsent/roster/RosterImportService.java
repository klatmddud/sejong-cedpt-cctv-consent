package kr.ac.sejong.cedpt.cctvconsent.roster;

import java.io.InputStream;
import java.time.LocalDate;
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
 * 기존 수강 행은 지우지 않으므로 이미 받은 동의는 유지된다.
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
			int addedCount, int removedCount) {
	}

	@Transactional
	public Result importRoster(String fileName, InputStream xlsx, LocalDate semesterEndsOn) {
		RosterFile roster = RosterParser.parse(xlsx);
		List<RosterFile.Course> targets = roster.courses().stream()
				.filter(course -> targetRooms.includes(course.room()))
				.toList();
		if (targets.isEmpty()) {
			throw new RosterParseException("대상 강의실을 쓰는 수업이 명부에 없습니다.");
		}

		Semester semester = semesters.findForUpdate(roster.year(), roster.term())
				.orElseGet(() -> semesters.save(new Semester(roster.year(), roster.term(), semesterEndsOn)));
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

		previouslyActive.removeAll(nowActive);
		int removed = previouslyActive.size();
		uploads.save(new RosterUpload(semester, fileName, targets.size(), enrollmentCount, added, removed));
		return new Result(roster.year(), roster.term(), targets.size(), enrollmentCount, students.size(), added, removed);
	}

	private static String key(String courseCode, String studentId) {
		return courseCode + "/" + studentId;
	}

}
