package kr.ac.sejong.cedpt.cctvconsent.roster;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface SemesterRepository extends JpaRepository<Semester, Long> {

	Optional<Semester> findByYearAndTerm(int year, String term);

	/**
	 * 학기 행이 없으면 만든다. 같은 학기 첫 로그인이 동시에 와도 하나만 만들어진다.
	 */
	@Modifying
	@Query(value = """
			insert into semester (year, term, ends_on, purge_on, created_at)
			values (:year, :term, :endsOn, :purgeOn, now())
			on conflict (year, term) do nothing""", nativeQuery = true)
	void insertIfAbsent(int year, String term, LocalDate endsOn, LocalDate purgeOn);

	/** 같은 학기 명부 업로드, 학생 로그인 동기화가 동시에 오면 하나씩 처리되도록 학기 행을 잠근다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Semester s where s.year = :year and s.term = :term")
	Optional<Semester> findForUpdate(int year, String term);

}

interface CourseRepository extends JpaRepository<Course, Long> {

	List<Course> findBySemester(Semester semester);

	long countBySemester(Semester semester);

	Optional<Course> findBySemesterAndCode(Semester semester, String code);

	List<Course> findBySemesterAndCodeIn(Semester semester, Collection<String> codes);

	/** 수강, 동의서는 DB의 ON DELETE CASCADE로 지운다. 이미 읽은 수강 엔티티가 남지 않도록 영속성 컨텍스트를 비운다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Course c where c.id = :id")
	void deleteWithEnrollments(long id);

}

interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

	@Query("select e from Enrollment e join fetch e.course c where c.semester = :semester")
	List<Enrollment> findBySemester(Semester semester);

	@Query("select e from Enrollment e join fetch e.course c where c.semester = :semester and e.studentId = :studentId")
	List<Enrollment> findBySemesterAndStudentId(Semester semester, String studentId);

	/** 학생 제출(StudentEnrollmentRepository.lockOpenByIds)과 교착하지 않도록 모두 id 순서로 잠근다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Enrollment e where e.id in :ids order by e.id")
	List<Enrollment> lockByIds(Collection<Long> ids);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Enrollment e where e.course = :course order by e.id")
	List<Enrollment> lockByCourse(Course course);

	/** [course id, 수강 행 수(수강 중이 아닌 것 포함), 동의서 수]. 과목을 지울 때 함께 지워지는 양이다. */
	@Query(value = """
			select e.course_id, count(*), count(s.id) from enrollment e
			join course c on c.id = e.course_id
			left join consent s on s.enrollment_id = e.id
			where c.semester_id = :semesterId
			group by e.course_id""", nativeQuery = true)
	List<Object[]> countAllByCourse(long semesterId);

	/**
	 * 명부에 한 번도 없었고 학생 로그인(sjpt)으로만 확인된 수강 중인 수강
	 * [학번, 이름, 과목명, 학수번호, 담당교수, 강의실, 강의시간], 강의실, 학수번호, 학번 순. 명부 누락이나 타과 수업 확인용.
	 */
	@Query(value = """
			select e.student_id, e.student_name, c.name, c.code, c.professor, c.room, c.schedule
			from enrollment e join course c on c.id = e.course_id
			where c.semester_id = :semesterId and e.in_sjpt and not e.ever_in_roster
			order by c.room, c.code, e.student_id""", nativeQuery = true)
	List<Object[]> findSjptOnly(long semesterId);

	/** 주어진 수강 중 동의서가 있는 것의 id. consent 패키지에 의존하지 않도록 테이블을 직접 읽는다. */
	@Query(value = "select enrollment_id from consent where enrollment_id in (:enrollmentIds)", nativeQuery = true)
	List<Long> findSignedIds(Collection<Long> enrollmentIds);

	@Query("""
			select count(e) from Enrollment e
			where e.course.semester = :semester and (e.inLatestRoster = true or e.inSjpt = true)""")
	long countActiveBySemester(Semester semester);

	/** [course id, 학생 수] */
	@Query("""
			select e.course.id, count(e) from Enrollment e
			where e.course.semester = :semester and (e.inLatestRoster = true or e.inSjpt = true)
			group by e.course.id""")
	List<Object[]> countActiveByCourse(Semester semester);

	/**
	 * 수강 중 학생 [course id, 학번, 이름, 동의서 제출 여부, 학생 로그인(sjpt)에서 확인됨], 학번 순.
	 * consent 테이블은 findSignedIds처럼 직접 읽는다.
	 */
	@Query(value = """
			select e.course_id, e.student_id, e.student_name,
			exists (select 1 from consent s where s.enrollment_id = e.id), e.in_sjpt from enrollment e
			join course c on c.id = e.course_id
			where c.semester_id = :semesterId and (e.in_latest_roster or e.in_sjpt)
			order by e.student_id""", nativeQuery = true)
	List<Object[]> findActiveStudents(long semesterId);

}

interface RosterUploadRepository extends JpaRepository<RosterUpload, Long> {

	List<RosterUpload> findTop10ByOrderByUploadedAtDesc();

	Optional<RosterUpload> findTopBySemesterOrderByIdDesc(Semester semester);

	Optional<RosterUpload> findTopBySemesterIdOrderByIdDesc(long semesterId);

}
