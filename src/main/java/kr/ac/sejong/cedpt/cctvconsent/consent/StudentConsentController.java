package kr.ac.sejong.cedpt.cctvconsent.consent;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kr.ac.sejong.cedpt.cctvconsent.auth.StudentPrincipal;
import kr.ac.sejong.cedpt.cctvconsent.roster.TargetRooms;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.WebAttributes;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 학생 화면. 언어는 첫 화면에서 고른 값(쿠키)을 따른다.
 */
@Controller
class StudentConsentController {

	/** 언어 선택 화면에서 고른 언어에 따라 바로 바꿔 보여 주는 문구. */
	record LandingText(String cta, String rooms) {
	}

	private final ConsentService consents;
	private final MessageSource messages;
	private final TargetRooms targetRooms;

	StudentConsentController(ConsentService consents, MessageSource messages, TargetRooms targetRooms) {
		this.consents = consents;
		this.messages = messages;
		this.targetRooms = targetRooms;
	}

	@GetMapping("/")
	String landing(Model model) {
		// 지금 쿠키 언어와 관계없이 두 언어 문구를 모두 넘긴다. 화면에서 언어를 고르면 스크립트가 바꿔 넣는다
		model.addAttribute("landingText", Map.of("ko", landingText(Locale.KOREAN), "en", landingText(Locale.ENGLISH)));
		model.addAttribute("rooms", targetRooms.ids());
		return "student/landing";
	}

	private LandingText landingText(Locale locale) {
		return new LandingText(messages.getMessage("landing.continue", null, locale),
				messages.getMessage("landing.rooms", null, locale));
	}

	@GetMapping("/login")
	String login(HttpSession session, Model model, Locale locale) {
		// 로그인 실패 이유(학번 잠김, 포털 오류 등)를 한 번만 보여 준다
		Object error = session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
		if (error instanceof AuthenticationException ex) {
			model.addAttribute("loginError", messages.getMessage(ex.getMessage(), null, ex.getMessage(), locale));
			session.removeAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
		}
		return "student/login";
	}

	@GetMapping("/consents")
	String list(@AuthenticationPrincipal StudentPrincipal student, HttpSession session, Model model) {
		session.removeAttribute(SUBMISSION);
		List<ConsentService.CourseConsent> courses = consents.courses(student.studentId());
		model.addAttribute("student", student);
		model.addAttribute("courses", courses);
		model.addAttribute("pending", courses.stream().filter(course -> !course.signed()).count());
		return "student/consents";
	}

	/** 제출 직후 완료 화면에 보여 줄 내용. 언어를 바꿔 다시 열어도 보이도록 세션에 둔다 */
	record Submission(String semester, List<String> courses) implements Serializable {
	}

	private static final String SUBMISSION = StudentConsentController.class.getName() + ".SUBMISSION";

	/**
	 * 제출이 거절되어 돌아왔으면 학생이 골랐던 수업(플래시 값 selected)을, 아니면 enrollmentId 수업을 골라 둔다.
	 * 본인의 미제출 수업이 하나도 없으면 무시하고 모든 수업을 골라 둔다.
	 *
	 * @param enrollmentId 내 수업에서 "지금 작성하기"를 누른 수업
	 */
	@GetMapping("/consents/sign")
	String form(@AuthenticationPrincipal StudentPrincipal student,
			@RequestParam(required = false) Long enrollmentId, Model model) {
		List<ConsentService.CourseConsent> pending = consents.courses(student.studentId()).stream()
				.filter(course -> !course.signed())
				.toList();
		if (pending.isEmpty()) {
			return "redirect:/consents";
		}
		Collection<?> selected = model.getAttribute("selected") instanceof Collection<?> flash ? flash : List.of();
		Collection<?> wanted = !selected.isEmpty() ? selected
				: enrollmentId != null ? List.of(enrollmentId) : List.of();
		List<Long> preselected = pending.stream().map(ConsentService.CourseConsent::enrollmentId)
				.filter(wanted::contains)
				.toList();
		model.addAttribute("student", student);
		model.addAttribute("courses", pending);
		model.addAttribute("preselected", preselected.isEmpty() ? null : preselected);
		return "student/consent-form";
	}

	@PostMapping("/consents/sign")
	String submit(@AuthenticationPrincipal StudentPrincipal student,
			@RequestParam(name = "enrollmentIds", required = false) List<Long> enrollmentIds,
			@RequestParam(defaultValue = "false") boolean agreed, @RequestParam(required = false) String signature,
			Locale locale, HttpSession session, RedirectAttributes redirect) {
		try {
			consents.submit(student.studentId(), student.name(), enrollmentIds,
					ConsentLanguage.fromLocale(locale), agreed, signature);
		}
		catch (ConsentException ex) {
			redirect.addFlashAttribute("error", messages.getMessage(ex.getMessage(), null, ex.getMessage(), locale));
			// 다시 열린 화면에서 학생이 골랐던 수업만 골라 둔다(모든 수업이 골라진 채로 다시 제출하지 않게)
			redirect.addFlashAttribute("selected", enrollmentIds == null ? List.of() : enrollmentIds);
			return "redirect:/consents/sign";
		}
		// 제출 완료 화면에 보여 줄 수업 이름. 새로 고침해도 다시 제출되지 않게 GET 화면으로 보낸다
		List<ConsentService.CourseConsent> done = consents.courses(student.studentId()).stream()
				.filter(course -> course.signed() && enrollmentIds.contains(course.enrollmentId()))
				.toList();
		session.setAttribute(SUBMISSION, new Submission(done.isEmpty() ? "" : done.getFirst().semester(),
				done.stream().map(ConsentService.CourseConsent::courseName).toList()));
		return "redirect:/consents/complete";
	}

	/** 제출한 뒤에만 보여 준다. 언어를 바꾸거나 새로 고쳐도 보이고, 내 수업으로 가면 지운다. 제출한 적이 없으면 내 수업으로 보낸다. */
	@GetMapping("/consents/complete")
	String complete(@AuthenticationPrincipal StudentPrincipal student, HttpSession session, Model model) {
		if (!(session.getAttribute(SUBMISSION) instanceof Submission submission)) {
			return "redirect:/consents";
		}
		model.addAttribute("student", student);
		model.addAttribute("submittedSemester", submission.semester());
		model.addAttribute("submittedCourses", submission.courses());
		return "student/complete";
	}

	@GetMapping("/consents/{enrollmentId}/pdf")
	ResponseEntity<byte[]> pdf(@AuthenticationPrincipal StudentPrincipal student, @PathVariable long enrollmentId) {
		ConsentService.PdfFile file = consents.pdf(student.studentId(), enrollmentId);
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.inline().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
				.body(file.content());
	}

	/** 내 수업에서 제출한 동의서를 펼칠 때 보여 주는 서명. 접근 조건은 PDF와 같다(남의 수강이면 404). */
	@GetMapping("/consents/{enrollmentId}/signature")
	ResponseEntity<byte[]> signature(@AuthenticationPrincipal StudentPrincipal student, @PathVariable long enrollmentId) {
		// Cache-Control: no-store, X-Content-Type-Options: nosniff는 Spring Security 기본 헤더가 붙인다
		return ResponseEntity.ok()
				.contentType(MediaType.IMAGE_PNG)
				.body(consents.signature(student.studentId(), enrollmentId));
	}

}
