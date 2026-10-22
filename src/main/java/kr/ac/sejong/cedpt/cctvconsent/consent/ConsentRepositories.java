package kr.ac.sejong.cedpt.cctvconsent.consent;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.ac.sejong.cedpt.cctvconsent.roster.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface ConsentRepository extends JpaRepository<Consent, Long> {

	List<Consent> findByEnrollmentIn(Collection<Enrollment> enrollments);

	Optional<Consent> findByEnrollment(Enrollment enrollment);

}

interface ConsentDocumentRepository extends JpaRepository<ConsentDocument, Long> {

	/** 서명 이미지만 읽는다(PDF까지 읽지 않게) */
	@Query("select d.signaturePng from ConsentDocument d where d.id = :consentId")
	Optional<byte[]> findSignaturePng(long consentId);

}

/** 학생 본인이 동의할 수 있는 수강: 최신 명부 또는 sjpt에서 확인됐고 학기가 끝나지 않은 것. */
interface StudentEnrollmentRepository extends Repository<Enrollment, Long> {

	@Query("""
			select e from Enrollment e join fetch e.course c join fetch c.semester s
			where e.studentId = :studentId and (e.inLatestRoster = true or e.inSjpt = true) and s.endsOn >= :today
			order by s.year desc, s.term desc, c.name""")
	List<Enrollment> findOpen(String studentId, LocalDate today);

	@Query("""
			select e from Enrollment e join fetch e.course c join fetch c.semester s
			where e.id = :id and e.studentId = :studentId and (e.inLatestRoster = true or e.inSjpt = true) and s.endsOn >= :today""")
	Optional<Enrollment> findOpenById(long id, String studentId, LocalDate today);

	/**
	 * findOpenById를 여러 개 한 번에 하고, 제출이 끝날 때까지 수강 행을 잠가(FOR SHARE) 학기 초기화나 과목 삭제가 그 사이에
	 * 지우지 못하게 한다. 명부 반영, sjpt 동기화(EnrollmentRepository.lockByIds, lockByCourse)와 교착하지 않도록 같은 id 순서로
	 * 잠근다. 이미 지워졌거나 조건에 맞지 않는 수강은 빠진다.
	 */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("""
			select e from Enrollment e join fetch e.course c join fetch c.semester s
			where e.id in :ids and e.studentId = :studentId and (e.inLatestRoster = true or e.inSjpt = true)
			and s.endsOn >= :today order by e.id""")
	List<Enrollment> lockOpenByIds(Collection<Long> ids, String studentId, LocalDate today);

}
