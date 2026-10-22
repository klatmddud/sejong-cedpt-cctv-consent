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

}

interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

	@Query("select e from Enrollment e join fetch e.course c where c.semester = :semester")
	List<Enrollment> findBySemester(Semester semester);

	@Query("select e from Enrollment e join fetch e.course c where c.semester = :semester and e.studentId = :studentId")
	List<Enrollment> findBySemesterAndStudentId(Semester semester, String studentId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Enrollment e where e.id in :ids")
	List<Enrollment> lockByIds(Collection<Long> ids);

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

}

interface RosterUploadRepository extends JpaRepository<RosterUpload, Long> {

	List<RosterUpload> findTop10ByOrderByUploadedAtDesc();

}
