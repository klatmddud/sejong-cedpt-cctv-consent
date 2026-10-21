package kr.ac.sejong.cedpt.cctvconsent.consent;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.ac.sejong.cedpt.cctvconsent.roster.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface ConsentRepository extends JpaRepository<Consent, Long> {

	List<Consent> findByEnrollmentIn(Collection<Enrollment> enrollments);

	Optional<Consent> findByEnrollment(Enrollment enrollment);

}

interface ConsentDocumentRepository extends JpaRepository<ConsentDocument, Long> {
}

/** 학생 본인이 동의할 수 있는 수강: 최신 명부에 있고 학기가 끝나지 않은 것. */
interface StudentEnrollmentRepository extends Repository<Enrollment, Long> {

	@Query("""
			select e from Enrollment e join fetch e.course c join fetch c.semester s
			where e.studentId = :studentId and e.inLatestRoster = true and s.endsOn >= :today
			order by s.year desc, s.term desc, c.name""")
	List<Enrollment> findOpen(String studentId, LocalDate today);

	@Query("""
			select e from Enrollment e join fetch e.course c join fetch c.semester s
			where e.id = :id and e.studentId = :studentId and e.inLatestRoster = true and s.endsOn >= :today""")
	Optional<Enrollment> findOpenById(long id, String studentId, LocalDate today);

}
