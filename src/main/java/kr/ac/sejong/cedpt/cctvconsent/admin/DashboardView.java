package kr.ac.sejong.cedpt.cctvconsent.admin;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService.CourseSummary;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService.SemesterSummary;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService.SjptOnlyEnrollment;
import kr.ac.sejong.cedpt.cctvconsent.roster.RosterQueryService.StudentSummary;
import kr.ac.sejong.cedpt.cctvconsent.roster.TargetRooms;

/**
 * 관리자 첫 화면의 학기 요약: 전체 제출률, 동의서를 아직 다 내지 않은 학생 수, 강의실별 제출률, 제출률 낮은 강의, 데이터 점검.
 * 수는 모두 수강 중인 수강(최근 명부 또는 sjpt에서 확인됨) 기준이다.
 *
 * @param progress 학생 기준 진행 상황
 * @param lagging 제출률이 낮은 강의, 낮은 순으로 최대 LAGGING_LIMIT개(모두 낸 강의는 뺀다)
 * @param sjptOnly 명부에 없고 학생 로그인으로만 확인된 수강
 * @param lastRosterUploadAt 이 학기에 마지막으로 명부를 반영한 시각, 없으면 null
 */
record DashboardView(SemesterSummary semester, long total, long submitted, int rate, StudentProgress progress,
		List<RoomRate> rooms, List<CourseRate> lagging, List<SjptOnlyEnrollment> sjptOnly,
		List<CourseSummary> unscheduled, OffsetDateTime lastRosterUploadAt) {

	static final int LAGGING_LIMIT = 6;

	record RoomRate(String room, long total, long submitted, int rate) {
	}

	record CourseRate(CourseSummary course, int unsubmitted, int rate) {
	}

	/**
	 * 학생 한 명씩 나눈 진행 상황. 로그인 기록을 따로 남기지 않으므로, 이번 학기 수강 중 하나라도 학생 로그인(sjpt)에서
	 * 확인됐거나 동의서를 하나라도 냈으면 로그인한 학생으로 본다.
	 *
	 * @param notLoggedIn 동의서를 다 내지 않았고 로그인이 확인되지 않은 학생
	 * @param partial 로그인했지만 동의서를 다 내지 않은 학생
	 * @param done 수강 중인 강의의 동의서를 모두 낸 학생
	 */
	record StudentProgress(int notLoggedIn, int partial, int done) {

		/** 수강 중인 강의 중 하나라도 동의서를 내지 않은 학생 수 */
		public int pending() {
			return notLoggedIn + partial;
		}

		public int total() {
			return notLoggedIn + partial + done;
		}

	}

	static DashboardView of(SemesterSummary semester, List<CourseSummary> courses,
			Function<String, List<TargetRooms.Room>> targetRooms, List<SjptOnlyEnrollment> sjptOnly,
			OffsetDateTime lastRosterUploadAt) {
		long total = courses.stream().mapToLong(CourseSummary::studentCount).sum();
		long submitted = total - courses.stream().mapToLong(CourseSummary::unsubmittedCount).sum();

		Set<String> students = new HashSet<>();
		Set<String> pending = new HashSet<>();
		Set<String> loggedIn = new HashSet<>();
		for (CourseSummary course : courses) {
			for (StudentSummary student : course.students()) {
				students.add(student.studentId());
				if (!student.submitted()) {
					pending.add(student.studentId());
				}
				if (student.inSjpt() || student.submitted()) {
					loggedIn.add(student.studentId());
				}
			}
		}
		int partial = (int) pending.stream().filter(loggedIn::contains).count();
		StudentProgress progress = new StudentProgress(pending.size() - partial, partial,
				students.size() - pending.size());

		List<RoomRate> rooms = SemesterPage.groupByRoom(courses, targetRooms).stream()
				.map(group -> new RoomRate(group.room(), group.studentCount(),
						group.studentCount() - group.unsubmittedCount(),
						rate(group.studentCount() - group.unsubmittedCount(), group.studentCount())))
				.toList();
		List<CourseRate> lagging = courses.stream()
				.map(course -> {
					int unsubmitted = course.unsubmittedCount();
					return new CourseRate(course, unsubmitted, rate(course.studentCount() - unsubmitted,
							course.studentCount()));
				})
				.filter(course -> course.unsubmitted() > 0)
				.sorted(Comparator.comparingInt(CourseRate::rate)
						.thenComparing(Comparator.comparingInt(CourseRate::unsubmitted).reversed()))
				.limit(LAGGING_LIMIT)
				.toList();
		List<CourseSummary> unscheduled = courses.stream().filter(course -> course.times().isEmpty()).toList();
		return new DashboardView(semester, total, submitted, rate(submitted, total), progress, rooms, lagging, sjptOnly, unscheduled, lastRosterUploadAt);
	}

	/** 백분율, 내림. 대상이 없으면 0 */
	private static int rate(long part, long whole) {
		return whole == 0 ? 0 : (int) (part * 100 / whole);
	}

}
