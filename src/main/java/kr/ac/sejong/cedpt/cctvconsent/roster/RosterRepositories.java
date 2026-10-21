package kr.ac.sejong.cedpt.cctvconsent.roster;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SemesterRepository extends JpaRepository<Semester, Long> {

	Optional<Semester> findByYearAndTerm(int year, String term);

	/** 같은 학기 명부가 동시에 올라오면 하나씩 처리되도록 학기 행을 잠근다. */
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

}

interface RosterUploadRepository extends JpaRepository<RosterUpload, Long> {
}
